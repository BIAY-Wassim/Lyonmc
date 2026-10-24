package fr.lyonmc.communaute;

import fr.lyonmc.achievements.api.AfkProvider;
import fr.lyonmc.achievements.core.Profile;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fidélité : détection AFK, temps de jeu réel (hors AFK et hub) récompensé, cadeau de retour.
 * Gère aussi l'arrivée des joueurs (création des données, récompenses en attente).
 */
public final class Loyalty implements Listener, AfkProvider {

    private final Communaute plugin;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    private int seconds;

    Loyalty(Communaute plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isAfk(UUID uuid) {
        return afk.contains(uuid);
    }

    private void active(Player p) {
        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());
        if (afk.remove(p.getUniqueId())) {
            p.sendActionBar(plugin.parse("<gray>Tu n'es plus AFK."));
        }
    }

    // ───────────────────────────── Arrivée ─────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerData known = plugin.store().get(p.getUniqueId());
        long previous = known != null ? known.lastSeen : 0;
        if (previous == 0) {
            // Pas encore connu de COMMUNAUTE : on prend la dernière venue enregistrée par LyonAchievements
            Profile pr = plugin.lyon().profiles().get(p.getUniqueId());
            if (pr != null) previous = pr.lastSeen;
        }
        joined(p, previous);
        long prev = previous;
        boolean fresh = !p.hasPlayedBefore();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.deliverPending(p);
            plugin.votes().onJoin(p);
            plugin.welcome().onJoin(p, fresh);
            comeback(p, prev);
        }, 40L);
    }

    /** Données du joueur à jour (aussi pour les joueurs déjà connectés au démarrage du plugin). */
    void joined(Player p, long previous) {
        boolean known = plugin.store().get(p.getUniqueId()) != null;
        PlayerData d = plugin.store().get(p);
        if (!known) {
            d.activeSeconds = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
            double already = plugin.getConfig().getDouble("accueil.deja-joueur-heures", 3);
            d.tutorial = p.hasPlayedBefore() && d.activeSeconds >= already * 3600 ? "ignore" : "actif";
            d.lastSeen = previous;
        }
        String ip = plugin.store().hashIp(p);
        if (ip != null) d.ipHash = ip;
        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());
    }

    private void comeback(Player p, long previous) {
        PlayerData d = plugin.store().get(p);
        long now = System.currentTimeMillis();
        d.lastSeen = now;
        if (!plugin.getConfig().getBoolean("retour.enabled", true) || previous <= 0) return;
        long days = (now - previous) / 86_400_000L;
        if (days < plugin.getConfig().getInt("retour.jours", 7) || d.activeSeconds < 3600) return;
        plugin.give(p, "retour.recompense");
        Bukkit.broadcast(plugin.parse(plugin.getConfig().getString("retour.annonce", ""),
                Placeholder.unparsed("player", p.getName()), Placeholder.unparsed("jours", String.valueOf(days))));
        p.sendMessage(plugin.lyon().prefixed("<gray>Content de te revoir ! Cadeau de retour : ").append(plugin.describe("retour.recompense")));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        PlayerData d = plugin.store().get(u);
        if (d != null) d.lastSeen = System.currentTimeMillis();
        lastActivity.remove(u);
        afk.remove(u);
    }

    // ───────────────────────────── AFK ─────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        // Seul un mouvement de la souris compte (les machines anti-AFK poussent le joueur sans le faire tourner)
        if (e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch()) active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent e) {
        lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        UUID u = e.getPlayer().getUniqueId();
        if (afk.contains(u)) Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(u);
            if (p != null) active(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) active(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        active(e.getPlayer());
    }

    /** Le temps de jeu Minecraft ne compte pas quand on est AFK. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStatistic(PlayerStatisticIncrementEvent e) {
        if (e.getStatistic() == Statistic.PLAY_ONE_MINUTE && afk.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    // ───────────────────────────── Chaque seconde ─────────────────────────────

    void tickSecond() {
        seconds++;
        long now = System.currentTimeMillis();
        boolean afkOn = plugin.getConfig().getBoolean("afk.enabled", true);
        long afkMs = Math.max(1, plugin.getConfig().getLong("afk.minutes", 5)) * 60_000L;
        boolean playOn = plugin.getConfig().getBoolean("temps-de-jeu.enabled", true);
        long playEvery = Math.max(1, plugin.getConfig().getLong("temps-de-jeu.minutes", 30)) * 60L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (afkOn && seconds % 5 == 0 && !afk.contains(u) && now - lastActivity.getOrDefault(u, now) > afkMs) {
                afk.add(u);
                p.sendActionBar(plugin.parse("<gray>Tu es AFK : ton temps de jeu ne compte plus."));
            }
            if (afk.contains(u) || plugin.inHub(p)) continue;
            PlayerData d = plugin.store().get(p);
            d.activeSeconds++;
            if (!playOn) continue;
            d.playAccumulator++;
            if (d.playAccumulator >= playEvery) {
                d.playAccumulator = 0;
                plugin.give(p, "temps-de-jeu.recompense");
                p.sendMessage(plugin.lyon().prefixed("<gray>Merci de jouer sur LyonMC ! <dark_gray>(" + (playEvery / 60) + " min de jeu)</dark_gray> ")
                        .append(plugin.describe("temps-de-jeu.recompense")));
            }
        }
    }
}
