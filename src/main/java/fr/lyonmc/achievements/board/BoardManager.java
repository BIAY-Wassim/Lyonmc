package fr.lyonmc.achievements.board;

import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.util.Text;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * Gère, pour chaque joueur, un scoreboard personnel :
 *  - sidebar (lignes du config, sans les nombres rouges)
 *  - nombre de succès dans la tablist + tri par rang
 *  - nombre de succès sous le pseudo
 * ainsi que le header/footer et le nom de la tablist.
 */
public final class BoardManager {

    private static final int MAX_LINES = 15;
    private static final String SORT_TEAM_PREFIX = "lmc_s";

    private final JavaPlugin plugin;
    private final AdvancementRegistry registry;
    private final StatsService stats;
    private final Text text;

    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private List<UUID> lastOrder = List.of();
    private int orderVersion = 0;

    public BoardManager(JavaPlugin plugin, AdvancementRegistry registry, StatsService stats, Text text) {
        this.plugin = plugin;
        this.registry = registry;
        this.stats = stats;
        this.text = text;
    }

    private static final class PlayerBoard {
        Scoreboard scoreboard;
        Objective sidebar;
        Objective tab;
        Objective below;
        final Component[] lines = new Component[MAX_LINES];
        final String[] entries = new String[MAX_LINES];
        int sortedVersion = -1;
        Component lastHeader, lastFooter, lastListName;
    }

    // ───────────────────────────── Cycle de vie ─────────────────────────────

    public void create(Player player) {
        remove(player);
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        PlayerBoard b = new PlayerBoard();
        b.scoreboard = manager.getNewScoreboard();

        if (plugin.getConfig().getBoolean("sidebar.enabled", true)) {
            b.sidebar = b.scoreboard.registerNewObjective("lmc_side", Criteria.DUMMY,
                    text.parse(plugin.getConfig().getString("sidebar.title", "LYONMC.FR")));
            b.sidebar.numberFormat(NumberFormat.blank());
            for (int i = 0; i < MAX_LINES; i++) {
                // entrée invisible unique : §0§r, §1§r ...
                b.entries[i] = "§" + Integer.toHexString(i) + "§r";
                Team t = b.scoreboard.registerNewTeam("lmc_line" + i);
                t.addEntry(b.entries[i]);
            }
            PlayerStats s = stats.get(player.getUniqueId());
            if (s == null || s.sidebar()) b.sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        if (plugin.getConfig().getBoolean("tablist.enabled", true) && plugin.getConfig().getBoolean("tablist.show-score", true)) {
            b.tab = b.scoreboard.registerNewObjective("lmc_tab", Criteria.DUMMY, Component.text("Succès"));
            TextColor c = TextColor.fromHexString(plugin.getConfig().getString("tablist.score-color", "#ffd700"));
            b.tab.numberFormat(NumberFormat.styled(Style.style(c == null ? NamedTextColor.GOLD : c)));
            b.tab.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        }

        if (plugin.getConfig().getBoolean("below-name.enabled", true)) {
            b.below = b.scoreboard.registerNewObjective("lmc_below", Criteria.DUMMY,
                    text.parse(plugin.getConfig().getString("below-name.title", "<gold>✦ succès")));
            b.below.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }

        boards.put(player.getUniqueId(), b);
        player.setScoreboard(b.scoreboard);
        orderVersion++;
        update(player, b);
    }

    public void remove(Player player) {
        PlayerBoard b = boards.remove(player.getUniqueId());
        if (b != null && player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        // retire le joueur des scoreboards des autres
        for (PlayerBoard other : boards.values()) {
            other.scoreboard.resetScores(player.getName());
            Team t = other.scoreboard.getEntryTeam(player.getName());
            if (t != null && t.getName().startsWith(SORT_TEAM_PREFIX)) t.removeEntry(player.getName());
        }
        orderVersion++;
    }

    public void removeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (boards.containsKey(p.getUniqueId())) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                p.playerListName(null);
                p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            }
        }
        boards.clear();
    }

    public void recreateAll() {
        removeAll();
        for (Player p : Bukkit.getOnlinePlayers()) create(p);
    }

    public void setSidebarVisible(Player player, boolean visible) {
        PlayerBoard b = boards.get(player.getUniqueId());
        if (b == null || b.sidebar == null) return;
        b.sidebar.setDisplaySlot(visible ? DisplaySlot.SIDEBAR : null);
    }

    /** À appeler quand le classement a pu changer. */
    public void markOrderDirty() {
        orderVersion++;
    }

    // ───────────────────────────── Rafraîchissement ─────────────────────────────

    public void tick() {
        // Ordre des joueurs en ligne selon le classement
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparingInt((Player p) -> {
            int r = stats.rank(p.getUniqueId());
            return r == 0 ? Integer.MAX_VALUE : r;
        }).thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        List<UUID> order = new ArrayList<>(online.size());
        for (Player p : online) order.add(p.getUniqueId());
        if (!order.equals(lastOrder)) {
            lastOrder = order;
            orderVersion++;
        }

        for (Player p : online) {
            PlayerBoard b = boards.get(p.getUniqueId());
            if (b == null) continue;
            update(p, b);
            updateShared(b, online);
        }
    }

    private void update(Player player, PlayerBoard b) {
        PlayerStats s = stats.get(player.getUniqueId());
        TagResolver resolver = text.resolver(registry, stats, s, player);

        // Sidebar
        if (b.sidebar != null) {
            List<String> raw = plugin.getConfig().getStringList("sidebar.lines");
            int n = Math.min(MAX_LINES, raw.size());
            for (int i = 0; i < MAX_LINES; i++) {
                if (i < n) {
                    Component line = text.parse(raw.get(i), resolver);
                    if (!line.equals(b.lines[i])) {
                        Team t = b.scoreboard.getTeam("lmc_line" + i);
                        if (t != null) t.prefix(line);
                        b.lines[i] = line;
                    }
                    Score score = b.sidebar.getScore(b.entries[i]);
                    int value = n - i;
                    if (!score.isScoreSet() || score.getScore() != value) score.setScore(value);
                } else if (b.lines[i] != null) {
                    b.scoreboard.resetScores(b.entries[i]);
                    b.lines[i] = null;
                }
            }
        }

        // Tablist header / footer + nom
        if (plugin.getConfig().getBoolean("tablist.enabled", true)) {
            Component header = joinLines(plugin.getConfig().getStringList("tablist.header"), resolver);
            Component footer = joinLines(plugin.getConfig().getStringList("tablist.footer"), resolver);
            if (!header.equals(b.lastHeader) || !footer.equals(b.lastFooter)) {
                player.sendPlayerListHeaderAndFooter(header, footer);
                b.lastHeader = header;
                b.lastFooter = footer;
            }

            int rank = s == null ? 0 : stats.rank(player.getUniqueId());
            String key = switch (rank) {
                case 0 -> "unranked";
                case 1 -> "top1";
                case 2 -> "top2";
                case 3 -> "top3";
                default -> "other";
            };
            String fmt = plugin.getConfig().getString("tablist.name-format." + key, "<player>");
            Component listName = text.parse(fmt,
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("rank", String.valueOf(rank)),
                    Placeholder.unparsed("done", String.valueOf(s == null ? 0 : s.count())));
            if (!listName.equals(b.lastListName)) {
                player.playerListName(listName);
                b.lastListName = listName;
            }
        }
    }

    /** Scores tablist / sous le pseudo + tri de la tablist, dans le scoreboard d'un spectateur. */
    private void updateShared(PlayerBoard b, List<Player> online) {
        for (Player target : online) {
            PlayerStats ts = stats.get(target.getUniqueId());
            int value = ts == null ? 0 : ts.count();
            if (b.tab != null) setIfChanged(b.tab.getScore(target.getName()), value);
            if (b.below != null) setIfChanged(b.below.getScore(target.getName()), value);
        }

        if (b.sortedVersion != orderVersion && plugin.getConfig().getBoolean("tablist.sort-by-rank", true)) {
            for (Team t : new ArrayList<>(b.scoreboard.getTeams())) {
                if (t.getName().startsWith(SORT_TEAM_PREFIX)) t.unregister();
            }
            int i = 0;
            for (Player target : online) {
                Team t = b.scoreboard.registerNewTeam(SORT_TEAM_PREFIX + String.format("%04d", i++));
                t.addEntry(target.getName());
            }
            b.sortedVersion = orderVersion;
        }
    }

    private static void setIfChanged(Score score, int value) {
        if (!score.isScoreSet() || score.getScore() != value) score.setScore(value);
    }

    private Component joinLines(List<String> lines, TagResolver resolver) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(text.parse(lines.get(i), resolver));
        }
        return out;
    }
}
