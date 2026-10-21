package fr.lyonmc.hub.player;

import fr.lyonmc.hub.world.HubWorlds;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dernière position de chaque joueur dans le vrai monde survie (pas le hub, pas le monde ressources).
 * /survie (et « Survie » dans le menu du hub) y ramène, même après un passage au hub puis au monde ressources.
 */
public final class SurvivalPositions implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final HubWorlds worlds;
    private final File file;
    private final YamlConfiguration data;
    private final Set<UUID> warmups = new HashSet<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public SurvivalPositions(JavaPlugin plugin, HubWorlds worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.file = new File(plugin.getDataFolder(), "positions-survie.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    /** Le vrai monde survie : ni le hub, ni un monde listé dans « hors-survie » (le monde ressources). */
    public boolean isSurvival(World w) {
        return w != null && !worlds.isHub(w) && !plugin.getConfig().getStringList("hors-survie").contains(w.getName());
    }

    private void remember(Player p, Location l) {
        if (l == null || !isSurvival(l.getWorld())) return;
        String k = p.getUniqueId().toString();
        data.set(k + ".monde", l.getWorld().getName());
        data.set(k + ".x", l.getX());
        data.set(k + ".y", l.getY());
        data.set(k + ".z", l.getZ());
        data.set(k + ".yaw", (double) l.getYaw());
        data.set(k + ".pitch", (double) l.getPitch());
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'enregistrer positions-survie.yml : " + e.getMessage());
        }
    }

    public Location get(Player p) {
        String k = p.getUniqueId().toString();
        World w = Bukkit.getWorld(data.getString(k + ".monde", ""));
        if (w == null) return null;
        return new Location(w, data.getDouble(k + ".x"), data.getDouble(k + ".y"), data.getDouble(k + ".z"),
                (float) data.getDouble(k + ".yaw"), (float) data.getDouble(k + ".pitch"));
    }

    // ───────────────────────────── Suivi ─────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        // On quitte la survie (pour le hub ou le monde ressources) : on retient l'endroit
        if (isSurvival(e.getFrom().getWorld()) && !isSurvival(e.getTo().getWorld())) remember(e.getPlayer(), e.getFrom());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (isSurvival(p.getWorld()) && !isSurvival(e.getRespawnLocation().getWorld())) remember(p, p.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        remember(e.getPlayer(), e.getPlayer().getLocation());
        warmups.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!isSurvival(p.getWorld()) && !worlds.isHub(p.getWorld())) {
            p.sendMessage(MM.deserialize("<#e8c872>✦</#e8c872> <gray>Pour revenir là où tu étais en survie : <white><click:run_command:'/survie'><u>/survie</u></click></white>"));
        }
    }

    // ───────────────────────────── /survie ─────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        go(p);
        return true;
    }

    /** Renvoie en survie, là où le joueur était. Immédiat depuis le hub, 3 secondes sans bouger ailleurs. */
    public void go(Player p) {
        if (isSurvival(p.getWorld())) {
            p.sendMessage(MM.deserialize("<gray>Tu es déjà dans le monde survie."));
            return;
        }
        Location target = get(p);
        if (target == null) {
            p.sendMessage(MM.deserialize("<gray>Pas encore de position en survie : départ à un endroit au hasard."));
            p.performCommand("rtp");
            return;
        }
        if (worlds.isHub(p.getWorld())) {
            teleport(p, target);
            return;
        }
        long now = System.currentTimeMillis();
        if (now < cooldown.getOrDefault(p.getUniqueId(), 0L) || !warmups.add(p.getUniqueId())) return;
        cooldown.put(p.getUniqueId(), now + 3000);
        Location start = p.getLocation().clone();
        p.sendMessage(MM.deserialize("<gray>Retour en survie dans <white>3 secondes</white>, ne bouge pas…"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!warmups.remove(p.getUniqueId()) || !p.isOnline()) return;
            if (!p.getWorld().equals(start.getWorld()) || p.getLocation().distanceSquared(start) > 1.0) {
                p.sendMessage(MM.deserialize("<gray>Retour annulé : tu as bougé."));
                return;
            }
            teleport(p, target);
        }, 60L);
    }

    private void teleport(Player p, Location target) {
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
        p.teleportAsync(target);
    }
}
