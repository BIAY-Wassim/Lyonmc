package fr.lyonmc.achievements.command;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.board.BoardManager;
import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.menu.MissionsMenu;
import fr.lyonmc.achievements.util.Text;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;

public final class SuccesCommand implements TabExecutor {

    private static final int PAGE_SIZE = 10;
    private static final String RULE = "─────────────────────────────";

    private final LyonAchievements plugin;
    private final AdvancementRegistry registry;
    private final StatsService stats;
    private final BoardManager boards;
    private final MissionsMenu menu;
    private final Text text;

    public SuccesCommand(LyonAchievements plugin, AdvancementRegistry registry, StatsService stats,
                         BoardManager boards, MissionsMenu menu, Text text) {
        this.plugin = plugin;
        this.registry = registry;
        this.stats = stats;
        this.boards = boards;
        this.menu = menu;
        this.text = text;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /carte
        if (command.getName().equalsIgnoreCase("carte")) {
            sender.sendMessage(text.parse(plugin.getConfig().getString("map.message", "lyonmc.fr")));
            return true;
        }

        // /classement
        if (command.getName().equalsIgnoreCase("classement")) {
            if (sender instanceof Player p) menu.openTop(p);
            else showTop(sender, 1);
            return true;
        }

        // /missions [catégorie]
        if (command.getName().equalsIgnoreCase("missions")) {
            openMenu(sender, args.length > 0 ? args[0] : null);
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                text.send(sender, "players-only");
                return true;
            }
            showProfile(sender, stats.refreshOnline(p));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "top", "classement" -> {
                boolean textual = args.length > 1 && (args[1].equalsIgnoreCase("texte") || args[1].equalsIgnoreCase("chat"));
                if (sender instanceof Player p && !textual && args.length < 2) {
                    menu.openTop(p);
                } else {
                    int page = args.length > 2 ? parseInt(args[2], 1) : args.length > 1 ? parseInt(args[1], 1) : 1;
                    showTop(sender, page);
                }
            }
            case "menu", "missions", "manquants", "missing" -> openMenu(sender, args.length > 1 ? args[1] : null);
            case "sidebar", "scoreboard", "toggle" -> toggleSidebar(sender);
            case "aide", "help" -> showHelp(sender);
            case "reload" -> {
                if (!sender.hasPermission("lyonmc.succes.admin")) {
                    text.send(sender, "no-permission");
                    return true;
                }
                plugin.reloadEverything();
                text.send(sender, "reloaded");
            }
            case "import" -> {
                if (!sender.hasPermission("lyonmc.succes.admin")) {
                    text.send(sender, "no-permission");
                    return true;
                }
                text.send(sender, "import-started");
                stats.importFromWorld(count -> {
                    boards.markOrderDirty();
                    text.send(sender, "import-done", Placeholder.unparsed("count", String.valueOf(count)));
                });
            }
            default -> {
                if (!sender.hasPermission("lyonmc.succes.others")) {
                    text.send(sender, "no-permission");
                    return true;
                }
                Player online = Bukkit.getPlayerExact(args[0]);
                PlayerStats target = online != null ? stats.refreshOnline(online) : stats.findByName(args[0]);
                if (target == null) {
                    text.send(sender, "player-not-found");
                    return true;
                }
                showProfile(sender, target);
            }
        }
        return true;
    }

    private void openMenu(CommandSender sender, String category) {
        if (!(sender instanceof Player p)) {
            text.send(sender, "players-only");
            return;
        }
        if (category != null) {
            String id = category.toLowerCase(Locale.ROOT);
            for (AdvancementRegistry.Category c : registry.usedCategories()) {
                if (c.id().equals(id) || c.name().equalsIgnoreCase(category)) {
                    stats.refreshOnline(p);
                    menu.open(p, MissionsMenu.View.CATEGORY, c.id(), MissionsMenu.Filter.MISSING, 1);
                    return;
                }
            }
        }
        menu.open(p);
    }

    // ───────────────────────────── Profil ─────────────────────────────

    private void showProfile(CommandSender sender, PlayerStats s) {
        int total = registry.total();
        int rank = stats.rank(s.uuid());

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text()
                .append(Component.text(s.name(), Text.WHITE, TextDecoration.BOLD))
                .append(Component.text("   " + (rank == 0 ? "non classé" : "#" + rank + " sur " + stats.ranking().size()), Text.DARK))
                .build());
        sender.sendMessage(Component.text(RULE, Text.DARK));
        sender.sendMessage(Component.text()
                .append(label("Succès"))
                .append(Component.text(s.count(), Text.WHITE))
                .append(Component.text(" / " + total + "   ", Text.DARK))
                .append(text.bar(s.count(), total, 30))
                .append(Component.text("  " + Text.percent(s.count(), total) + " %", Text.GRAY))
                .build());

        TextComponent.Builder types = Component.text().append(label("Types"));
        boolean first = true;
        for (AdvancementDisplay.Frame f : List.of(AdvancementDisplay.Frame.TASK, AdvancementDisplay.Frame.GOAL, AdvancementDisplay.Frame.CHALLENGE)) {
            if (!first) types.append(Component.text("   ", Text.DARK));
            first = false;
            types.append(Component.text(AdvancementRegistry.frameName(f).toLowerCase(Locale.ROOT) + "s ", Text.GRAY))
                    .append(Component.text(s.count(f), Text.WHITE))
                    .append(Component.text("/" + registry.total(f), Text.DARK));
        }
        sender.sendMessage(types.build());
        sender.sendMessage(Component.empty());

        for (AdvancementRegistry.Category c : registry.usedCategories()) {
            int done = s.count(c.id());
            int tot = registry.total(c.id());
            Component line = Component.text()
                    .append(label(c.name()))
                    .append(text.bar(done, tot, 20))
                    .append(Component.text("  " + done, Text.WHITE))
                    .append(Component.text(" / " + tot, Text.DARK))
                    .build();
            if (sender instanceof Player p && p.getUniqueId().equals(s.uuid())) {
                line = line.clickEvent(ClickEvent.runCommand("/missions " + c.id()))
                        .hoverEvent(HoverEvent.showText(Component.text("Voir le détail", Text.GRAY)));
            }
            sender.sendMessage(line);
        }
        sender.sendMessage(Component.empty());

        AdvancementRegistry.Entry last = s.lastKey() == null ? null : registry.get(s.lastKey());
        sender.sendMessage(Component.text()
                .append(label("Dernier"))
                .append(text.advancement(registry, stats, last))
                .append(last == null ? Component.empty() : Component.text("  " + Text.timeAgo(s.lastTime()), Text.DARK))
                .build());

        AdvancementRegistry.Entry rarest = null;
        double rarestValue = Double.MAX_VALUE;
        for (String key : s.done().keySet()) {
            AdvancementRegistry.Entry e = registry.get(key);
            if (e == null) continue;
            double r = stats.rarity(key);
            if (r < rarestValue) {
                rarestValue = r;
                rarest = e;
            }
        }
        if (rarest != null) {
            sender.sendMessage(Component.text()
                    .append(label("Plus rare"))
                    .append(text.advancement(registry, stats, rarest))
                    .append(Component.text(String.format(Locale.FRANCE, "  %.1f %% des joueurs", rarestValue), Text.DARK))
                    .build());
        }

        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(s.uuid());
            long ticks = op.getStatistic(Statistic.PLAY_ONE_MINUTE);
            if (ticks > 0) {
                sender.sendMessage(Component.text()
                        .append(label("Temps de jeu"))
                        .append(Component.text(Text.duration(ticks / 20), Text.WHITE))
                        .append(s.count() > 0 ? Component.text("   un succès toutes les "
                                + Text.duration(ticks / 20 / s.count()), Text.DARK) : Component.empty())
                        .build());
            }
        } catch (Exception ignored) {
        }

        sender.sendMessage(Component.text(RULE, Text.DARK));
        if (sender instanceof Player p && p.getUniqueId().equals(s.uuid())) {
            sender.sendMessage(Component.text("Ouvrir le menu des missions", text.accent())
                    .clickEvent(ClickEvent.runCommand("/missions"))
                    .hoverEvent(HoverEvent.showText(Component.text("/missions", Text.GRAY))));
        }
    }

    private Component label(String s) {
        String padded = s + " ".repeat(Math.max(1, 14 - s.length()));
        return Component.text(padded, Text.GRAY);
    }

    // ───────────────────────────── Classement ─────────────────────────────

    private void showTop(CommandSender sender, int page) {
        List<PlayerStats> ranking = stats.ranking();
        int total = registry.total();
        int pages = Math.max(1, (ranking.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(pages, page));
        UUID self = sender instanceof Player p ? p.getUniqueId() : null;

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text()
                .append(Component.text("Classement des succès", Text.WHITE, TextDecoration.BOLD))
                .append(Component.text("   " + ranking.size() + " joueurs", Text.DARK))
                .build());
        sender.sendMessage(Component.text(RULE, Text.DARK));
        if (ranking.isEmpty()) {
            sender.sendMessage(Component.text("Personne n'est encore classé.", Text.GRAY));
        }

        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(ranking.size(), page * PAGE_SIZE); i++) {
            PlayerStats s = ranking.get(i);
            int pos = i + 1;
            boolean isSelf = s.uuid().equals(self);
            boolean online = Bukkit.getPlayer(s.uuid()) != null;
            TextColor posColor = pos == 1 ? text.accent() : pos <= 3 ? Text.WHITE : Text.DARK;
            sender.sendMessage(Component.text()
                    .append(Component.text(String.format("%3d   ", pos), posColor))
                    .append(Component.text(s.name(), isSelf ? text.accent() : online ? Text.WHITE : Text.GRAY))
                    .append(Component.text("   " + s.count(), Text.WHITE))
                    .append(Component.text(" / " + total + "   ", Text.DARK))
                    .append(text.bar(s.count(), total, 15))
                    .append(Component.text("   " + s.challenges() + " défis", Text.DARK))
                    .hoverEvent(HoverEvent.showText(Component.text("Voir le profil de " + s.name(), Text.GRAY)))
                    .clickEvent(ClickEvent.runCommand("/succes " + s.name()))
                    .build());
        }

        sender.sendMessage(Component.text(RULE, Text.DARK));
        TextComponent.Builder nav = Component.text();
        nav.append(page > 1
                ? Component.text("‹ précédent", text.accent()).clickEvent(ClickEvent.runCommand("/succes top texte " + (page - 1)))
                : Component.text("‹ précédent", Text.DARK));
        nav.append(Component.text("    page " + page + " / " + pages + "    ", Text.GRAY));
        nav.append(page < pages
                ? Component.text("suivant ›", text.accent()).clickEvent(ClickEvent.runCommand("/succes top texte " + (page + 1)))
                : Component.text("suivant ›", Text.DARK));
        if (self != null) {
            int r = stats.rank(self);
            nav.append(Component.text("    ta position  ", Text.DARK))
                    .append(Component.text(r == 0 ? "-" : "#" + r, Text.WHITE));
        }
        sender.sendMessage(nav.build());
    }

    // ───────────────────────────── Divers ─────────────────────────────

    private void toggleSidebar(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            text.send(sender, "players-only");
            return;
        }
        PlayerStats s = stats.getOrCreate(player.getUniqueId(), player.getName());
        s.sidebar(!s.sidebar());
        boards.setSidebarVisible(player, s.sidebar());
        text.send(sender, s.sidebar() ? "sidebar-on" : "sidebar-off");
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("Succès · aide", Text.WHITE, TextDecoration.BOLD));
        sender.sendMessage(Component.text(RULE, Text.DARK));
        helpLine(sender, "/missions", "menu des succès et missions restantes");
        helpLine(sender, "/succes", "ton profil");
        helpLine(sender, "/succes <joueur>", "profil d'un joueur");
        helpLine(sender, "/succes top", "podium et classement");
        helpLine(sender, "/succes sidebar", "afficher ou masquer le scoreboard");
        helpLine(sender, "/carte", "Dynmap du serveur sur lyonmc.fr");
        if (sender.hasPermission("lyonmc.succes.admin")) {
            helpLine(sender, "/succes reload", "recharger la configuration");
            helpLine(sender, "/succes import", "réimporter les fichiers du monde");
        }
    }

    private void helpLine(CommandSender sender, String cmd, String desc) {
        sender.sendMessage(Component.text()
                .append(Component.text(cmd, Text.WHITE))
                .append(Component.text("  " + desc, Text.DARK))
                .clickEvent(ClickEvent.suggestCommand(cmd.replaceAll(" [\\[<].*", " ").trim()))
                .build());
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ───────────────────────────── Tab ─────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("classement") || command.getName().equalsIgnoreCase("carte")) {
            return out;
        } else if (command.getName().equalsIgnoreCase("missions")) {
            if (args.length == 1) for (AdvancementRegistry.Category c : registry.usedCategories()) out.add(c.id());
        } else if (args.length == 1) {
            out.addAll(List.of("top", "menu", "sidebar", "aide"));
            if (sender.hasPermission("lyonmc.succes.admin")) out.addAll(List.of("reload", "import"));
            if (sender.hasPermission("lyonmc.succes.others")) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("menu")) {
            for (AdvancementRegistry.Category c : registry.usedCategories()) out.add(c.id());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
