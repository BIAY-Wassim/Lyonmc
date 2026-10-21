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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * Scoreboard personnel de chaque joueur :
 *  - sidebar (sans les nombres à droite)
 *  - tablist : header/footer, nom, score, tri par rang
 *  - nametag : rang devant le pseudo + ligne d'infos sous le pseudo (vie, succès)
 */
public final class BoardManager {

    private static final int MAX_LINES = 15;
    private static final String TEAM_PREFIX = "lmc_s";

    private final fr.lyonmc.achievements.LyonAchievements plugin;
    private final AdvancementRegistry registry;
    private final StatsService stats;
    private final Text text;

    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private List<UUID> lastOrder = List.of();
    private int orderVersion = 0;

    public BoardManager(fr.lyonmc.achievements.LyonAchievements plugin, AdvancementRegistry registry, StatsService stats, Text text) {
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
        final Map<UUID, Component> lastPrefix = new HashMap<>();
        final Map<UUID, Component> lastSuffix = new HashMap<>();
        final Map<UUID, NamedTextColor> lastColor = new HashMap<>();
        final Map<UUID, Component> lastBelow = new HashMap<>();
        Component lastHeader, lastFooter;
    }

    /** Ce que les autres voient d'un joueur (calculé une fois par tick). */
    private record Tag(Component prefix, Component suffix, NamedTextColor color, Component below, int score) {
    }

    // ───────────────────────────── Cycle de vie ─────────────────────────────

    public void create(Player player) {
        remove(player);
        PlayerBoard b = new PlayerBoard();
        b.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();

        if (plugin.getConfig().getBoolean("sidebar.enabled", true)) {
            b.sidebar = b.scoreboard.registerNewObjective("lmc_side", Criteria.DUMMY,
                    text.parse(plugin.getConfig().getString("sidebar.title", "LYONMC")));
            b.sidebar.numberFormat(NumberFormat.blank());
            for (int i = 0; i < MAX_LINES; i++) {
                b.entries[i] = "§" + Integer.toHexString(i) + "§r";
                b.scoreboard.registerNewTeam("lmc_line" + i).addEntry(b.entries[i]);
            }
            PlayerStats s = stats.get(player.getUniqueId());
            if (s == null || s.sidebar()) b.sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        if (plugin.getConfig().getBoolean("tablist.enabled", true) && plugin.getConfig().getBoolean("tablist.show-score", true)) {
            b.tab = b.scoreboard.registerNewObjective("lmc_tab", Criteria.DUMMY, Component.text("Succès"));
            TextColor c = TextColor.fromHexString(plugin.getConfig().getString("tablist.score-color", "#8a8a8a"));
            b.tab.numberFormat(NumberFormat.styled(Style.style(c == null ? NamedTextColor.GRAY : c)));
            b.tab.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        }

        if (plugin.getConfig().getBoolean("nametag.enabled", true)) {
            b.below = b.scoreboard.registerNewObjective("lmc_below", Criteria.DUMMY, Component.empty());
            b.below.setDisplaySlot(DisplaySlot.BELOW_NAME);
        } else if (plugin.getConfig().getBoolean("below-name.enabled", false)) {
            b.below = b.scoreboard.registerNewObjective("lmc_below", Criteria.DUMMY, text.parse("<gray>succès"));
            b.below.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }

        boards.put(player.getUniqueId(), b);
        player.setScoreboard(b.scoreboard);
        orderVersion++;
    }

    public void remove(Player player) {
        PlayerBoard b = boards.remove(player.getUniqueId());
        if (b != null && player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        for (PlayerBoard other : boards.values()) {
            other.scoreboard.resetScores(player.getName());
            Team t = other.scoreboard.getEntryTeam(player.getName());
            if (t != null && t.getName().startsWith(TEAM_PREFIX)) t.removeEntry(player.getName());
            other.lastPrefix.remove(player.getUniqueId());
            other.lastSuffix.remove(player.getUniqueId());
            other.lastColor.remove(player.getUniqueId());
            other.lastBelow.remove(player.getUniqueId());
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

    public void markOrderDirty() {
        orderVersion++;
    }

    // ───────────────────────────── Rafraîchissement ─────────────────────────────

    public void tick() {
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

        // Nametags calculés une seule fois par joueur
        Map<UUID, Tag> tags = new HashMap<>();
        for (Player p : online) tags.put(p.getUniqueId(), buildTag(p));

        for (Player p : online) {
            PlayerBoard b = boards.get(p.getUniqueId());
            if (b == null) continue;
            updatePersonal(p, b);
            updateShared(b, online, tags);
        }
    }

    private Tag buildTag(Player p) {
        PlayerStats s = stats.get(p.getUniqueId());
        int rank = stats.rank(p.getUniqueId());
        int count = s == null ? 0 : s.count();
        if (!plugin.getConfig().getBoolean("nametag.enabled", true)) {
            return new Tag(Component.empty(), Component.empty(), NamedTextColor.WHITE, null, count);
        }
        AttributeInstance maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr == null ? 20.0 : maxAttr.getValue();
        TagResolver r = TagResolver.resolver(
                text.resolver(registry, stats, s, p),
                Placeholder.unparsed("health", String.valueOf((int) Math.ceil(p.getHealth()))),
                Placeholder.unparsed("max_health", String.valueOf((int) Math.round(max))));
        // Au hub, les joueurs se montrent : grade, titre et une autre ligne sous le pseudo
        boolean hub = plugin.spawnHub() != null && plugin.spawnHub().inHub(p.getLocation());
        Component prefix = text.parse(plugin.getConfig().getString("nametag.prefix." + Text.rankKey(rank), ""), r);
        if (plugin.getConfig().getBoolean("nametag.grade", true)) prefix = prefix.append(plugin.grades().chatTag(p.getUniqueId()));
        Component title = plugin.rewards().titleRaw(plugin.profiles().get(p.getUniqueId()));
        Component suffix = hub && plugin.getConfig().getBoolean("nametag.hub-title", true) && !title.equals(Component.empty())
                ? Component.text(" [", Text.DARK).append(title).append(Component.text("]", Text.DARK)) : Component.empty();
        String belowFormat = hub ? plugin.getConfig().getString("nametag.hub-below",
                "<accent>★</accent> <white><done></white><dark_gray>/<total></dark_gray>  <dark_gray>·</dark_gray>  <accent>⛁</accent> <white><coins>")
                : plugin.getConfig().getString("nametag.below", "");
        Component below = text.parse(belowFormat, r);
        return new Tag(prefix, suffix, plugin.rewards().nameColor(p.getUniqueId()), below, count);
    }

    private void updatePersonal(Player player, PlayerBoard b) {
        PlayerStats s = stats.get(player.getUniqueId());
        TagResolver resolver = text.resolver(registry, stats, s, player);

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
                    setIfChanged(b.sidebar.getScore(b.entries[i]), n - i);
                } else if (b.lines[i] != null) {
                    b.scoreboard.resetScores(b.entries[i]);
                    b.lines[i] = null;
                }
            }
        }

        if (plugin.getConfig().getBoolean("tablist.enabled", true)) {
            Component header = text.lines(plugin.getConfig().getStringList("tablist.header"), resolver);
            Component footer = text.lines(plugin.getConfig().getStringList("tablist.footer"), resolver);
            if (!header.equals(b.lastHeader) || !footer.equals(b.lastFooter)) {
                player.sendPlayerListHeaderAndFooter(header, footer);
                b.lastHeader = header;
                b.lastFooter = footer;
            }
            int rank = stats.rank(player.getUniqueId());
            Component listName = text.parse(
                    plugin.getConfig().getString("tablist.name-format." + Text.rankKey(rank), "<player>"),
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.component("name", plugin.rewards().coloredName(player.getUniqueId(), player.getName(), Text.WHITE)),
                    Placeholder.component("team", plugin.teams().tagComponent(plugin.teams().teamOf(player.getUniqueId()))),
                    Placeholder.unparsed("rank", String.valueOf(rank)),
                    Placeholder.unparsed("done", String.valueOf(s == null ? 0 : s.count())));
            if (!listName.equals(player.playerListName())) player.playerListName(listName);
        }
    }

    /** Ce que ce spectateur voit des autres : score tablist, nametag, ordre. */
    private void updateShared(PlayerBoard b, List<Player> online, Map<UUID, Tag> tags) {
        // Équipes : une par joueur, dans l'ordre du classement (tri de la tablist + préfixe du nametag)
        if (b.sortedVersion != orderVersion) {
            for (Team t : new ArrayList<>(b.scoreboard.getTeams())) {
                if (t.getName().startsWith(TEAM_PREFIX)) t.unregister();
            }
            boolean sort = plugin.getConfig().getBoolean("tablist.sort-by-rank", true);
            int i = 0;
            for (Player target : online) {
                String name = TEAM_PREFIX + (sort ? String.format("%04d", i++) : target.getUniqueId().toString().substring(0, 8));
                Team t = b.scoreboard.registerNewTeam(name);
                t.color(NamedTextColor.WHITE);
                t.addEntry(target.getName());
            }
            b.lastPrefix.clear();
            b.lastSuffix.clear();
            b.lastColor.clear();
            b.sortedVersion = orderVersion;
        }

        for (Player target : online) {
            Tag tag = tags.get(target.getUniqueId());
            if (tag == null) continue;
            UUID id = target.getUniqueId();

            if (b.tab != null) setIfChanged(b.tab.getScore(target.getName()), tag.score());

            if (!tag.prefix().equals(b.lastPrefix.get(id))) {
                Team t = b.scoreboard.getEntryTeam(target.getName());
                if (t != null) t.prefix(tag.prefix());
                b.lastPrefix.put(id, tag.prefix());
            }
            if (!tag.suffix().equals(b.lastSuffix.get(id))) {
                Team t = b.scoreboard.getEntryTeam(target.getName());
                if (t != null) t.suffix(tag.suffix());
                b.lastSuffix.put(id, tag.suffix());
            }
            if (tag.color() != b.lastColor.get(id)) {
                Team t = b.scoreboard.getEntryTeam(target.getName());
                if (t != null) t.color(tag.color());
                b.lastColor.put(id, tag.color());
            }

            if (b.below != null) {
                Score score = b.below.getScore(target.getName());
                setIfChanged(score, tag.score());
                if (tag.below() != null && !tag.below().equals(b.lastBelow.get(id))) {
                    score.numberFormat(NumberFormat.fixed(tag.below()));
                    b.lastBelow.put(id, tag.below());
                }
            }
        }
    }

    private static void setIfChanged(Score score, int value) {
        if (!score.isScoreSet() || score.getScore() != value) score.setScore(value);
    }
}
