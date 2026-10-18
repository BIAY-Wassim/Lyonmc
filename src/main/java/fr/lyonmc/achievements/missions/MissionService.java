package fr.lyonmc.achievements.missions;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Missions quotidiennes (facile / moyenne / difficile) et hebdomadaires, avec série de jours.
 */
public final class MissionService {

    private final LyonAchievements plugin;
    private final Map<String, MissionDef> defs = new LinkedHashMap<>();
    private final Map<String, List<MissionDef>> byTier = new HashMap<>();
    private final Map<UUID, Long> lastActionbar = new HashMap<>();
    private final Random random = new Random();

    public MissionService(LyonAchievements plugin) {
        this.plugin = plugin;
        plugin.text().addProvider((b, uuid) -> {
            Profile p = uuid == null ? null : plugin.profiles().get(uuid);
            int done = 0;
            int total = 0;
            if (p != null && today().equals(p.dailyDate)) {
                for (Profile.MissionSlot m : p.daily) {
                    total++;
                    if (m.done) done++;
                }
            } else {
                total = dailyCount();
            }
            b.resolver(Placeholder.unparsed("daily_done", String.valueOf(done)));
            b.resolver(Placeholder.unparsed("daily_total", String.valueOf(total)));
            b.resolver(Placeholder.unparsed("streak", String.valueOf(p == null ? 0 : activeStreak(p))));
            int wd = 0;
            if (p != null && weekId().equals(p.weekId)) for (Profile.MissionSlot m : p.weekly) if (m.done) wd++;
            b.resolver(Placeholder.unparsed("weekly_done", String.valueOf(wd)));
            b.resolver(Placeholder.unparsed("missions_done", String.valueOf(p == null ? 0 : p.missionsDone)));
        });
    }

    // ───────────────────────────── Chargement ─────────────────────────────

    public void load() {
        defs.clear();
        byTier.clear();
        ConfigurationSection daily = plugin.configs().missions().getConfigurationSection("daily");
        if (daily != null) {
            for (String tier : daily.getKeys(false)) {
                ConfigurationSection t = daily.getConfigurationSection(tier);
                if (t == null) continue;
                for (String id : t.getKeys(false)) add(MissionDef.of(id, tier, Objects.requireNonNull(t.getConfigurationSection(id))));
            }
        }
        ConfigurationSection weekly = plugin.configs().missions().getConfigurationSection("weekly");
        if (weekly != null) {
            for (String id : weekly.getKeys(false)) {
                ConfigurationSection c = weekly.getConfigurationSection(id);
                if (c != null) add(MissionDef.of(id, "weekly", c));
            }
        }
        plugin.getLogger().info(defs.size() + " missions chargées.");
    }

    private void add(MissionDef def) {
        if (def == null) return;
        defs.put(def.id(), def);
        byTier.computeIfAbsent(def.tier(), k -> new ArrayList<>()).add(def);
    }

    public MissionDef def(String id) {
        return defs.get(id);
    }

    private ConfigurationSection settings() {
        return plugin.configs().missions().getConfigurationSection("settings");
    }

    private int dailyCount() {
        ConfigurationSection s = settings();
        if (s == null) return 3;
        return s.getInt("daily.easy", 1) + s.getInt("daily.medium", 1) + s.getInt("daily.hard", 1);
    }

    // ───────────────────────────── Dates ─────────────────────────────

    public String today() {
        return LocalDate.now(plugin.text().zone()).toString();
    }

    private String yesterday() {
        return LocalDate.now(plugin.text().zone()).minusDays(1).toString();
    }

    public String weekId() {
        LocalDate d = LocalDate.now(plugin.text().zone());
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** Série en cours (0 si un jour a été raté). */
    public int activeStreak(Profile p) {
        if (p.lastStreakDate == null) return 0;
        if (p.lastStreakDate.equals(today()) || p.lastStreakDate.equals(yesterday())) return p.missionStreak;
        return 0;
    }

    // ───────────────────────────── Attribution ─────────────────────────────

    /** Attribue de nouvelles missions si le jour ou la semaine a changé. Retourne true si de nouvelles missions du jour. */
    public boolean ensure(Profile p) {
        boolean fresh = false;
        if (!today().equals(p.dailyDate)) {
            if (activeStreak(p) == 0 && p.missionStreak != 0) p.missionStreak = 0;
            p.dailyDate = today();
            p.daily.clear();
            ConfigurationSection s = settings();
            Set<String> previous = new HashSet<>();
            for (String tier : List.of("easy", "medium", "hard")) {
                int n = s == null ? 1 : s.getInt("daily." + tier, 1);
                for (MissionDef d : pick(tier, n, previous)) {
                    p.daily.add(new Profile.MissionSlot(d.id(), 0, false));
                    previous.add(d.id());
                }
            }
            p.dailyRerolls = 0;
            p.dailyBonusGiven = false;
            p.dirty();
            fresh = true;
        }
        if (!weekId().equals(p.weekId)) {
            p.weekId = weekId();
            p.weekly.clear();
            int n = settings() == null ? 3 : settings().getInt("weekly", 3);
            for (MissionDef d : pick("weekly", n, Set.of())) p.weekly.add(new Profile.MissionSlot(d.id(), 0, false));
            p.dirty();
        }
        // Missions supprimées de la config : on les retire
        p.daily.removeIf(m -> !defs.containsKey(m.id));
        p.weekly.removeIf(m -> !defs.containsKey(m.id));
        return fresh;
    }

    private List<MissionDef> pick(String tier, int n, Set<String> exclude) {
        List<MissionDef> pool = new ArrayList<>(byTier.getOrDefault(tier, List.of()));
        pool.removeIf(d -> exclude.contains(d.id()));
        Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(n, pool.size()));
    }

    /** Vérifie le changement de jour pour les joueurs connectés (appelé régulièrement). */
    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile p = plugin.profiles().get(player);
            if (ensure(p) && p.dailyDate != null) {
                plugin.msg(player, "<accent>✦</accent> <gray>Nouvelles missions du jour disponibles : "
                        + "<click:run_command:'/missions'><hover:show_text:'<gray>Ouvrir'><white><u>/missions</u></white></hover></click>");
            }
        }
    }

    // ───────────────────────────── Progression ─────────────────────────────

    public void progress(Player player, MissionType type, String key, boolean hostile, int amount) {
        if (amount <= 0 || !plugin.module("missions")) return;
        Profile p = plugin.profiles().get(player);
        ensure(p);
        progressList(player, p, p.daily, false, type, key, hostile, amount);
        progressList(player, p, p.weekly, true, type, key, hostile, amount);
    }

    private void progressList(Player player, Profile p, List<Profile.MissionSlot> list, boolean weekly,
                              MissionType type, String key, boolean hostile, int amount) {
        for (Profile.MissionSlot slot : list) {
            if (slot.done) continue;
            MissionDef def = defs.get(slot.id);
            if (def == null || def.type() != type || !def.matches(key, hostile)) continue;
            slot.progress = Math.min(def.amount(), slot.progress + amount);
            p.dirty();
            if (slot.progress >= def.amount()) {
                slot.done = true;
                complete(player, p, def, weekly);
            } else {
                actionbar(player, p, def, slot.progress);
            }
        }
    }

    private void actionbar(Player player, Profile p, MissionDef def, int progress) {
        boolean enabled = settings() == null || settings().getBoolean("actionbar", true);
        if (!enabled || !p.actionbar) return;
        long now = System.currentTimeMillis();
        Long last = lastActionbar.get(player.getUniqueId());
        if (last != null && now - last < 400) return;
        lastActionbar.put(player.getUniqueId(), now);
        player.sendActionBar(Component.text()
                .append(Component.text("✦ ", plugin.text().accent()))
                .append(Component.text(def.name() + "  ", Text.WHITE))
                .append(plugin.text().bar(progress, def.amount(), 15))
                .append(Component.text("  " + Text.num(progress) + "/" + Text.num(def.amount()), Text.GRAY))
                .build());
    }

    private Bundle baseReward(String tier) {
        return Bundle.of(plugin.configs().missions().getConfigurationSection("rewards." + tier));
    }

    /** Multiplicateur de pièces lié à la série. */
    public double streakMultiplier(Profile p) {
        ConfigurationSection s = settings();
        double per = s == null ? 10 : s.getDouble("streak-bonus-percent", 10);
        double max = s == null ? 100 : s.getDouble("streak-bonus-max", 100);
        return 1 + Math.min(max, per * activeStreak(p)) / 100.0;
    }

    private void complete(Player player, Profile p, MissionDef def, boolean weekly) {
        p.missionsDone++;
        Bundle reward = baseReward(def.tier()).scaled(streakMultiplier(p), 1);
        plugin.rewards().grant(player, reward);

        player.showTitle(Title.title(
                plugin.text().parse("<accent>✔ <white>Mission accomplie</white>"),
                Component.text(def.name(), Text.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(500))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
        StringBuilder gains = new StringBuilder();
        if (reward.coins() > 0) gains.append("+").append(plugin.economy().format(reward.coins()));
        if (reward.seasonPoints() > 0) gains.append("  +").append(reward.seasonPoints()).append(" pts de saison");
        plugin.msg(player, "<accent>✔</accent> <gray>Mission " + (weekly ? "de la semaine" : "du jour")
                + " terminée : <white>" + def.name() + "</white> <dark_gray>" + gains);

        plugin.custom().grant(player, "premiere_mission");
        if (weekly) plugin.custom().grant(player, "hebdo");
        if (p.missionsDone >= 100) plugin.custom().grant(player, "missions_100");

        if (!weekly && !p.dailyBonusGiven) {
            boolean all = !p.daily.isEmpty();
            for (Profile.MissionSlot s : p.daily) if (!s.done) all = false;
            if (all) allDailyDone(player, p);
        }
        p.dirty();
        plugin.rewards().check(player);
    }

    private void allDailyDone(Player player, Profile p) {
        p.dailyBonusGiven = true;
        if (yesterday().equals(p.lastStreakDate)) p.missionStreak++;
        else if (!today().equals(p.lastStreakDate)) p.missionStreak = 1;
        p.lastStreakDate = today();
        p.bestMissionStreak = Math.max(p.bestMissionStreak, p.missionStreak);
        Bundle bonus = Bundle.of(plugin.configs().missions().getConfigurationSection("settings.all-daily-bonus"));
        plugin.rewards().grant(player, bonus);
        plugin.msg(player, "<accent>✦</accent> <white>Toutes les missions du jour sont terminées !</white> <gray>Série : <accent>"
                + p.missionStreak + " jour" + (p.missionStreak > 1 ? "s" : "") + "</accent> <dark_gray>(bonus +"
                + plugin.economy().format(bonus.coins()) + ")");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.5f);
        plugin.custom().grant(player, "routine");
        if (p.missionStreak >= 7) plugin.custom().grant(player, "serie_7");
        if (p.missionStreak >= 30) plugin.custom().grant(player, "serie_30");
    }

    // ───────────────────────────── Changer une mission ─────────────────────────────

    public long rerollPrice(Profile p) {
        ConfigurationSection s = settings();
        int free = s == null ? 1 : s.getInt("free-rerolls", 1);
        return p.dailyRerolls < free ? 0 : (s == null ? 150 : s.getLong("reroll-price", 150));
    }

    public boolean reroll(Player player, int index) {
        Profile p = plugin.profiles().get(player);
        ensure(p);
        if (index < 0 || index >= p.daily.size()) return false;
        Profile.MissionSlot slot = p.daily.get(index);
        if (slot.done) return false;
        MissionDef old = defs.get(slot.id);
        if (old == null) return false;
        Set<String> exclude = new HashSet<>();
        for (Profile.MissionSlot s : p.daily) exclude.add(s.id);
        List<MissionDef> choice = pick(old.tier(), 1, exclude);
        if (choice.isEmpty()) {
            plugin.msg(player, "<gray>Aucune autre mission disponible pour cette difficulté.");
            return false;
        }
        long price = rerollPrice(p);
        if (price > 0 && !plugin.economy().withdraw(player.getUniqueId(), price)) {
            plugin.msg(player, "<gray>Il te faut " + plugin.economy().format(price) + " pour changer de mission.");
            return false;
        }
        p.dailyRerolls++;
        p.daily.set(index, new Profile.MissionSlot(choice.get(0).id(), 0, false));
        p.dirty();
        plugin.msg(player, "<gray>Nouvelle mission : <white>" + choice.get(0).name()
                + (price > 0 ? "</white> <dark_gray>(-" + plugin.economy().format(price) + ")" : ""));
        return true;
    }

    /** Remise à zéro (admin) */
    public void reset(Profile p) {
        p.dailyDate = null;
        p.weekId = null;
        ensure(p);
    }
}
