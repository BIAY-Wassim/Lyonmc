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

import java.util.List;
import java.util.Locale;

public final class Text {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    /** Palette sobre */
    public static final TextColor WHITE = TextColor.color(0xE6E6E6);
    public static final TextColor GRAY = TextColor.color(0x9A9A9A);
    public static final TextColor DARK = TextColor.color(0x5A5A5A);

    private final JavaPlugin plugin;
    private TextColor accent;
    private TagResolver theme;
    private int barLength;
    private String barChar;
    private TextColor barFilled;
    private TextColor barEmpty;

    public Text(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        TextColor a = TextColor.fromHexString(plugin.getConfig().getString("theme.accent", "#d9c08c"));
        accent = a == null ? TextColor.color(0xD9C08C) : a;
        theme = Placeholder.styling("accent", accent);
        try {
            zone = java.time.ZoneId.of(plugin.getConfig().getString("settings.timezone", "Europe/Paris"));
        } catch (Exception e) {
            zone = java.time.ZoneId.of("Europe/Paris");
        }

        barLength = Math.max(1, plugin.getConfig().getInt("progress-bar.length", 20));
        barChar = plugin.getConfig().getString("progress-bar.char", "|");
        TextColor f = TextColor.fromHexString(plugin.getConfig().getString("progress-bar.filled-color", "#d9c08c"));
        TextColor e = TextColor.fromHexString(plugin.getConfig().getString("progress-bar.empty-color", "#3a3a3a"));
        barFilled = f == null ? accent : f;
        barEmpty = e == null ? NamedTextColor.DARK_GRAY : e;
    }

    public TextColor accent() {
        return accent;
    }

    // ───────────────────────────── Messages ─────────────────────────────

    public Component parse(String mini, TagResolver... resolvers) {
        return MM.deserialize(mini == null ? "" : mini, TagResolver.resolver(TagResolver.resolver(resolvers), theme));
    }

    /** Plusieurs lignes séparées par des retours à la ligne. */
    public Component lines(List<String> lines, TagResolver... resolvers) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(parse(lines.get(i), resolvers));
        }
        return out;
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
        int filled = total <= 0 ? 0 : (int) Math.floor(length * (double) done / total);
        if (done > 0 && filled == 0) filled = 1;
        filled = Math.max(0, Math.min(length, filled));
        return Component.text()
                .append(Component.text(barChar.repeat(filled), barFilled))
                .append(Component.text(barChar.repeat(length - filled), barEmpty))
                .build()
                .decoration(TextDecoration.ITALIC, false);
    }

    public static String percent(int done, int total) {
        if (total <= 0) return "0";
        double p = done * 100.0 / total;
        if (p >= 100) return "100";
        return String.format(Locale.FRANCE, "%.1f", p);
    }

    /** Titre du succès, survolable (description, type, catégorie, rareté). */
    public Component advancement(AdvancementRegistry registry, StatsService stats, AdvancementRegistry.Entry e) {
        if (e == null) return Component.text("aucun", GRAY);
        Component titleColor = e.title().colorIfAbsent(e.frame() == AdvancementDisplay.Frame.CHALLENGE ? accent : WHITE);
        AdvancementRegistry.Category cat = registry.category(e.category());
        Component hover = Component.text()
                .append(titleColor)
                .append(Component.newline())
                .append(e.description().colorIfAbsent(GRAY))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text(AdvancementRegistry.frameName(e.frame()) + " · " + cat.name(), DARK))
                .append(Component.newline())
                .append(Component.text("Obtenu par " + String.format(Locale.FRANCE, "%.1f", stats.rarity(e.key()))
                        + " % des joueurs", DARK))
                .build();
        return titleColor.hoverEvent(HoverEvent.showText(hover));
    }

    public static String timeAgo(long time) {
        if (time <= 0) return "";
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
        if (d > 0) return d + " j " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        return m + " min";
    }

    // ───────────────────────────── Placeholders ─────────────────────────────

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
        b.resolver(Placeholder.component("last", last == null ? Component.text("aucun", GRAY) : last.title()));
        b.resolver(Placeholder.unparsed("last_ago", s == null ? "" : timeAgo(s.lastTime())));

        for (int i = 1; i <= 5; i++) {
            PlayerStats t = stats.top(i);
            b.resolver(Placeholder.unparsed("top" + i + "_name", t == null ? "-" : t.name()));
            b.resolver(Placeholder.unparsed("top" + i + "_done", t == null ? "0" : String.valueOf(t.count())));
        }

        // Joueur connecté : session, stats, ping
        Player online = s != null ? Bukkit.getPlayer(s.uuid()) : viewer;
        int ping = online == null ? 0 : online.getPing();
        b.resolver(Placeholder.unparsed("ping", String.valueOf(ping)));
        b.resolver(Placeholder.component("ping_bars", pingBars(ping)));
        b.resolver(Placeholder.unparsed("session", online == null ? "-" : duration(stats.sessionSeconds(online.getUniqueId()))));
        b.resolver(Placeholder.unparsed("world", online == null ? "-" : worldName(online.getWorld().getEnvironment())));
        int mobKills = stat(online, org.bukkit.Statistic.MOB_KILLS);
        int playerKills = stat(online, org.bukkit.Statistic.PLAYER_KILLS);
        b.resolver(Placeholder.unparsed("mob_kills", String.valueOf(mobKills)));
        b.resolver(Placeholder.unparsed("player_kills", String.valueOf(playerKills)));
        b.resolver(Placeholder.unparsed("kills", String.valueOf(mobKills + playerKills)));
        b.resolver(Placeholder.unparsed("deaths", String.valueOf(stat(online, org.bukkit.Statistic.DEATHS))));
        b.resolver(Placeholder.unparsed("playtime", online == null ? "-" : duration(stat(online, org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20L)));

        // Serveur
        b.resolver(Placeholder.unparsed("online", String.valueOf(Bukkit.getOnlinePlayers().size())));
        b.resolver(Placeholder.unparsed("max_online", String.valueOf(Bukkit.getMaxPlayers())));
        double tps = Bukkit.getTPS()[0];
        b.resolver(Placeholder.unparsed("tps", String.format(Locale.FRANCE, "%.1f", Math.min(20.0, tps))));
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
        b.resolver(Placeholder.unparsed("time", now.format(TIME)));
        b.resolver(Placeholder.unparsed("date", capitalize(now.format(DATE))));
        b.resolver(Placeholder.unparsed("server_total", String.format(Locale.FRANCE, "%,d", stats.serverTotal()).replace('\u202f', ' ').replace('\u00a0', ' ')));
        PlayerStats lg = stats.lastGlobal();
        AdvancementRegistry.Entry lgAdv = lg == null || lg.lastKey() == null ? null : registry.get(lg.lastKey());
        b.resolver(Placeholder.unparsed("last_global_player", lg == null ? "personne" : lg.name()));
        b.resolver(Placeholder.component("last_global_adv", lgAdv == null ? Component.text("-", GRAY) : lgAdv.title().colorIfAbsent(accent)));
        b.resolver(Placeholder.unparsed("last_global_ago", lg == null ? "" : timeAgo(lg.lastTime())));
        return b.build();
    }

    private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
    private static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRANCE);
    private java.time.ZoneId zone = java.time.ZoneId.of("Europe/Paris");

    /** Indicateur de ping en 4 barres colorées. */
    public static Component pingBars(int ping) {
        int bars;
        TextColor color;
        if (ping <= 0) { bars = 0; color = DARK; }
        else if (ping < 60) { bars = 4; color = TextColor.color(0x7BD88F); }
        else if (ping < 120) { bars = 3; color = TextColor.color(0xC6D86B); }
        else if (ping < 250) { bars = 2; color = TextColor.color(0xE8B45A); }
        else { bars = 1; color = TextColor.color(0xE06C6C); }
        return Component.text()
                .append(Component.text("▮".repeat(bars), color))
                .append(Component.text("▮".repeat(4 - bars), TextColor.color(0x3A3A3A)))
                .build();
    }

    private static int stat(Player p, org.bukkit.Statistic st) {
        if (p == null) return 0;
        try {
            return p.getStatistic(st);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String worldName(org.bukkit.World.Environment env) {
        return switch (env) {
            case NETHER -> "Nether";
            case THE_END -> "The End";
            default -> "Overworld";
        };
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Clé de format selon le rang : top1, top2, top3, other, unranked */
    public static String rankKey(int rank) {
        return switch (rank) {
            case 0 -> "unranked";
            case 1 -> "top1";
            case 2 -> "top2";
            case 3 -> "top3";
            default -> "other";
        };
    }
}
