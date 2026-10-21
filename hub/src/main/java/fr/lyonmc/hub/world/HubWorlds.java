package fr.lyonmc.hub.world;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Monde(s) vide(s) du hub : création, chargement au démarrage, retour au spawn si on tombe dans le vide. */
public final class HubWorlds implements Listener {

    private final JavaPlugin plugin;

    public HubWorlds(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public List<String> names() {
        return plugin.getConfig().getStringList("worlds");
    }

    /** Charge les mondes du hub qui existent déjà sur le disque. */
    public void loadExisting() {
        for (String name : names()) {
            if (Bukkit.getWorld(name) != null) continue;
            if (new File(Bukkit.getWorldContainer(), name).isDirectory()) {
                World w = create(name);
                plugin.getLogger().info("Monde du hub chargé : " + (w == null ? "échec pour " + name : w.getName()));
            }
        }
    }

    /** Crée (ou charge) un monde vide, avec une petite plateforme au premier lancement. */
    public World create(String name) {
        World w = new WorldCreator(name).generator(new VoidGenerator()).environment(World.Environment.NORMAL).createWorld();
        if (w == null) return null;
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_FIRE_TICK, false);
        w.setGameRule(GameRule.MOB_GRIEFING, false);
        w.setGameRule(GameRule.DO_INSOMNIA, false);
        w.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        w.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        w.setGameRule(GameRule.SPAWN_CHUNK_RADIUS, 2);
        w.setTime(6000);
        w.setStorm(false);
        w.setThundering(false);
        if (w.getBlockAt(0, 64, 0).getType() == Material.AIR) {
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) w.getBlockAt(x, 64, z).setType(Material.SMOOTH_STONE, false);
            }
            w.setSpawnLocation(0, 65, 0);
        }
        if (!names().contains(name)) {
            List<String> list = new ArrayList<>(names());
            list.add(name);
            plugin.getConfig().set("worlds", list);
            plugin.saveConfig();
        }
        return w;
    }

    /** La petite plateforme de départ de /lyonhub monde est-elle encore là ? */
    public boolean hasStartPlatform(World w) {
        return isHub(w) && w.getBlockAt(0, 64, 0).getType() == Material.SMOOTH_STONE;
    }

    public boolean isHub(World w) {
        return w != null && names().contains(w.getName());
    }

    private void rescue(Player p) {
        Location spawn = p.getWorld().getSpawnLocation().add(0.5, 0, 0.5);
        p.setFallDistance(0);
        p.teleportAsync(spawn);
    }

    /** Aucun succès Minecraft ne s'obtient au hub (les succès LyonMC restent possibles). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCriterion(com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent e) {
        if (isHub(e.getPlayer().getWorld()) && e.getAdvancement().getKey().getNamespace().equals("minecraft")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.getConfig().getBoolean("void-rescue", true)) return;
        if (e.getTo().getBlockY() == e.getFrom().getBlockY()) return;
        World w = e.getTo().getWorld();
        if (isHub(w) && e.getTo().getY() < w.getMinHeight() - 8) rescue(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVoid(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.VOID || !(e.getEntity() instanceof Player p)) return;
        if (!plugin.getConfig().getBoolean("void-rescue", true) || !isHub(p.getWorld())) return;
        e.setCancelled(true);
        rescue(p);
    }
}
