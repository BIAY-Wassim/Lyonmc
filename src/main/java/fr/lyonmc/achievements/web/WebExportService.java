package fr.lyonmc.achievements.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.missions.MissionDef;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.rewards.RewardService;
import fr.lyonmc.achievements.team.Team;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Level;

/**
 * Exporte les données du serveur en JSON pour le site lyonmc.fr :
 * serveur.json (état en direct), classements.json, joueurs.json, contenu.json (guide).
 */
public final class WebExportService implements Listener {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final LyonAchievements plugin;
    private volatile boolean warned;
    private volatile long lastContent;

    public WebExportService(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("web");
    }

    public boolean enabled() {
        return cfg() != null && cfg().getBoolean("enabled", true);
    }

    /** Dossier d'export. Un chemin relatif part du dossier du serveur (celui qui contient plugins/). */
    private Path folder() {
        Path p = Path.of(cfg().getString("export-path", "siteweb/data"));
        if (p.isAbsolute()) return p;
        return plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile().toPath().resolve(p).normalize();
    }

    // ───────────────────────────── Suivi des joueurs ─────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        touch(event.getPlayer());
    }

    private void touch(Player p) {
        Profile pr = plugin.profiles().get(p);
        pr.lastSeen = System.currentTimeMillis();
        pr.playtimeSeconds = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
        pr.dirty();
    }

    // ───────────────────────────── Export ─────────────────────────────

    /** Appelé régulièrement sur le thread principal : prépare les données puis écrit en asynchrone. */
    public void export() {
        if (!enabled()) return;
        for (Player p : Bukkit.getOnlinePlayers()) touch(p);
        Map<String, String> files = new LinkedHashMap<>();
        try {
            files.put("serveur.json", GSON.toJson(server()));
            files.put("classements.json", GSON.toJson(leaderboards()));
            files.put("joueurs.json", GSON.toJson(players()));
            files.put("actualites.json", GSON.toJson(news()));
            long now = System.currentTimeMillis();
            // Le guide est regénéré toutes les 10 minutes, ou tout de suite s'il a disparu du dossier
            if (now - lastContent > 10 * 60_000L || !Files.exists(folder().resolve("contenu.json"))) {
                files.put("contenu.json", GSON.toJson(content()));
                lastContent = now;
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Export web impossible", e);
            return;
        }
        Path dir = folder();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(dir, files));
    }

    /** Force la régénération du guide au prochain export (après /lmc reload). */
    public void invalidateContent() {
        lastContent = 0;
    }

    private synchronized void write(Path dir, Map<String, String> files) {
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path tmp = dir.resolve(e.getKey() + ".tmp");
                Files.writeString(tmp, e.getValue(), StandardCharsets.UTF_8);
                Files.move(tmp, dir.resolve(e.getKey()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            warned = false;
        } catch (IOException e) {
            lastContent = 0;
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Export du site impossible dans " + dir + " (" + e.getMessage() + "). "
                        + "Vérifie que le dossier existe et appartient à l'utilisateur du serveur Minecraft.");
            }
        }
    }

    // ───────────────────────────── actualites.json ─────────────────────────────

    private List<Map<String, Object>> news() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> n : plugin.configs().news().getMapList("actualites")) {
            if (n.get("titre") == null) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("date", String.valueOf(n.get("date") == null ? "" : n.get("date")));
            o.put("tag", n.get("tag") == null ? "" : String.valueOf(n.get("tag")));
            o.put("title", String.valueOf(n.get("titre")));
            o.put("text", n.get("texte") == null ? "" : String.valueOf(n.get("texte")));
            out.add(o);
            if (out.size() >= 50) break;
        }
        return out;
    }

    // ───────────────────────────── serveur.json ─────────────────────────────

    private Map<String, Object> server() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generated", System.currentTimeMillis());
        m.put("ip", cfg().getString("ip", "lyonmc.fr"));
        m.put("discord", cfg().getString("discord", ""));
        m.put("version", cfg().getString("version-conseillee", "1.21.8"));
        m.put("versions", cfg().getString("versions-acceptees", ""));
        Map<String, Object> bedrock = new LinkedHashMap<>();
        bedrock.put("enabled", cfg().getBoolean("bedrock.enabled", false));
        bedrock.put("address", cfg().getString("bedrock.adresse", cfg().getString("ip", "lyonmc.fr")));
        bedrock.put("port", cfg().getInt("bedrock.port", 19132));
        m.put("bedrock", bedrock);
        Map<String, Object> legal = new LinkedHashMap<>();
        for (String k : List.of("editeur", "contact", "directeur", "hebergeur", "hebergeur-adresse", "hebergeur-telephone", "mise-a-jour",
                "texte-mentions", "texte-confidentialite")) {
            legal.put(k, cfg().getString("mentions-legales." + k, ""));
        }
        legal.put("remplacer", cfg().getBoolean("mentions-legales.remplacer", false));
        m.put("legal", legal);
        m.put("online", Bukkit.getOnlinePlayers().size());
        m.put("max", Bukkit.getMaxPlayers());
        List<Map<String, Object>> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", p.getName());
            o.put("uuid", p.getUniqueId().toString());
            online.add(o);
        }
        online.sort(Comparator.comparing(o -> String.valueOf(o.get("name")), String.CASE_INSENSITIVE_ORDER));
        m.put("players", online);

        AdvancementRegistry reg = plugin.registry();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("achievements", reg.total());
        totals.put("challenges", reg.total(AdvancementDisplay.Frame.CHALLENGE));
        totals.put("unlocked", plugin.stats().serverTotal());
        totals.put("ranked", plugin.stats().ranking().size());
        totals.put("profiles", plugin.profiles().all().size());
        totals.put("teams", plugin.teams().all().size());
        totals.put("listings", plugin.auctions().active().size());
        m.put("totals", totals);

        Map<String, Object> season = new LinkedHashMap<>();
        season.put("id", plugin.seasons().id());
        season.put("name", plugin.seasons().name());
        ZonedDateTime end = YearMonth.now(plugin.text().zone()).plusMonths(1).atDay(1).atStartOfDay(plugin.text().zone());
        season.put("endsAt", end.toInstant().toEpochMilli());
        season.put("weekend", plugin.seasons().isWeekend());
        season.put("multiplier", plugin.seasons().multiplier());
        m.put("season", season);

        Map<String, Object> boss = new LinkedHashMap<>();
        boss.put("name", WebText.plain(plugin.text().parse(plugin.configs().events().getString("boss.name", "Le Colosse"))));
        boss.put("active", plugin.boss().active());
        ZonedDateTime next = plugin.boss().arena() == null ? null : plugin.boss().next();
        boss.put("next", next == null ? null : next.toInstant().toEpochMilli());
        m.put("boss", boss);

        PlayerStats last = plugin.stats().lastGlobal();
        if (last != null && last.lastKey() != null && reg.get(last.lastKey()) != null) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("player", last.name());
            l.put("uuid", last.uuid().toString());
            l.put("advancement", last.lastKey());
            l.put("time", last.lastTime());
            m.put("last", l);
        }
        return m;
    }

    // ───────────────────────────── classements.json ─────────────────────────────

    private Map<String, Object> leaderboards() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generated", System.currentTimeMillis());

        List<Map<String, Object>> ach = new ArrayList<>();
        List<PlayerStats> ranking = plugin.stats().ranking();
        for (int i = 0; i < Math.min(100, ranking.size()); i++) {
            PlayerStats s = ranking.get(i);
            Map<String, Object> o = base(s.uuid(), s.name());
            o.put("count", s.count());
            o.put("challenges", s.count(AdvancementDisplay.Frame.CHALLENGE));
            ach.add(o);
        }
        m.put("achievements", ach);

        List<Map<String, Object>> season = new ArrayList<>();
        List<Profile> sr = plugin.seasons().ranking();
        for (int i = 0; i < Math.min(50, sr.size()); i++) {
            Profile p = sr.get(i);
            Map<String, Object> o = base(p.uuid(), p.name);
            o.put("points", plugin.seasons().points(p));
            season.add(o);
        }
        m.put("season", season);

        List<Map<String, Object>> teams = new ArrayList<>();
        for (Team t : plugin.teams().top()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", t.name);
            o.put("tag", t.tag);
            o.put("color", t.color);
            o.put("owner", plugin.teams().memberName(t.owner));
            List<String> members = new ArrayList<>();
            for (UUID u : t.members) members.add(plugin.teams().memberName(u));
            o.put("members", members);
            o.put("achievements", plugin.teams().achievements(t));
            o.put("season", plugin.teams().seasonPoints(t));
            teams.add(o);
        }
        m.put("teams", teams);

        List<Map<String, Object>> rich = new ArrayList<>();
        List<Profile> top = plugin.economy().top();
        for (int i = 0; i < Math.min(20, top.size()); i++) {
            Map<String, Object> o = base(top.get(i).uuid(), top.get(i).name);
            o.put("coins", top.get(i).coins);
            rich.add(o);
        }
        m.put("richest", rich);

        List<Profile> all = new ArrayList<>(plugin.profiles().all());
        all.removeIf(p -> p.name == null || p.missionsDone == 0);
        all.sort(Comparator.comparingInt((Profile p) -> p.missionsDone).reversed());
        List<Map<String, Object>> missions = new ArrayList<>();
        for (int i = 0; i < Math.min(20, all.size()); i++) {
            Profile p = all.get(i);
            Map<String, Object> o = base(p.uuid(), p.name);
            o.put("missions", p.missionsDone);
            o.put("streak", Math.max(p.bestMissionStreak, p.missionStreak));
            missions.add(o);
        }
        m.put("missions", missions);

        // Métiers : total des niveaux, puis chaque métier
        if (plugin.jobs().enabled()) {
            Map<String, Object> jobs = new LinkedHashMap<>();
            List<Map<String, Object>> total = new ArrayList<>();
            List<Profile> tj = plugin.jobs().top(null);
            for (int i = 0; i < Math.min(20, tj.size()); i++) {
                Profile p = tj.get(i);
                Map<String, Object> o = base(p.uuid(), p.name);
                o.put("levels", plugin.jobs().totalLevels(p));
                total.add(o);
            }
            jobs.put("total", total);
            for (fr.lyonmc.achievements.jobs.JobService.Job job : plugin.jobs().all()) {
                List<Map<String, Object>> list = new ArrayList<>();
                List<Profile> tl = plugin.jobs().top(job);
                for (int i = 0; i < Math.min(20, tl.size()); i++) {
                    Profile p = tl.get(i);
                    Map<String, Object> o = base(p.uuid(), p.name);
                    o.put("level", plugin.jobs().level(p, job.id()));
                    o.put("xp", plugin.jobs().xp(p, job.id()));
                    list.add(o);
                }
                jobs.put(job.id(), list);
            }
            m.put("jobs", jobs);
        }

        List<Map<String, Object>> history = new ArrayList<>();
        for (String h : plugin.seasons().history()) {
            String[] parts = h.split(";");
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", parts[0]);
            o.put("month", parts.length > 1 ? parts[1] : "");
            List<Map<String, Object>> podium = new ArrayList<>();
            for (int k = 2; k < parts.length; k++) {
                String[] w = parts[k].split(":");
                if (w.length < 3) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("pos", w[0]);
                e.put("name", w[1]);
                e.put("points", w[2]);
                podium.add(e);
            }
            o.put("podium", podium);
            history.add(o);
        }
        m.put("history", history);
        return m;
    }

    private Map<String, Object> base(UUID uuid, String name) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", name);
        o.put("uuid", uuid.toString());
        Profile p = plugin.profiles().get(uuid);
        Component title = p == null ? Component.empty() : plugin.rewards().titleRaw(p);
        if (!title.equals(Component.empty())) o.put("title", WebText.html(title));
        fr.lyonmc.achievements.rewards.GradeService.Grade g = plugin.grades().shown(uuid);
        o.put("grade", WebText.html(plugin.text().parse(g.display())));
        o.put("gradeId", g.id());
        Team t = plugin.teams().teamOf(uuid);
        if (t != null) o.put("team", t.tag);
        return o;
    }

    // ───────────────────────────── joueurs.json ─────────────────────────────

    private Map<String, Object> players() {
        Map<String, Object> out = new LinkedHashMap<>();
        AdvancementRegistry reg = plugin.registry();
        Set<UUID> seen = new HashSet<>();
        List<UUID> ids = new ArrayList<>();
        for (PlayerStats s : plugin.stats().all()) if (s.name() != null && seen.add(s.uuid())) ids.add(s.uuid());
        for (Profile p : plugin.profiles().all()) if (p.name != null && seen.add(p.uuid())) ids.add(p.uuid());

        for (UUID uuid : ids) {
            PlayerStats s = plugin.stats().get(uuid);
            Profile p = plugin.profiles().get(uuid);
            String name = s != null && s.name() != null ? s.name() : p != null ? p.name : null;
            if (name == null) continue;
            Map<String, Object> o = base(uuid, name);
            o.put("online", Bukkit.getPlayer(uuid) != null);
            int count = s == null ? 0 : s.count();
            o.put("count", count);
            o.put("rank", plugin.stats().rank(uuid));
            Map<String, Integer> frames = new LinkedHashMap<>();
            frames.put("task", s == null ? 0 : s.count(AdvancementDisplay.Frame.TASK));
            frames.put("goal", s == null ? 0 : s.count(AdvancementDisplay.Frame.GOAL));
            frames.put("challenge", s == null ? 0 : s.count(AdvancementDisplay.Frame.CHALLENGE));
            o.put("frames", frames);
            Map<String, Integer> cats = new LinkedHashMap<>();
            for (AdvancementRegistry.Category c : reg.usedCategories()) cats.put(c.id(), s == null ? 0 : s.count(c.id()));
            o.put("categories", cats);
            List<Object[]> done = new ArrayList<>();
            if (s != null) {
                for (Map.Entry<String, Long> e : s.done().entrySet()) {
                    if (reg.isCounted(e.getKey())) done.add(new Object[]{e.getKey(), e.getValue()});
                }
                done.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));
            }
            o.put("done", done);
            if (p != null) {
                o.put("coins", p.coins);
                o.put("seasonPoints", plugin.seasons().points(p));
                o.put("seasonRank", plugin.seasons().rank(uuid));
                o.put("missions", p.missionsDone);
                o.put("streak", plugin.missions().activeStreak(p));
                o.put("bestStreak", Math.max(p.bestMissionStreak, p.missionStreak));
                o.put("loginBest", Math.max(p.bestLoginStreak, p.loginStreak));
                o.put("bossKills", p.bossKills);
                o.put("titles", plugin.rewards().unlockedTitles(p));
                o.put("paliers", p.claimed.size());
                o.put("playtime", p.playtimeSeconds);
                o.put("lastSeen", p.lastSeen);
                o.put("blocks", p.blocksMined);
                o.put("sales", p.hdvSales);
                if (plugin.jobs().enabled()) {
                    Map<String, Object> jobs = new LinkedHashMap<>();
                    for (fr.lyonmc.achievements.jobs.JobService.Job job : plugin.jobs().all()) {
                        long xp = plugin.jobs().xp(p, job.id());
                        Map<String, Object> j = new LinkedHashMap<>();
                        j.put("level", plugin.jobs().level(xp));
                        j.put("into", plugin.jobs().into(xp));
                        j.put("span", plugin.jobs().span(xp));
                        jobs.put(job.id(), j);
                    }
                    o.put("jobs", jobs);
                }
            }
            Team t = plugin.teams().teamOf(uuid);
            if (t != null) {
                Map<String, Object> team = new LinkedHashMap<>();
                team.put("name", t.name);
                team.put("tag", t.tag);
                team.put("color", t.color);
                o.put("teamInfo", team);
            }
            out.put(name.toLowerCase(Locale.ROOT), o);
        }
        return out;
    }

    // ───────────────────────────── contenu.json (guide) ─────────────────────────────

    private Map<String, Object> content() {
        Map<String, Object> m = new LinkedHashMap<>();
        AdvancementRegistry reg = plugin.registry();

        List<Map<String, Object>> cats = new ArrayList<>();
        for (AdvancementRegistry.Category c : reg.usedCategories()) {
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("id", c.id());
            cat.put("name", c.name());
            List<Map<String, Object>> advs = new ArrayList<>();
            for (AdvancementRegistry.Entry e : reg.entries()) {
                if (!e.category().equals(c.id())) continue;
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("key", e.key());
                String tk = WebText.key(e.title());
                if (tk != null) a.put("titleKey", tk);
                a.put("title", WebText.plain(e.title()));
                String dk = WebText.key(e.description());
                if (dk != null) a.put("descKey", dk);
                a.put("desc", WebText.plain(e.description()));
                a.put("frame", e.frame().name().toLowerCase(Locale.ROOT));
                a.put("hidden", e.hidden());
                a.put("icon", e.icon().getType().getKey().getKey());
                a.put("rarity", Math.round(plugin.stats().rarity(e.key()) * 10) / 10.0);
                advs.add(a);
            }
            cat.put("advancements", advs);
            cats.add(cat);
        }
        m.put("categories", cats);

        Map<String, List<Map<String, Object>>> missions = new LinkedHashMap<>();
        for (MissionDef d : plugin.missions().all()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", d.name());
            o.put("icon", d.icon().getKey().getKey());
            missions.computeIfAbsent(d.tier(), k -> new ArrayList<>()).add(o);
        }
        m.put("missions", missions);
        Map<String, Object> missionRewards = new LinkedHashMap<>();
        for (String tier : List.of("easy", "medium", "hard", "weekly")) {
            missionRewards.put(tier, bundle(Bundle.of(plugin.configs().missions().getConfigurationSection("rewards." + tier))));
        }
        missionRewards.put("bonus", bundle(Bundle.of(plugin.configs().missions().getConfigurationSection("settings.all-daily-bonus"))));
        missionRewards.put("streakPercent", plugin.configs().missions().getDouble("settings.streak-bonus-percent", 10));
        missionRewards.put("streakMax", plugin.configs().missions().getDouble("settings.streak-bonus-max", 100));
        m.put("missionRewards", missionRewards);

        RewardService rw = plugin.rewards();
        List<Map<String, Object>> paliers = new ArrayList<>();
        for (RewardService.Palier pa : rw.paliers()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", pa.name());
            o.put("icon", pa.icon().getKey().getKey());
            o.put("condition", rw.describe(pa.condition()));
            o.put("rewards", bundle(pa.reward()));
            paliers.add(o);
        }
        m.put("paliers", paliers);

        List<Map<String, Object>> titles = new ArrayList<>();
        for (RewardService.TitleDef t : rw.titles()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("html", WebText.html(plugin.text().parse(t.display())));
            o.put("description", t.description());
            if (t.price() > 0) o.put("price", t.price());
            titles.add(o);
        }
        m.put("titles", titles);

        List<Map<String, Object>> grades = new ArrayList<>();
        for (fr.lyonmc.achievements.rewards.GradeService.Grade g : plugin.grades().all()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", g.id());
            o.put("html", WebText.html(plugin.text().parse(g.display())));
            o.put("percent", g.percent());
            grades.add(o);
        }
        m.put("grades", grades);

        List<Map<String, Object>> colors = new ArrayList<>();
        for (RewardService.CosmeticDef c : rw.colors()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("html", WebText.html(rw.colorPreview(c, c.display())));
            if (c.price() > 0) o.put("price", c.price());
            colors.add(o);
        }
        m.put("colors", colors);
        List<Map<String, Object>> trails = new ArrayList<>();
        for (RewardService.CosmeticDef c : rw.trails()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", c.display());
            o.put("icon", c.icon().getKey().getKey());
            if (c.price() > 0) o.put("price", c.price());
            trails.add(o);
        }
        m.put("trails", trails);

        List<Map<String, Object>> daily = new ArrayList<>();
        for (Bundle b : plugin.daily().cycle()) daily.add(bundle(b));
        m.put("daily", daily);

        Map<String, Object> boss = new LinkedHashMap<>();
        boss.put("name", WebText.plain(plugin.text().parse(plugin.configs().events().getString("boss.name", "Le Colosse"))));
        boss.put("schedule", plugin.configs().events().getStringList("boss.schedule"));
        boss.put("duration", plugin.configs().events().getInt("boss.duration-minutes", 15));
        Map<String, Object> bossRewards = new LinkedHashMap<>();
        for (String k : List.of("participation", "top1", "top2", "top3", "last-hit")) {
            bossRewards.put(k, bundle(Bundle.of(plugin.configs().events().getConfigurationSection("boss.rewards." + k))));
        }
        boss.put("rewards", bossRewards);
        m.put("boss", boss);

        Map<String, Object> season = new LinkedHashMap<>();
        season.put("points", Map.of(
                "task", plugin.configs().events().getInt("season.points.task", 10),
                "goal", plugin.configs().events().getInt("season.points.goal", 25),
                "challenge", plugin.configs().events().getInt("season.points.challenge", 60)));
        season.put("weekendMultiplier", plugin.configs().events().getDouble("season.weekend-multiplier", 2));
        Map<String, Object> seasonRewards = new LinkedHashMap<>();
        for (String k : List.of("1", "2", "3", "top10", "participation")) {
            seasonRewards.put(k, bundle(Bundle.of(plugin.configs().events().getConfigurationSection("season.rewards." + k))));
        }
        season.put("rewards", seasonRewards);
        m.put("season", season);

        Map<String, Object> eco = new LinkedHashMap<>();
        eco.put("symbol", plugin.economy().symbol());
        eco.put("plural", plugin.economy().plural());
        eco.put("start", plugin.economy().startingBalance());
        eco.put("task", plugin.configs().rewards().getLong("achievement-coins.task", 15));
        eco.put("goal", plugin.configs().rewards().getLong("achievement-coins.goal", 40));
        eco.put("challenge", plugin.configs().rewards().getLong("achievement-coins.challenge", 150));
        m.put("economy", eco);

        if (plugin.jobs().enabled()) {
            fr.lyonmc.achievements.jobs.JobService js = plugin.jobs();
            Map<String, Object> jobs = new LinkedHashMap<>();
            jobs.put("max", js.maxLevel());
            jobs.put("coinsPerLevel", js.coinsPerLevel());
            jobs.put("bonusPerLevel", js.bonusPerLevel());
            jobs.put("resourceMultiplier", js.resourceMultiplier());
            List<Long> curve = new ArrayList<>();
            for (int l : new int[]{1, 10, 25, 49}) curve.add(js.need(l));
            jobs.put("need", curve);
            List<Map<String, Object>> list = new ArrayList<>();
            for (fr.lyonmc.achievements.jobs.JobService.Job job : js.all()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", job.id());
                o.put("name", job.name());
                o.put("symbol", job.symbol());
                o.put("color", job.color().asHexString());
                o.put("icon", job.icon().getKey().getKey());
                o.put("description", String.join(" ", job.description()));
                List<Map<String, Object>> src = new ArrayList<>();
                for (Map.Entry<org.bukkit.Material, Integer> e : job.blocks().entrySet()) {
                    org.bukkit.Material mat = e.getKey();
                    src.add(Map.of("key", mat.translationKey(), "id", fr.lyonmc.achievements.jobs.JobMenu.icon(mat).getKey().getKey(),
                            "xp", e.getValue(), "ripe", fr.lyonmc.achievements.jobs.JobListenerAccess.isCrop(mat)));
                }
                for (Map.Entry<org.bukkit.Material, Integer> e : job.harvests().entrySet()) {
                    org.bukkit.Material mat = e.getKey();
                    src.add(Map.of("key", mat.translationKey(), "id", fr.lyonmc.achievements.jobs.JobMenu.icon(mat).getKey().getKey(), "xp", e.getValue()));
                }
                if (job.breed() > 0) src.add(Map.of("label", "Faire naître un animal", "id", "wheat", "xp", job.breed()));
                for (Map.Entry<String, Integer> e : job.mobs().entrySet()) {
                    String k = e.getKey();
                    if (k.equals("HOSTILE")) src.add(Map.of("label", "Autres monstres", "id", "rotten_flesh", "xp", e.getValue()));
                    else if (k.equals("PASSIVE")) src.add(Map.of("label", "Animaux", "id", "leather", "xp", e.getValue()));
                    else {
                        try {
                            org.bukkit.entity.EntityType t = org.bukkit.entity.EntityType.valueOf(k);
                            org.bukkit.Material egg = org.bukkit.Material.matchMaterial(k + "_SPAWN_EGG");
                            src.add(Map.of("key", t.translationKey(), "id", egg == null ? "bone" : egg.getKey().getKey(), "xp", e.getValue()));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                for (Map.Entry<String, Integer> e : job.fish().entrySet()) {
                    String[] l = switch (e.getKey()) {
                        case "POISSON" -> new String[]{"Poisson", "cod"};
                        case "TRESOR" -> new String[]{"Trésor (livre enchanté, selle, arc...)", "enchanted_book"};
                        default -> new String[]{"Déchet (bâton, cuir, bol...)", "stick"};
                    };
                    src.add(Map.of("label", l[0], "id", l[1], "xp", e.getValue()));
                }
                src.sort((a, b) -> Integer.compare((Integer) b.get("xp"), (Integer) a.get("xp")));
                o.put("sources", src);
                List<Map<String, Object>> ms = new ArrayList<>();
                for (Map.Entry<Integer, Bundle> e : job.milestones().entrySet()) {
                    Map<String, Object> mo = new LinkedHashMap<>();
                    mo.put("level", e.getKey());
                    mo.put("rewards", bundle(e.getValue()));
                    ms.add(mo);
                }
                o.put("milestones", ms);
                list.add(o);
            }
            jobs.put("list", list);
            m.put("jobs", jobs);
        }

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("homesBase", plugin.getConfig().getInt("teleport.homes-base", 2));
        settings.put("teamCost", plugin.teams().createCost());
        settings.put("teamMax", plugin.teams().maxMembers());
        settings.put("hdvTax", plugin.auctions().tax());
        settings.put("hdvMax", plugin.auctions().maxListings());
        settings.put("resourceBonus", plugin.resourceBonus().describe());
        settings.put("rtpCooldown", plugin.getConfig().getLong("teleport.rtp.cooldown-seconds", 600));
        m.put("settings", settings);
        return m;
    }

    /** Lot de récompenses structuré (le site l'affiche en français). */
    private Map<String, Object> bundle(Bundle b) {
        Map<String, Object> o = new LinkedHashMap<>();
        if (b.coins() > 0) o.put("coins", b.coins());
        if (b.seasonPoints() > 0) o.put("seasonPoints", b.seasonPoints());
        List<String> titles = new ArrayList<>();
        for (String t : b.titles()) {
            RewardService.TitleDef def = plugin.rewards().title(t);
            if (def != null) titles.add(WebText.html(plugin.text().parse(def.display())));
        }
        if (!titles.isEmpty()) o.put("titles", titles);
        RewardService.CosmeticDef color = plugin.rewards().cosmetic("color", b.color());
        if (color != null) o.put("color", WebText.html(plugin.rewards().colorPreview(color, color.display())));
        RewardService.CosmeticDef trail = plugin.rewards().cosmetic("trail", b.trail());
        if (trail != null) o.put("trail", trail.display());
        if (b.homes() > 0) o.put("homes", b.homes());
        if (b.xpLevels() > 0) o.put("xpLevels", b.xpLevels());
        if (b.xpPoints() > 0) o.put("xp", b.xpPoints());
        List<Map<String, Object>> items = new ArrayList<>();
        for (org.bukkit.inventory.ItemStack it : b.itemStacks()) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("key", it.getType().translationKey());
            i.put("id", it.getType().getKey().getKey());
            i.put("amount", it.getAmount());
            items.add(i);
        }
        if (!items.isEmpty()) o.put("items", items);
        return o;
    }
}
