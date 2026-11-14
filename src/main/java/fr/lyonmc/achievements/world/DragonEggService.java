package fr.lyonmc.achievements.world;

import fr.lyonmc.achievements.LyonAchievements;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Œuf de l'End. En temps normal, un seul œuf existe (au premier dragon) : un seul joueur peut avoir le succès
 * « La nouvelle génération ». Ici, un nouvel œuf apparaît sur le portail de sortie à chaque dragon tué
 * (s'il n'y en a pas déjà un), et l'œuf d'un joueur qui a déjà le succès retourne sur le portail :
 * il reste rare, et tout le monde peut l'obtenir à son tour.
 */
public final class DragonEggService implements Listener {

    private static final NamespacedKey ADVANCEMENT = NamespacedKey.minecraft("end/dragon_egg");
    /** Résultats de la pose d'un œuf. */
    private static final int IMPOSSIBLE = 0, PLACED = 1, ALREADY_THERE = 2;

    private final LyonAchievements plugin;

    public DragonEggService(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private boolean respawnEnabled() {
        return plugin.getConfig().getBoolean("oeuf-dragon.reapparition", true);
    }

    private boolean returnEnabled() {
        return plugin.getConfig().getBoolean("oeuf-dragon.retour-apres-succes", true);
    }

    /** Un œuf rendu alors que le portail n'était pas chargé : il sera posé dès que possible. */
    private boolean owed() {
        return plugin.configs().state().getBoolean("oeuf-dragon.en-attente", false);
    }

    private void setOwed(boolean owed) {
        if (owed == owed()) return;
        plugin.configs().state().set("oeuf-dragon.en-attente", owed ? true : null);
        plugin.configs().saveState();
    }

    /** L'End principal (celui du dragon). */
    private static World endWorld() {
        World main = Bukkit.getWorld("world_the_end");
        if (main != null) return main;
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.THE_END && w.getEnderDragonBattle() != null) return w;
        }
        return null;
    }

    /**
     * Pose un œuf en haut du pilier de bedrock du portail de sortie, là où le jeu pose le premier.
     * Rien n'est chargé exprès : si le portail n'est pas chargé ou pas encore construit, c'est IMPOSSIBLE.
     */
    private static int place(World end) {
        if (end == null) return IMPOSSIBLE;
        DragonBattle battle = end.getEnderDragonBattle();
        Location portal = battle == null ? null : battle.getEndPortalLocation();
        int x = portal == null ? 0 : portal.getBlockX(), z = portal == null ? 0 : portal.getBlockZ();
        if (!end.isChunkLoaded(x >> 4, z >> 4)) return IMPOSSIBLE;
        Block top = end.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
        if (top.getType() == Material.DRAGON_EGG) return ALREADY_THERE;
        if (top.getType() != Material.BEDROCK) return IMPOSSIBLE;
        Block spot = top.getRelative(BlockFace.UP);
        if (!spot.getType().isAir()) return IMPOSSIBLE;
        spot.setType(Material.DRAGON_EGG);
        end.playSound(spot.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.5f, 0.8f);
        return PLACED;
    }

    /** Dragon tué : un nouvel œuf apparaît sur le portail (après l'animation de mort, où le jeu pose le premier). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon) || !respawnEnabled()) return;
        World w = event.getEntity().getWorld();
        if (w.getEnvironment() != World.Environment.THE_END) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int r = place(w);
            if (r == PLACED) {
                setOwed(false);
                plugin.getLogger().info("Dragon tué : un nouvel œuf de dragon est posé sur le portail de l'End.");
                for (Player p : w.getPlayers()) {
                    plugin.msg(p, "<light_purple>Un nouvel œuf de dragon est apparu sur le portail !</light_purple> "
                            + "<gray>Le premier qui le ramasse obtient le succès <white>La nouvelle génération</white>.");
                }
            } else if (r == IMPOSSIBLE) {
                setOwed(true);
            }
        }, 20L * 20);
    }

    /** Succès obtenu : l'œuf repart sur le portail quelques secondes plus tard (le temps de voir le succès). */
    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!returnEnabled() || !event.getAdvancement().getKey().equals(ADVANCEMENT)) return;
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> takeBack(p), 60L);
    }

    /** Toutes les 10 secondes : œufs des joueurs qui ont déjà le succès, et œuf en attente du portail. */
    public void tick() {
        if (returnEnabled()) for (Player p : Bukkit.getOnlinePlayers()) takeBack(p);
        if (owed()) {
            World end = endWorld();
            if (end != null && !end.getPlayers().isEmpty() && place(end) != IMPOSSIBLE) {
                setOwed(false);
                plugin.getLogger().info("Œuf de dragon en attente posé sur le portail de l'End.");
            }
        }
    }

    /** Retire les œufs d'un joueur (en survie ou aventure) qui a déjà le succès, et en repose un sur le portail. */
    private void takeBack(Player p) {
        if (!p.isOnline() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        PlayerInventory inv = p.getInventory();
        if (!inv.contains(Material.DRAGON_EGG) && p.getItemOnCursor().getType() != Material.DRAGON_EGG) return;
        Advancement adv = Bukkit.getAdvancement(ADVANCEMENT);
        if (adv == null || !p.getAdvancementProgress(adv).isDone()) return;
        int removed = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == Material.DRAGON_EGG) {
                removed += it.getAmount();
                inv.setItem(i, null);
            }
        }
        if (p.getItemOnCursor().getType() == Material.DRAGON_EGG) {
            removed += p.getItemOnCursor().getAmount();
            p.setItemOnCursor(null);
        }
        if (removed == 0) return;
        int r = place(endWorld());
        if (r == IMPOSSIBLE) setOwed(true);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
        plugin.msg(p, "<gray>Tu as le succès <white>La nouvelle génération</white> : l'œuf de dragon retourne sur le portail de l'End "
                + "pour le prochain joueur.");
        plugin.getLogger().info("Œuf de dragon de " + p.getName() + " rendu" + (r == PLACED ? " : posé sur le portail de l'End."
                : r == ALREADY_THERE ? " (un œuf attend déjà sur le portail)." : " : portail pas chargé, il sera posé plus tard."));
    }
}
