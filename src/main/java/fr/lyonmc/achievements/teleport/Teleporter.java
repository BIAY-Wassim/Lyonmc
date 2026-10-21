package fr.lyonmc.achievements.teleport;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Téléportations avec délai (annulé si le joueur bouge ou prend des dégâts) et mémoire pour /back. */
public final class Teleporter implements Listener {

    private record Pending(BukkitTask task, Location origin) {
    }

    private final LyonAchievements plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Location> back = new HashMap<>();

    public Teleporter(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    public int warmup(Player p) {
        if (p.hasPermission("lyonmc.teleport.instant")) return 0;
        return Math.max(0, plugin.getConfig().getInt("teleport.warmup-seconds", 3));
    }

    public void teleport(Player player, Location to, String label) {
        teleport(player, to, label, null);
    }

    /** Téléporte après le délai. after est appelé une fois arrivé. */
    public void teleport(Player player, Location to, String label, Consumer<Player> after) {
        if (to == null || to.getWorld() == null) {
            plugin.msg(player, "<gray>Destination introuvable.");
            return;
        }
        if (player.isDead()) return;
        cancel(player.getUniqueId(), false);
        int seconds = warmup(player);
        if (seconds <= 0) {
            go(player, to, label, after);
            return;
        }
        Location origin = player.getLocation().clone();
        int[] left = {seconds * 2};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
                cancel(player.getUniqueId(), false);
                return;
            }
            Location now = player.getLocation();
            if (now.getWorld() != origin.getWorld() || now.distanceSquared(origin) > 0.36) {
                cancel(player.getUniqueId(), true);
                return;
            }
            if (left[0] <= 0) {
                Pending p = pending.remove(player.getUniqueId());
                if (p != null) p.task().cancel();
                go(player, to, label, after);
                return;
            }
            if (left[0] % 2 == 0) {
                player.sendActionBar(Component.text("Téléportation vers " + label + " dans " + (left[0] / 2) + " s · ne bouge pas", Text.GRAY));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.6f);
            }
            left[0]--;
        }, 0L, 10L);
        pending.put(player.getUniqueId(), new Pending(task, origin));
    }

    private void go(Player player, Location to, String label, Consumer<Player> after) {
        if (!player.isOnline() || player.isDead()) return;
        back.put(player.getUniqueId(), player.getLocation().clone());
        player.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            if (!ok) return;
            player.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f);
            player.sendActionBar(Component.text("Téléporté : " + label, Text.GRAY));
            if (after != null) after.accept(player);
        });
    }

    public void cancel(UUID uuid, boolean notify) {
        Pending p = pending.remove(uuid);
        if (p == null) return;
        p.task().cancel();
        Player player = Bukkit.getPlayer(uuid);
        if (notify && player != null) {
            player.sendActionBar(Component.text("Téléportation annulée", Text.GRAY));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1f);
        }
    }

    public Location back(UUID uuid) {
        return back.get(uuid);
    }

    /** Fixe la position de /back (ex. : là où le joueur s'était déconnecté avant d'arriver au hub). */
    public void remember(UUID uuid, Location location) {
        if (location != null && location.getWorld() != null) back.put(uuid, location.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && pending.containsKey(p.getUniqueId())) cancel(p.getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        back.put(event.getEntity().getUniqueId(), event.getEntity().getLocation().clone());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), false);
    }
}
