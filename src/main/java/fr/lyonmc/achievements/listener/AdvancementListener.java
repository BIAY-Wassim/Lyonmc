package fr.lyonmc.achievements.listener;

import fr.lyonmc.achievements.board.BoardManager;
import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.util.Text;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public final class AdvancementListener implements Listener {

    private final JavaPlugin plugin;
    private final AdvancementRegistry registry;
    private final StatsService stats;
    private final BoardManager boards;
    private final Text text;

    public AdvancementListener(JavaPlugin plugin, AdvancementRegistry registry, StatsService stats,
                               BoardManager boards, Text text) {
        this.plugin = plugin;
        this.registry = registry;
        this.stats = stats;
        this.boards = boards;
        this.text = text;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        stats.startSession(p.getUniqueId());
        stats.refreshOnline(p);
        stats.rebuildRanking();
        boards.create(p);
        boards.markOrderDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        stats.refreshOnline(p);
        stats.endSession(p.getUniqueId());
        boards.remove(p);
        boards.markOrderDirty();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        String key = event.getAdvancement().getKey().toString();
        AdvancementRegistry.Entry entry = registry.get(key);
        if (entry == null) return; // recette ou succès invisible

        UUID uuid = player.getUniqueId();
        PlayerStats before = stats.get(uuid);
        int oldCount = before == null ? 0 : before.count();
        int oldRank = stats.rank(uuid);
        PlayerStats oldLeader = stats.top(1);

        PlayerStats s = stats.getOrCreate(uuid, player.getName());
        s.done().put(key, System.currentTimeMillis());
        s.recompute(registry);
        stats.rebuildRanking();
        boards.markOrderDirty();

        int total = registry.total();
        int newCount = s.count();
        if (newCount == oldCount) return; // déjà compté

        boolean challenge = entry.frame() == AdvancementDisplay.Frame.CHALLENGE;
        TagResolver base = text.resolver(registry, stats, s, player);
        Component advComponent = text.advancement(registry, stats, entry);
        TagResolver all = TagResolver.resolver(base, Placeholder.component("advancement", advComponent));

        // Annonce
        if (plugin.getConfig().getBoolean("announcements.replace-vanilla", true)) {
            event.message(null);
            String path = challenge ? "announcements.challenge-message" : "announcements.message";
            Bukkit.broadcast(text.parse(plugin.getConfig().getString(path, ""), all));
        }

        // Effets pour le joueur
        if (challenge) {
            player.showTitle(Title.title(
                    text.parse(plugin.getConfig().getString("announcements.challenge-title", ""), all),
                    text.parse(plugin.getConfig().getString("announcements.challenge-subtitle", "<advancement>"), all),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 0.8f);
        }

        // Paliers
        List<Integer> milestones = plugin.getConfig().getIntegerList("announcements.milestones");
        for (int m : milestones) {
            double threshold = total * m / 100.0;
            if (oldCount < threshold && newCount >= threshold) {
                Bukkit.broadcast(text.parse(plugin.getConfig().getString("announcements.milestone-message", ""),
                        all, Placeholder.unparsed("milestone", String.valueOf(m))));
                for (Player online : Bukkit.getOnlinePlayers()) {
                    online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 1.2f);
                }
            }
        }

        // Tous les succès
        if (newCount >= total && oldCount < total) {
            Bukkit.broadcast(text.parse(plugin.getConfig().getString("announcements.all-completed-message", ""), all));
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }

        // Classement
        int newRank = stats.rank(uuid);
        PlayerStats newLeader = stats.top(1);
        if (newLeader == s && oldLeader != s && stats.ranking().size() > 1) {
            Bukkit.broadcast(text.parse(plugin.getConfig().getString("announcements.new-leader-message", ""), all));
        } else if (newRank > 0 && (oldRank == 0 || newRank < oldRank)) {
            player.sendMessage(text.parse(plugin.getConfig().getString("announcements.rank-up-message", ""), all));
        }
    }
}
