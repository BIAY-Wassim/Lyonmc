package fr.lyonmc.achievements.command;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.board.BoardManager;
import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.util.Text;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
    private static final TextColor ACCENT = TextColor.color(0xF9D423);
    private static final TextColor ACCENT2 = TextColor.color(0xFF4E50);

    private final LyonAchievements plugin;
    private final AdvancementRegistry registry;
    private final StatsService stats;
    private final BoardManager boards;
    private final Text text;

    public SuccesCommand(LyonAchievements plugin, AdvancementRegistry registry, StatsService stats,
                         BoardManager boards, Text text) {
        this.plugin = plugin;
        this.registry = registry;
        this.stats = stats;
        this.boards = boards;
        this.text = text;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
            case "top", "classement" -> showTop(sender, args.length > 1 ? parseInt(args[1], 1) : 1);
            case "manquants", "missing" -> showMissing(sender, args);
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
                PlayerStats target = stats.findByName(args[0]);
                Player online = Bukkit.getPlayerExact(args[0]);
                if (online != null) target = stats.refreshOnline(online);
                if (target == null) {
                    text.send(sender, "player-not-found");
                    return true;
                }
                showProfile(sender, target);
            }
        }
        return true;
    }

    // ───────────────────────────── Profil ─────────────────────────────

    private void showProfile(CommandSender sender, PlayerStats s) {
        int total = registry.total();
        int rank = stats.rank(s.uuid());

        sender.sendMessage(header("Succès de " + s.name()));
        sender.sendMessage(Component.text()
                .append(Component.text(" Progression ", NamedTextColor.GRAY))
                .append(Component.text(s.count(), NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("/" + total + " ", NamedTextColor.DARK_GRAY))
                .append(text.bar(s.count(), total, 20))
                .append(Component.text(" " + Text.percent(s.count(), total) + "%", NamedTextColor.YELLOW))
                .build());
        sender.sendMessage(Component.text()
                .append(Component.text(" Classement ", NamedTextColor.GRAY))
                .append(Component.text(rank == 0 ? "non classé" : "#" + rank, NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" sur " + stats.ranking().size() + " joueurs", NamedTextColor.DARK_GRAY))
                .clickEvent(ClickEvent.runCommand("/succes top " + Math.max(1, (rank - 1) / PAGE_SIZE + 1)))
                .hoverEvent(HoverEvent.showText(Component.text("Voir le classement", NamedTextColor.YELLOW)))
                .build());

        // Types
        TextComponent.Builder types = Component.text().append(Component.text(" ", NamedTextColor.GRAY));
        for (AdvancementDisplay.Frame f : new AdvancementDisplay.Frame[]{
                AdvancementDisplay.Frame.TASK, AdvancementDisplay.Frame.GOAL, AdvancementDisplay.Frame.CHALLENGE}) {
            TextColor c = AdvancementRegistry.frameColor(f);
            types.append(Component.text(AdvancementRegistry.frameName(f) + "s ", c))
                    .append(Component.text(s.count(f), NamedTextColor.WHITE))
                    .append(Component.text("/" + registry.total(f) + "   ", NamedTextColor.DARK_GRAY));
        }
        sender.sendMessage(types.build());
        sender.sendMessage(Component.empty());

        // Catégories
        for (AdvancementRegistry.Category c : registry.usedCategories()) {
            int done = s.count(c.id());
            int tot = registry.total(c.id());
            String name = c.name();
            String padded = name + " ".repeat(Math.max(1, 13 - name.length()));
            Component line = Component.text()
                    .append(Component.text(" " + padded, c.color()))
                    .append(text.bar(done, tot, 12))
                    .append(Component.text(" " + done, NamedTextColor.WHITE))
                    .append(Component.text("/" + tot, NamedTextColor.DARK_GRAY))
                    .append(done >= tot ? Component.text(" ✔", NamedTextColor.GREEN) : Component.empty())
                    .build();
            if (sender instanceof Player p && p.getUniqueId().equals(s.uuid())) {
                line = line.clickEvent(ClickEvent.runCommand("/succes manquants " + c.id()))
                        .hoverEvent(HoverEvent.showText(Component.text("Voir les succès manquants", NamedTextColor.YELLOW)));
            }
            sender.sendMessage(line);
        }
        sender.sendMessage(Component.empty());

        // Dernier succès
        AdvancementRegistry.Entry last = s.lastKey() == null ? null : registry.get(s.lastKey());
        sender.sendMessage(Component.text()
                .append(Component.text(" Dernier succès ", NamedTextColor.GRAY))
                .append(text.advancement(registry, stats, last))
                .append(last == null ? Component.empty()
                        : Component.text(" " + Text.timeAgo(s.lastTime()), NamedTextColor.DARK_GRAY))
                .build());

        // Succès le plus rare
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
                    .append(Component.text(" Plus rare ", NamedTextColor.GRAY))
                    .append(text.advancement(registry, stats, rarest))
                    .append(Component.text(String.format(Locale.US, " %.1f%% des joueurs", rarestValue), NamedTextColor.DARK_GRAY))
                    .build());
        }

        // Temps de jeu
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(s.uuid());
            long ticks = op.getStatistic(Statistic.PLAY_ONE_MINUTE);
            if (ticks > 0) {
                sender.sendMessage(Component.text()
                        .append(Component.text(" Temps de jeu ", NamedTextColor.GRAY))
                        .append(Component.text(Text.duration(ticks / 20), NamedTextColor.WHITE))
                        .append(s.count() > 0 ? Component.text("  (1 succès / "
                                + Text.duration(ticks / 20 / s.count()) + ")", NamedTextColor.DARK_GRAY) : Component.empty())
                        .build());
            }
        } catch (Exception ignored) {
            // statistique indisponible pour ce joueur
        }

        sender.sendMessage(footer());
    }

    // ───────────────────────────── Classement ─────────────────────────────

    private void showTop(CommandSender sender, int page) {
        List<PlayerStats> ranking = stats.ranking();
        int total = registry.total();
        int pages = Math.max(1, (ranking.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(pages, page));

        sender.sendMessage(header("Classement des succès"));
        if (ranking.isEmpty()) {
            sender.sendMessage(Component.text(" Personne n'est encore classé.", NamedTextColor.GRAY));
        }
        UUID self = sender instanceof Player p ? p.getUniqueId() : null;

        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(ranking.size(), page * PAGE_SIZE); i++) {
            PlayerStats s = ranking.get(i);
            int pos = i + 1;
            TextColor posColor = switch (pos) {
                case 1 -> TextColor.color(0xFFD700);
                case 2 -> TextColor.color(0xC0C0C0);
                case 3 -> TextColor.color(0xCD7F32);
                default -> NamedTextColor.GRAY;
            };
            boolean isSelf = s.uuid().equals(self);
            boolean isOnline = Bukkit.getPlayer(s.uuid()) != null;
            sender.sendMessage(Component.text()
                    .append(Component.text(pos <= 3 ? " ♛ " : "   ", posColor))
                    .append(Component.text(String.format("#%-3d ", pos), posColor, TextDecoration.BOLD))
                    .append(Component.text(isOnline ? "● " : "○ ", isOnline ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY))
                    .append(Component.text(s.name(), isSelf ? ACCENT : NamedTextColor.WHITE)
                            .decoration(TextDecoration.UNDERLINED, isSelf))
                    .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(s.count(), NamedTextColor.YELLOW))
                    .append(Component.text("/" + total + " ", NamedTextColor.DARK_GRAY))
                    .append(text.bar(s.count(), total, 8))
                    .append(Component.text(" ✦" + s.challenges(), TextColor.color(0xD58CFF)))
                    .hoverEvent(HoverEvent.showText(Component.text("Voir le profil de " + s.name(), NamedTextColor.YELLOW)))
                    .clickEvent(ClickEvent.runCommand("/succes " + s.name()))
                    .build());
        }

        // Navigation
        TextComponent.Builder nav = Component.text().append(Component.text(" "));
        if (page > 1) {
            nav.append(Component.text("« Précédent", ACCENT)
                    .clickEvent(ClickEvent.runCommand("/succes top " + (page - 1))));
        } else {
            nav.append(Component.text("« Précédent", NamedTextColor.DARK_GRAY));
        }
        nav.append(Component.text("   Page " + page + "/" + pages + "   ", NamedTextColor.GRAY));
        if (page < pages) {
            nav.append(Component.text("Suivant »", ACCENT)
                    .clickEvent(ClickEvent.runCommand("/succes top " + (page + 1))));
        } else {
            nav.append(Component.text("Suivant »", NamedTextColor.DARK_GRAY));
        }
        sender.sendMessage(nav.build());

        if (self != null) {
            int r = stats.rank(self);
            sender.sendMessage(Component.text()
                    .append(Component.text(" Ta position ", NamedTextColor.GRAY))
                    .append(Component.text(r == 0 ? "non classé" : "#" + r, NamedTextColor.GOLD, TextDecoration.BOLD))
                    .build());
        }
        sender.sendMessage(footer());
    }

    // ───────────────────────────── Manquants ─────────────────────────────

    private void showMissing(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            text.send(sender, "players-only");
            return;
        }
        PlayerStats s = stats.refreshOnline(player);

        String category = null;
        int page = 1;
        for (int i = 1; i < args.length; i++) {
            if (isInt(args[i])) page = parseInt(args[i], 1);
            else category = args[i].toLowerCase(Locale.ROOT);
        }

        List<AdvancementRegistry.Entry> missing = new ArrayList<>();
        for (AdvancementRegistry.Entry e : registry.entries()) {
            if (s.hasDone(e.key())) continue;
            if (category != null && !e.category().equals(category)) continue;
            missing.add(e);
        }

        int pages = Math.max(1, (missing.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(pages, page));
        String title = category == null ? "Succès manquants" : "Manquants · " + registry.category(category).name();
        sender.sendMessage(header(title + " (" + missing.size() + ")"));

        // Filtres cliquables
        TextComponent.Builder filters = Component.text().append(Component.text(" Filtre ", NamedTextColor.DARK_GRAY));
        filters.append(Component.text("[Tout] ", category == null ? ACCENT : NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/succes manquants")));
        for (AdvancementRegistry.Category c : registry.usedCategories()) {
            boolean sel = c.id().equals(category);
            filters.append(Component.text("[" + c.name() + "] ", sel ? ACCENT : c.color())
                    .clickEvent(ClickEvent.runCommand("/succes manquants " + c.id())));
        }
        sender.sendMessage(filters.build());

        if (missing.isEmpty()) {
            sender.sendMessage(Component.text(" Bravo, tout est débloqué ici ! ✔", NamedTextColor.GREEN));
        }

        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(missing.size(), page * PAGE_SIZE); i++) {
            AdvancementRegistry.Entry e = missing.get(i);
            TextColor color = AdvancementRegistry.frameColor(e.frame());
            Component line;
            if (e.hidden()) {
                line = Component.text()
                        .append(Component.text(" ◆ ", color))
                        .append(Component.text("??????", NamedTextColor.GRAY).decorate(TextDecoration.OBFUSCATED))
                        .append(Component.text(" (secret)", NamedTextColor.DARK_GRAY))
                        .hoverEvent(HoverEvent.showText(Component.text("Succès caché : à toi de le découvrir !", NamedTextColor.GRAY)))
                        .build();
            } else {
                line = Component.text()
                        .append(Component.text(" ◆ ", color))
                        .append(text.advancement(registry, stats, e))
                        .append(Component.text(" " + AdvancementRegistry.frameName(e.frame()), NamedTextColor.DARK_GRAY))
                        .build();
            }
            sender.sendMessage(line);
        }

        String base = "/succes manquants " + (category == null ? "" : category + " ");
        TextComponent.Builder nav = Component.text().append(Component.text(" "));
        nav.append(page > 1
                ? Component.text("« Précédent", ACCENT).clickEvent(ClickEvent.runCommand(base + (page - 1)))
                : Component.text("« Précédent", NamedTextColor.DARK_GRAY));
        nav.append(Component.text("   Page " + page + "/" + pages + "   ", NamedTextColor.GRAY));
        nav.append(page < pages
                ? Component.text("Suivant »", ACCENT).clickEvent(ClickEvent.runCommand(base + (page + 1)))
                : Component.text("Suivant »", NamedTextColor.DARK_GRAY));
        sender.sendMessage(nav.build());
        sender.sendMessage(footer());
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
        sender.sendMessage(header("Aide · /succes"));
        helpLine(sender, "/succes", "ton profil de succès");
        helpLine(sender, "/succes <joueur>", "profil d'un autre joueur");
        helpLine(sender, "/succes top [page]", "classement du serveur");
        helpLine(sender, "/succes manquants [catégorie]", "ce qu'il te reste à faire");
        helpLine(sender, "/succes sidebar", "afficher / cacher le scoreboard");
        if (sender.hasPermission("lyonmc.succes.admin")) {
            helpLine(sender, "/succes reload", "recharger la config");
            helpLine(sender, "/succes import", "réimporter les fichiers du monde");
        }
        sender.sendMessage(footer());
    }

    private void helpLine(CommandSender sender, String cmd, String desc) {
        sender.sendMessage(Component.text()
                .append(Component.text(" " + cmd, ACCENT))
                .append(Component.text(" — " + desc, NamedTextColor.GRAY))
                .clickEvent(ClickEvent.suggestCommand(cmd.replaceAll(" [\\[<].*", " ").trim()))
                .build());
    }

    private Component header(String title) {
        return Component.text()
                .append(Component.text("━━━━━━━ ", ACCENT2).decoration(TextDecoration.STRIKETHROUGH, false))
                .append(Component.text("✦ ", ACCENT))
                .append(Component.text(title, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" ✦", ACCENT))
                .append(Component.text(" ━━━━━━━", ACCENT2))
                .build();
    }

    private Component footer() {
        return Component.text("━━━━━━━━━━━━━ lyonmc.fr ━━━━━━━━━━━━━", ACCENT2);
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
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
        if (args.length == 1) {
            out.addAll(List.of("top", "manquants", "sidebar", "aide"));
            if (sender.hasPermission("lyonmc.succes.admin")) out.addAll(List.of("reload", "import"));
            if (sender.hasPermission("lyonmc.succes.others")) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("manquants") || args[0].equalsIgnoreCase("missing"))) {
            for (AdvancementRegistry.Category c : registry.usedCategories()) out.add(c.id());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
