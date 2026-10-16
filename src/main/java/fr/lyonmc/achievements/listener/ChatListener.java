package fr.lyonmc.achievements.listener;

import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat : badge de rang + pseudo survolable (carte de stats) + message.
 * Les composants sont préparés sur le thread principal puis lus par le chat (asynchrone).
 */
public final class ChatListener implements Listener {

    private record Card(Component badge, Component name) {
    }

    private final JavaPlugin plugin;
    private final AdvancementRegistry registry;
    private final StatsService stats;
    private final Text text;
    private final Map<UUID, Card> cards = new ConcurrentHashMap<>();

    public ChatListener(JavaPlugin plugin, AdvancementRegistry registry, StatsService stats, Text text) {
        this.plugin = plugin;
        this.registry = registry;
        this.stats = stats;
        this.text = text;
    }

    /** Appelé sur le thread principal à chaque rafraîchissement. */
    public void refreshAll() {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    public void refresh(Player p) {
        PlayerStats s = stats.get(p.getUniqueId());
        int rank = stats.rank(p.getUniqueId());
        TagResolver r = text.resolver(registry, stats, s, p);

        Component badge = text.parse(plugin.getConfig().getString("chat.badge." + Text.rankKey(rank), ""), r);
        TextColor nameColor = TextColor.fromHexString(plugin.getConfig().getString("chat.name-color", "#e6e6e6"));
        Component hover = text.lines(plugin.getConfig().getStringList("chat.hover"), r);
        Component name = Component.text(p.getName(), nameColor == null ? Text.WHITE : nameColor)
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.runCommand("/succes " + p.getName()));
        cards.put(p.getUniqueId(), new Card(badge, name));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        Player source = event.getPlayer();
        Card card = cards.get(source.getUniqueId());
        String format = plugin.getConfig().getString("chat.format", "<badge><name> <dark_gray>›</dark_gray> <message>");
        TextColor msgColor = TextColor.fromHexString(plugin.getConfig().getString("chat.message-color", "#bfbfbf"));
        Component badge = card == null ? Component.empty() : card.badge();
        Component name = card == null ? Component.text(source.getName(), Text.WHITE) : card.name();

        event.renderer((player, displayName, message, viewer) -> text.parse(format,
                Placeholder.component("badge", badge),
                Placeholder.component("name", name),
                Placeholder.component("message", message.colorIfAbsent(msgColor == null ? Text.GRAY : msgColor))));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        Player p = event.getPlayer();
        TagResolver r = Placeholder.unparsed("player", p.getName());
        event.joinMessage(text.parse(plugin.getConfig().getString("chat.join-message", ""), r));
        if (!p.hasPlayedBefore()) {
            String first = plugin.getConfig().getString("chat.first-join-message", "");
            if (!first.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(text.parse(first, r)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        cards.remove(event.getPlayer().getUniqueId());
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        event.quitMessage(text.parse(plugin.getConfig().getString("chat.quit-message", ""),
                Placeholder.unparsed("player", event.getPlayer().getName())));
    }
}
