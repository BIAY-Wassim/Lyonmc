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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat : badge de rang + pseudo survolable (carte de stats) + message.
 * Les composants sont préparés sur le thread principal puis lus par le chat (asynchrone).
 */
public final class ChatListener implements Listener {

    private record Card(Component badge, Component grade, Component name, Component title, Component team) {
    }

    private final fr.lyonmc.achievements.LyonAchievements plugin;
    private final AdvancementRegistry registry;
    private final StatsService stats;
    private final Text text;
    private final Map<UUID, Card> cards = new ConcurrentHashMap<>();

    public ChatListener(fr.lyonmc.achievements.LyonAchievements plugin, AdvancementRegistry registry, StatsService stats, Text text) {
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
        java.util.List<String> hoverLines = new java.util.ArrayList<>(plugin.getConfig().getStringList("chat.hover"));
        if (!hoverLines.isEmpty() && hoverLines.stream().noneMatch(l -> l.contains("<grade>"))) {
            hoverLines.add(Math.min(2, hoverLines.size()), "<dark_gray>✦ Grade</dark_gray> <grade>");
        }
        Component hover = text.lines(hoverLines, r);
        Component name = plugin.rewards().coloredName(p.getUniqueId(), p.getName(), nameColor == null ? Text.WHITE : nameColor)
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.runCommand("/succes " + p.getName()));
        fr.lyonmc.achievements.core.Profile profile = plugin.profiles().get(p.getUniqueId());
        Component title = plugin.rewards().titleTag(profile);
        Component team = plugin.teams().tagComponent(plugin.teams().teamOf(p.getUniqueId()));
        Component grade = plugin.grades().chatTag(p.getUniqueId());
        cards.put(p.getUniqueId(), new Card(badge, grade, name, title, team));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        Player source = event.getPlayer();
        Card card = cards.get(source.getUniqueId());
        String format = plugin.getConfig().getString("chat.format", "<badge><grade><team><title><name> <dark_gray>›</dark_gray> <message>");
        // Anciens config.yml sans <grade> : on le place avant l'équipe (ou avant le pseudo)
        if (!format.contains("<grade>")) {
            format = format.contains("<team>") ? format.replaceFirst("<team>", "<grade><team>")
                    : format.replaceFirst("<name>", "<grade><name>");
        }
        final String chatFormat = format;
        Component grade = card == null ? Component.empty() : card.grade();
        TextColor msgColor = TextColor.fromHexString(plugin.getConfig().getString("chat.message-color", "#bfbfbf"));
        Component badge = card == null ? Component.empty() : card.badge();
        Component name = card == null ? Component.text(source.getName(), Text.WHITE) : card.name();
        Component title = card == null ? Component.empty() : card.title();
        Component team = card == null ? Component.empty() : card.team();

        // Chat d'équipe : seuls les coéquipiers voient le message (sauf s'il commence par !)
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
        // [objet] : l'objet en main, survolable par les autres joueurs
        event.message(withItem(source, event.message(), plain));
        if (plugin.teams().inTeamChat(source.getUniqueId()) && plugin.teams().teamOf(source.getUniqueId()) != null) {
            if (plain.startsWith("!")) {
                event.message(event.message().replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                        .matchLiteral("!").times(1).replacement("").build()));
            } else {
                event.viewers().removeIf(a -> a instanceof Player vp
                        && !plugin.teams().sameTeam(vp.getUniqueId(), source.getUniqueId()));
                String teamFormat = plugin.getConfig().getString("chat.team-format",
                        "<team><dark_gray>(équipe)</dark_gray> <name> <dark_gray>›</dark_gray> <#bfe3ff><message>");
                event.renderer((player, displayName, message, viewer) -> text.parse(teamFormat,
                        Placeholder.component("team", team),
                        Placeholder.component("name", name),
                        Placeholder.component("message", mention(message, viewer, source))));
                pingMentions(source, plain, event.viewers());
                return;
            }
        }

        event.renderer((player, displayName, message, viewer) -> text.parse(chatFormat,
                Placeholder.component("badge", badge),
                Placeholder.component("grade", grade),
                Placeholder.component("team", team),
                Placeholder.component("title", title),
                Placeholder.component("name", name),
                Placeholder.component("message", mention(message, viewer, source).colorIfAbsent(msgColor == null ? Text.GRAY : msgColor))));
        pingMentions(source, plain, event.viewers());
    }

    // ───────────────────────────── Mentions et [objet] ─────────────────────────────

    private static final java.util.regex.Pattern ITEM_TAG = java.util.regex.Pattern.compile("(?i)\\[(objet|item|main)\\]");

    private Component withItem(Player source, Component message, String plain) {
        if (!plugin.getConfig().getBoolean("chat.item-tag", true) || !ITEM_TAG.matcher(plain).find()) return message;
        org.bukkit.inventory.ItemStack hand;
        try {
            hand = source.getInventory().getItemInMainHand().clone();
        } catch (RuntimeException e) {
            return message;
        }
        if (hand.getType().isAir()) return message;
        Component item = (hand.getAmount() > 1 ? Component.text(hand.getAmount() + "× ", Text.WHITE) : Component.empty())
                .append(hand.displayName());
        return message.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder().match(ITEM_TAG).times(3)
                .replacement(item).build());
    }

    private static java.util.regex.Pattern namePattern(String name) {
        return java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9_])@?" + java.util.regex.Pattern.quote(name) + "(?![A-Za-z0-9_])");
    }

    /** Le pseudo du lecteur est mis en valeur dans les messages qui le mentionnent. */
    private Component mention(Component message, net.kyori.adventure.audience.Audience viewer, Player source) {
        if (!(viewer instanceof Player vp) || vp.equals(source) || !plugin.getConfig().getBoolean("chat.mentions", true)) return message;
        TextColor accent = plugin.text().accent();
        return message.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder().match(namePattern(vp.getName()))
                .replacement((m, b) -> b.color(accent).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)).build());
    }

    /** Petit son pour les joueurs mentionnés (sur le thread principal). */
    private void pingMentions(Player source, String plain, java.util.Set<net.kyori.adventure.audience.Audience> viewers) {
        if (!plugin.getConfig().getBoolean("chat.mentions", true)) return;
        java.util.List<UUID> pinged = new java.util.ArrayList<>();
        for (net.kyori.adventure.audience.Audience a : viewers) {
            if (a instanceof Player vp && !vp.equals(source) && namePattern(vp.getName()).matcher(plain).find()) pinged.add(vp.getUniqueId());
        }
        if (pinged.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID u : pinged) {
                Player vp = Bukkit.getPlayer(u);
                if (vp == null) continue;
                vp.playSound(vp.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.6f);
                vp.sendActionBar(plugin.text().parse("<accent>✦</accent> <white>" + source.getName() + "</white> <gray>t'a mentionné dans le chat"));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) refresh(p);
        }, 5L);
        TagResolver r = Placeholder.unparsed("player", p.getName());
        event.joinMessage(text.parse(plugin.getConfig().getString("chat.join-message", ""), r));
        if (plugin.getConfig().getBoolean("chat.welcome.enabled", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                TagResolver wr = text.resolver(registry, stats, stats.get(p.getUniqueId()), p);
                for (String line : plugin.getConfig().getStringList("chat.welcome.lines")) {
                    p.sendMessage(text.parse(line, wr));
                }
            }, 10L);
        }
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
