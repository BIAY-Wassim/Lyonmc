package fr.lyonmc.achievements.season;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Saisons mensuelles : points, classement, récompenses de fin de saison, week-end bonus. */
public final class SeasonService {

    private final LyonAchievements plugin;
    private List<Profile> ranking = List.of();
    private final Map<UUID, Integer> rankIndex = new HashMap<>();
    private long lastRanking;

    public SeasonService(LyonAchievements plugin) {
        this.plugin = plugin;
        plugin.text().addProvider((b, uuid) -> {
            Profile p = uuid == null ? null : plugin.profiles().get(uuid);
            int rank = uuid == null ? 0 : rank(uuid);
            b.resolver(Placeholder.unparsed("season", String.valueOf(id())));
            b.resolver(Placeholder.unparsed("season_name", name()));
            b.resolver(Placeholder.unparsed("season_points", Text.num(p == null ? 0 : points(p))));
            b.resolver(Placeholder.unparsed("season_rank", rank == 0 ? "-" : String.valueOf(rank)));
            b.resolver(Placeholder.unparsed("season_left", timeLeft()));
            b.resolver(Placeholder.component("weekend_line", isWeekend() && enabled()
                    ? plugin.text().parse(" <accent>✦ Week-end : points x" + multiplierText() + "</accent>")
                    : Component.empty()));
            List<Profile> r = ranking();
            for (int i = 1; i <= 3; i++) {
                Profile t = i <= r.size() ? r.get(i - 1) : null;
                b.resolver(Placeholder.unparsed("season_top" + i + "_name", t == null ? "-" : t.name));
                b.resolver(Placeholder.unparsed("season_top" + i + "_points", t == null ? "0" : Text.num(points(t))));
            }
        });
    }

    private ConfigurationSection cfg() {
        return plugin.configs().events().getConfigurationSection("season");
    }

    private YamlConfiguration state() {
        return plugin.configs().state();
    }

    public boolean enabled() {
        return cfg() == null || cfg().getBoolean("enabled", true);
    }

    public void init() {
        if (!state().contains("season.id")) {
            state().set("season.id", 1);
            state().set("season.month", YearMonth.now(plugin.text().zone()).toString());
            plugin.configs().saveState();
        }
    }

    public int id() {
        return state().getInt("season.id", 1);
    }

    public String name() {
        String month = state().getString("season.month", YearMonth.now(plugin.text().zone()).toString());
        try {
            YearMonth ym = YearMonth.parse(month);
            String m = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.FRANCE);
            return "Saison " + id() + " · " + Character.toUpperCase(m.charAt(0)) + m.substring(1);
        } catch (Exception e) {
            return "Saison " + id();
        }
    }

    public String timeLeft() {
        ZonedDateTime now = ZonedDateTime.now(plugin.text().zone());
        ZonedDateTime end = YearMonth.from(now).plusMonths(1).atDay(1).atStartOfDay(plugin.text().zone());
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(now, end));
        long d = minutes / 1440, h = (minutes % 1440) / 60;
        return d > 0 ? d + " j " + h + " h" : h + " h " + (minutes % 60) + " min";
    }

    public boolean isWeekend() {
        DayOfWeek d = LocalDate.now(plugin.text().zone()).getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    public double multiplier() {
        return isWeekend() && cfg() != null ? Math.max(1, cfg().getDouble("weekend-multiplier", 2.0)) : 1.0;
    }

    private String multiplierText() {
        double m = cfg() == null ? 2 : cfg().getDouble("weekend-multiplier", 2.0);
        return m == Math.floor(m) ? String.valueOf((int) m) : String.valueOf(m);
    }

    // ───────────────────────────── Points ─────────────────────────────

    public int points(Profile p) {
        return p.seasonId == id() ? p.seasonPoints : 0;
    }

    public int addPoints(UUID uuid, String name, int base, boolean applyMultiplier) {
        if (!enabled() || base <= 0) return 0;
        Profile p = plugin.profiles().getOrCreate(uuid, name);
        if (p.seasonId != id()) {
            p.seasonId = id();
            p.seasonPoints = 0;
        }
        int gained = (int) Math.round(base * (applyMultiplier ? multiplier() : 1.0));
        p.seasonPoints += gained;
        p.dirty();
        lastRanking = 0;
        return gained;
    }

    /** Points de saison pour un nouveau succès. */
    public int pointsFor(io.papermc.paper.advancement.AdvancementDisplay.Frame frame) {
        if (cfg() == null) return 0;
        return switch (frame) {
            case CHALLENGE -> cfg().getInt("points.challenge", 60);
            case GOAL -> cfg().getInt("points.goal", 25);
            default -> cfg().getInt("points.task", 10);
        };
    }

    public List<Profile> ranking() {
        long now = System.currentTimeMillis();
        if (now - lastRanking > 5000) {
            List<Profile> list = new ArrayList<>();
            int id = id();
            for (Profile p : plugin.profiles().all()) if (p.seasonId == id && p.seasonPoints > 0 && p.name != null) list.add(p);
            list.sort(Comparator.comparingInt((Profile p) -> p.seasonPoints).reversed()
                    .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
            ranking = List.copyOf(list);
            rankIndex.clear();
            for (int i = 0; i < ranking.size(); i++) rankIndex.put(ranking.get(i).uuid(), i + 1);
            lastRanking = now;
        }
        return ranking;
    }

    public int rank(UUID uuid) {
        ranking();
        return rankIndex.getOrDefault(uuid, 0);
    }

    // ───────────────────────────── Fin de saison ─────────────────────────────

    /** Appelé chaque minute. */
    public void tick() {
        if (!enabled()) return;
        String current = YearMonth.now(plugin.text().zone()).toString();
        String stored = state().getString("season.month", current);
        if (!current.equals(stored)) endSeason(current);

        if (isWeekend() && cfg() != null && cfg().getBoolean("weekend-announce", true)) {
            LocalDate today = LocalDate.now(plugin.text().zone());
            LocalDate saturday = today.getDayOfWeek() == DayOfWeek.SUNDAY ? today.minusDays(1) : today;
            String key = saturday.toString();
            if (!key.equals(state().getString("season.weekend-announced"))) {
                state().set("season.weekend-announced", key);
                plugin.configs().saveState();
                Bukkit.broadcast(plugin.text().parse("<accent>✦</accent> <white>C'est le week-end !</white> <gray>Les points de saison sont <accent>x"
                        + multiplierText() + "</accent> jusqu'à dimanche minuit. <dark_gray>(/saison)"));
                plugin.discord().send("Week-end bonus", "Les points de saison sont multipliés par " + multiplierText()
                        + " jusqu'à dimanche minuit.", 0xE8C872, null);
            }
        }
    }

    public void endSeason(String newMonth) {
        lastRanking = 0;
        List<Profile> top = new ArrayList<>(ranking());
        int finished = id();
        String finishedName = name();
        int minPoints = cfg() == null ? 100 : cfg().getInt("participation-min-points", 100);

        StringBuilder history = new StringBuilder(state().getString("season.month", "?"));
        for (int i = 0; i < top.size(); i++) {
            Profile p = top.get(i);
            int pos = i + 1;
            String path = null;
            if (pos <= 3) path = "season.rewards." + pos;
            else if (pos <= 10) path = "season.rewards.top10";
            else if (p.seasonPoints >= minPoints) path = "season.rewards.participation";
            if (pos <= 3) {
                p.seasonPodiums++;
                plugin.custom().grantLater(p, "podium");
                history.append(';').append(pos).append(':').append(p.name).append(':').append(p.seasonPoints);
            }
            if (path != null) give(p, path);
            p.dirty();
        }
        state().set("season.history." + finished, history.toString());
        state().set("season.id", finished + 1);
        state().set("season.month", newMonth);
        plugin.configs().saveState();
        lastRanking = 0;

        Bukkit.broadcast(Component.empty());
        Bukkit.broadcast(plugin.text().parse("<accent>✦</accent> <white><bold>Fin de la " + finishedName + "</bold></white> <accent>✦</accent>"));
        String[] medals = {"<#ffd86b>♛ 1</#ffd86b>", "<#c8c8c8>✧ 2</#c8c8c8>", "<#d29a6a>✧ 3</#d29a6a>"};
        StringBuilder discord = new StringBuilder();
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            Bukkit.broadcast(plugin.text().parse("  " + medals[i] + " <white>" + top.get(i).name + "</white> <dark_gray>"
                    + Text.num(top.get(i).seasonPoints) + " pts"));
            discord.append(i + 1).append(". ").append(top.get(i).name).append(" — ").append(top.get(i).seasonPoints).append(" pts\n");
        }
        Bukkit.broadcast(plugin.text().parse("<gray>La <white>" + name() + "</white> commence. Bonne chance à tous !"));
        for (Player pl : Bukkit.getOnlinePlayers()) pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        plugin.discord().send("Fin de la " + finishedName, discord.isEmpty() ? "Aucun joueur classé." : discord.toString(),
                0xFFD86B, top.isEmpty() ? null : top.get(0).uuid());
    }

    /** Donne un lot de evenements.yml (tout de suite si connecté, sinon à la prochaine connexion). */
    public void give(Profile p, String path) {
        Player online = Bukkit.getPlayer(p.uuid());
        Bundle b = Bundle.of(plugin.configs().events().getConfigurationSection(path));
        if (online != null) {
            plugin.rewards().grant(online, b);
            plugin.rewards().check(online);
        } else {
            p.pendingBundles.add(path);
            p.dirty();
        }
    }

    /** Lots en attente (à la connexion). */
    public void deliverPending(Player player, Profile p) {
        if (p.pendingBundles.isEmpty()) return;
        for (String path : new ArrayList<>(p.pendingBundles)) {
            plugin.rewards().grant(player, Bundle.of(plugin.configs().events().getConfigurationSection(path)));
        }
        p.pendingBundles.clear();
        p.dirty();
        plugin.msg(player, "<accent>✦</accent> <gray>Tu as reçu des récompenses pendant ton absence (saison, boss...).");
    }

    public List<String> history() {
        ConfigurationSection h = state().getConfigurationSection("season.history");
        if (h == null) return List.of();
        List<String> keys = new ArrayList<>(h.getKeys(false));
        keys.sort(Comparator.comparingInt((String k) -> {
            try {
                return Integer.parseInt(k);
            } catch (NumberFormatException e) {
                return 0;
            }
        }).reversed());
        List<String> out = new ArrayList<>();
        for (String k : keys) out.add(k + ";" + h.getString(k, ""));
        return out;
    }
}
