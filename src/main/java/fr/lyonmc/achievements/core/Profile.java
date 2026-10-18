package fr.lyonmc.achievements.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;

/**
 * Toutes les données "jeu" d'un joueur (hors succès vanilla, gérés par PlayerStats) :
 * économie, titres, cosmétiques, missions, séries, saison, homes...
 */
public final class Profile {

    /** Progression d'une mission attribuée. */
    public static final class MissionSlot {
        public String id;
        public int progress;
        public boolean done;

        public MissionSlot(String id, int progress, boolean done) {
            this.id = id;
            this.progress = progress;
            this.done = done;
        }

        String serialize() {
            return id + "|" + progress + "|" + done;
        }

        static MissionSlot parse(String s) {
            String[] p = s.split("\\|");
            if (p.length < 3) return null;
            try {
                return new MissionSlot(p[0], Integer.parseInt(p[1]), Boolean.parseBoolean(p[2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private final UUID uuid;
    public String name;

    // Économie
    public long coins;
    public long earned;

    // Titres et cosmétiques
    public final Set<String> titles = new LinkedHashSet<>();
    public String title;
    public final Set<String> cosmetics = new LinkedHashSet<>();
    public String color;
    public String trail;

    // Paliers de récompenses obtenus
    public final Set<String> claimed = new HashSet<>();

    // Homes
    public int bonusHomes;
    public int boughtHomes;
    public final Map<String, StoredLocation> homes = new LinkedHashMap<>();

    // Missions
    public String dailyDate;
    public final List<MissionSlot> daily = new ArrayList<>();
    public int dailyRerolls;
    public boolean dailyBonusGiven;
    public String weekId;
    public final List<MissionSlot> weekly = new ArrayList<>();
    public int missionStreak;
    public String lastStreakDate;
    public int bestMissionStreak;
    public int missionsDone;

    // Récompense quotidienne
    public String lastLoginClaim;
    public int loginStreak;
    public int bestLoginStreak;

    // Saison
    public int seasonId;
    public int seasonPoints;
    public int seasonPodiums;

    // Statistiques diverses
    public int bossKills;
    public int bossLastHits;
    public long blocksMined;
    public int hdvSales;

    // Divers
    public final List<String> mailbox = new ArrayList<>();
    public final Set<String> pendingAdvancements = new HashSet<>();
    /** Chemins (dans evenements.yml) de lots à donner à la prochaine connexion. */
    public final List<String> pendingBundles = new ArrayList<>();
    public boolean actionbar = true;
    public long lastSeen;
    public long playtimeSeconds;
    public boolean firstJoinDone;

    private transient boolean dirty;

    public Profile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() {
        return uuid;
    }

    public void dirty() {
        this.dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clean() {
        this.dirty = false;
    }

    // ───────────────────────────── YAML ─────────────────────────────

    public static Profile load(UUID uuid, ConfigurationSection c) {
        Profile p = new Profile(uuid, c.getString("name"));
        p.coins = c.getLong("coins");
        p.earned = c.getLong("earned");
        p.titles.addAll(c.getStringList("titles"));
        p.title = c.getString("title");
        p.cosmetics.addAll(c.getStringList("cosmetics"));
        p.color = c.getString("color");
        p.trail = c.getString("trail");
        p.claimed.addAll(c.getStringList("claimed"));
        p.bonusHomes = c.getInt("bonus-homes");
        p.boughtHomes = c.getInt("bought-homes");
        ConfigurationSection homes = c.getConfigurationSection("homes");
        if (homes != null) {
            for (String k : homes.getKeys(false)) {
                StoredLocation l = StoredLocation.parse(homes.getString(k));
                if (l != null) p.homes.put(k, l);
            }
        }
        p.dailyDate = c.getString("missions.daily-date");
        for (String s : c.getStringList("missions.daily")) {
            MissionSlot m = MissionSlot.parse(s);
            if (m != null) p.daily.add(m);
        }
        p.dailyRerolls = c.getInt("missions.daily-rerolls");
        p.dailyBonusGiven = c.getBoolean("missions.daily-bonus");
        p.weekId = c.getString("missions.week-id");
        for (String s : c.getStringList("missions.weekly")) {
            MissionSlot m = MissionSlot.parse(s);
            if (m != null) p.weekly.add(m);
        }
        p.missionStreak = c.getInt("missions.streak");
        p.lastStreakDate = c.getString("missions.last-streak-date");
        p.bestMissionStreak = c.getInt("missions.best-streak");
        p.missionsDone = c.getInt("missions.done");
        p.lastLoginClaim = c.getString("login.last-claim");
        p.loginStreak = c.getInt("login.streak");
        p.bestLoginStreak = c.getInt("login.best-streak");
        p.seasonId = c.getInt("season.id");
        p.seasonPoints = c.getInt("season.points");
        p.seasonPodiums = c.getInt("season.podiums");
        p.bossKills = c.getInt("stats.boss-kills");
        p.bossLastHits = c.getInt("stats.boss-last-hits");
        p.blocksMined = c.getLong("stats.blocks-mined");
        p.hdvSales = c.getInt("stats.hdv-sales");
        p.mailbox.addAll(c.getStringList("mailbox"));
        p.pendingAdvancements.addAll(c.getStringList("pending-advancements"));
        p.pendingBundles.addAll(c.getStringList("pending-bundles"));
        p.actionbar = c.getBoolean("settings.actionbar", true);
        p.firstJoinDone = c.getBoolean("first-join-done", false);
        p.lastSeen = c.getLong("last-seen");
        p.playtimeSeconds = c.getLong("playtime-seconds");
        return p;
    }

    public YamlConfiguration save() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("name", name);
        c.set("coins", coins);
        c.set("earned", earned);
        c.set("titles", new ArrayList<>(titles));
        c.set("title", title);
        c.set("cosmetics", new ArrayList<>(cosmetics));
        c.set("color", color);
        c.set("trail", trail);
        c.set("claimed", new ArrayList<>(claimed));
        c.set("bonus-homes", bonusHomes);
        c.set("bought-homes", boughtHomes);
        for (Map.Entry<String, StoredLocation> e : homes.entrySet()) c.set("homes." + e.getKey(), e.getValue().serialize());
        c.set("missions.daily-date", dailyDate);
        List<String> d = new ArrayList<>();
        for (MissionSlot m : daily) d.add(m.serialize());
        c.set("missions.daily", d);
        c.set("missions.daily-rerolls", dailyRerolls);
        c.set("missions.daily-bonus", dailyBonusGiven);
        c.set("missions.week-id", weekId);
        List<String> w = new ArrayList<>();
        for (MissionSlot m : weekly) w.add(m.serialize());
        c.set("missions.weekly", w);
        c.set("missions.streak", missionStreak);
        c.set("missions.last-streak-date", lastStreakDate);
        c.set("missions.best-streak", bestMissionStreak);
        c.set("missions.done", missionsDone);
        c.set("login.last-claim", lastLoginClaim);
        c.set("login.streak", loginStreak);
        c.set("login.best-streak", bestLoginStreak);
        c.set("season.id", seasonId);
        c.set("season.points", seasonPoints);
        c.set("season.podiums", seasonPodiums);
        c.set("stats.boss-kills", bossKills);
        c.set("stats.boss-last-hits", bossLastHits);
        c.set("stats.blocks-mined", blocksMined);
        c.set("stats.hdv-sales", hdvSales);
        c.set("mailbox", new ArrayList<>(mailbox));
        c.set("pending-advancements", new ArrayList<>(pendingAdvancements));
        c.set("pending-bundles", new ArrayList<>(pendingBundles));
        c.set("settings.actionbar", actionbar);
        c.set("first-join-done", firstJoinDone);
        c.set("last-seen", lastSeen);
        c.set("playtime-seconds", playtimeSeconds);
        return c;
    }
}
