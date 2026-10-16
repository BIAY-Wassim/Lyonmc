package fr.lyonmc.achievements.util;

import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.data.StatsService;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class Text {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private int barLength;
    private String barChar;
    private TextColor barFilled;
    private TextColor barEmpty;

    public Text(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        barLength = Math.max(1, plugin.getConfig().getInt("progress-bar.length", 10));
        barChar = plugin.getConfig().getString("progress-bar.char", "■");
        TextColor f = TextColor.fromHexString(plugin.getConfig().getString("progress-bar.filled-color", "#4ade80"));
        TextColor e = TextColor.fromHexString(plugin.getConfig().getString("progress-bar.empty-color", "#3f3f46"));
        barFilled = f == null ? NamedTextColor.GREEN : f;
        barEmpty = e == null ? NamedTextColor.DARK_GRAY : e;
    }

    // ───────────────────────────── Messages ─────────────────────────────

    public Component parse(String mini, TagResolver... resolvers) {
        return MM.deserialize(mini == null ? "" : mini, resolvers);
    }

    public Component msg(String path, TagResolver... resolvers) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String raw = plugin.getConfig().getString("messages." + path, path);
        return parse(prefix + raw, resolvers);
    }

    public void send(CommandSender to, String path, TagResolver... resolvers) {
        to.sendMessage(msg(path, resolvers));
    }

    // ───────────────────────────── Composants ─────────────────────────────

    public Component bar(int done, int total) {
        return bar(done, total, barLength);
    }

    public Component bar(int done, int total, int length) {
        int filled = total <= 0 ? 0 : (int) Math.round(length * (double) done / total);
        filled = Math.max(0, Math.min(length, filled));
        return Component.text()
                .append(Component.text(barChar.repeat(filled), barFilled))
                .append(Component.text(barChar.repeat(length - filled), barEmpty))
                .build();
    }

    public static String percent(int done, int total) {
        if (total <= 0) return "0";
        double p = done * 100.0 / total;
        if (p >= 100) return "100";
        return String.format(Locale.US, "%.1f", p);
    }

    /** Titre du succès coloré par type, survolable (description, type, catégorie, rareté). */
    public Component advancement(AdvancementRegistry registry, StatsService stats, AdvancementRegistry.Entry e) {
        if (e == null) return Component.text("Aucun", NamedTextColor.GRAY);
        TextColor color = AdvancementRegistry.frameColor(e.frame());
        AdvancementRegistry.Category cat = registry.category(e.category());
        Component hover = Component.text()
                .append(e.title().colorIfAbsent(color).decoration(TextDecoration.BOLD, true))
                .append(Component.newline())
                .append(e.description().colorIfAbsent(NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("Type ", NamedTextColor.DARK_GRAY))
                .append(Component.text(AdvancementRegistry.frameName(e.frame()), color))
                .append(Component.newline())
                .append(Component.text("Catégorie ", NamedTextColor.DARK_GRAY))
                .append(cat.displayName())
                .append(Component.newline())
                .append(Component.text("Obtenu par ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format(Locale.US, "%.1f%%", stats.rarity(e.key())), NamedTextColor.YELLOW))
                .append(Component.text(" des joueurs", NamedTextColor.DARK_GRAY))
                .build();
        return Component.text()
                .append(Component.text("[", color))
                .append(e.title().colorIfAbsent(color))
                .append(Component.text("]", color))
                .hoverEvent(HoverEvent.showText(hover))
                .build();
    }

    public static String timeAgo(long time) {
        if (time <= 0) return "?";
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return "à l'instant";
        if (s < 3600) return "il y a " + (s / 60) + " min";
        if (s < 86400) return "il y a " + (s / 3600) + " h";
        if (s < 86400L * 30) return "il y a " + (s / 86400) + " j";
        if (s < 86400L * 365) return "il y a " + (s / (86400L * 30)) + " mois";
        return "il y a " + (s / (86400L * 365)) + " an(s)";
    }

    public static String duration(long seconds) {
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60;
        if (d > 0) return d + "j " + h + "h";
        if (h > 0) return h + "h " + m + "min";
        return m + "min";
    }

    // ───────────────────────────── Placeholders ─────────────────────────────

    /** Tous les placeholders du config pour un joueur donné. */
    public TagResolver resolver(AdvancementRegistry registry, StatsService stats, PlayerStats s, Player viewer) {
        TagResolver.Builder b = TagResolver.builder();
        int done = s == null ? 0 : s.count();
        int total = registry.total();
        int rank = s == null ? 0 : stats.rank(s.uuid());

        b.resolver(Placeholder.unparsed("player", s != null && s.name() != null ? s.name() : viewer != null ? viewer.getName() : "?"));
        b.resolver(Placeholder.unparsed("done", String.valueOf(done)));
        b.resolver(Placeholder.unparsed("total", String.valueOf(total)));
        b.resolver(Placeholder.unparsed("percent", percent(done, total)));
        b.resolver(Placeholder.component("bar", bar(done, total)));
        b.resolver(Placeholder.unparsed("rank", rank == 0 ? "-" : String.valueOf(rank)));
        b.resolver(Placeholder.unparsed("ranked", String.valueOf(stats.ranking().size())));

        b.resolver(Placeholder.unparsed("challenges", String.valueOf(s == null ? 0 : s.count(AdvancementDisplay.Frame.CHALLENGE))));
        b.resolver(Placeholder.unparsed("challenges_total", String.valueOf(registry.total(AdvancementDisplay.Frame.CHALLENGE))));
        b.resolver(Placeholder.unparsed("goals", String.valueOf(s == null ? 0 : s.count(AdvancementDisplay.Frame.GOAL))));
        b.resolver(Placeholder.unparsed("goals_total", String.valueOf(registry.total(AdvancementDisplay.Frame.GOAL))));
        b.resolver(Placeholder.unparsed("tasks", String.valueOf(s == null ? 0 : s.count(AdvancementDisplay.Frame.TASK))));
        b.resolver(Placeholder.unparsed("tasks_total", String.valueOf(registry.total(AdvancementDisplay.Frame.TASK))));

        for (AdvancementRegistry.Category c : registry.categories()) {
            String id = c.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
            b.resolver(Placeholder.component("cat_" + id + "_name", c.displayName()));
            b.resolver(Placeholder.unparsed("cat_" + id + "_done", String.valueOf(s == null ? 0 : s.count(c.id()))));
            b.resolver(Placeholder.unparsed("cat_" + id + "_total", String.valueOf(registry.total(c.id()))));
        }

        AdvancementRegistry.Entry last = s == null || s.lastKey() == null ? null : registry.get(s.lastKey());
        b.resolver(Placeholder.component("last", last == null ? Component.text("Aucun", NamedTextColor.GRAY)
                : last.title().colorIfAbsent(AdvancementRegistry.frameColor(last.frame()))));
        b.resolver(Placeholder.unparsed("last_ago", s == null ? "-" : timeAgo(s.lastTime())));

        for (int i = 1; i <= 5; i++) {
            PlayerStats t = stats.top(i);
            b.resolver(Placeholder.unparsed("top" + i + "_name", t == null ? "---" : t.name()));
            b.resolver(Placeholder.unparsed("top" + i + "_done", t == null ? "0" : String.valueOf(t.count())));
        }

        b.resolver(Placeholder.unparsed("online", String.valueOf(Bukkit.getOnlinePlayers().size())));
        b.resolver(Placeholder.unparsed("max_online", String.valueOf(Bukkit.getMaxPlayers())));
        b.resolver(Placeholder.unparsed("ping", viewer == null ? "0" : String.valueOf(viewer.getPing())));
        double tps = Bukkit.getTPS()[0];
        b.resolver(Placeholder.unparsed("tps", String.format(Locale.US, "%.1f", Math.min(20.0, tps))));
        return b.build();
    }
}
