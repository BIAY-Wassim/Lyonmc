package fr.lyonmc.communaute;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** communaute.json pour le site (dans le même dossier que les données de LyonAchievements). */
final class WebExport {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Communaute plugin;

    WebExport(Communaute plugin) {
        this.plugin = plugin;
    }

    private Path folder() {
        if (!plugin.lyon().getConfig().getBoolean("web.enabled", true)) return null;
        String p = plugin.lyon().getConfig().getString("web.export-path", "siteweb/data");
        Path path = Path.of(p);
        if (!path.isAbsolute()) {
            File root = plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile();
            path = root.toPath().resolve(p);
        }
        return path;
    }

    void export() {
        Path dir = folder();
        if (dir == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generated", System.currentTimeMillis());

        List<Map<String, Object>> sites = new ArrayList<>();
        for (Votes.Site s : plugin.votes().sites()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", s.name());
            o.put("url", s.url());
            sites.add(o);
        }
        Map<String, Object> votes = new LinkedHashMap<>();
        votes.put("sites", sites);
        votes.put("reward", plugin.bundle("votes.recompense").coins());
        votes.put("rewardSeason", plugin.bundle("votes.recompense").seasonPoints());
        votes.put("party", plugin.votes().partyCount());
        votes.put("partyTarget", plugin.votes().partyTarget());
        String month = plugin.month();
        List<Map<String, Object>> top = new ArrayList<>();
        for (PlayerData d : plugin.votes().top(month, 10)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", d.name);
            o.put("uuid", d.uuid.toString());
            o.put("votes", d.votesIn(month));
            top.add(o);
        }
        votes.put("top", top);
        votes.put("topReward", plugin.bundle("votes.top-mois.1").coins());
        m.put("votes", votes);

        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("enabled", plugin.getConfig().getBoolean("parrainage.enabled", true));
        ref.put("hours", plugin.getConfig().getDouble("parrainage.heures-requises", 2));
        ref.put("sponsorReward", plugin.bundle("parrainage.recompense-parrain").coins());
        ref.put("newcomerReward", plugin.bundle("parrainage.recompense-filleul").coins());
        List<Map<String, Object>> sponsors = new ArrayList<>();
        for (PlayerData d : plugin.referral().top(10)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", d.name);
            o.put("uuid", d.uuid.toString());
            o.put("count", d.filleuls);
            sponsors.add(o);
        }
        ref.put("top", sponsors);
        m.put("referral", ref);

        Goals g = plugin.goals();
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("name", g.currentName());
        goal.put("type", g.currentType());
        goal.put("progress", Math.min(g.progress(), g.target()));
        goal.put("target", g.target());
        goal.put("done", g.done());
        goal.put("endsAt", g.endsAt());
        m.put("goal", goal);

        Map<String, Object> bonus = new LinkedHashMap<>();
        bonus.put("schedule", plugin.happyHours().schedule());
        ZonedDateTime next = plugin.happyHours().next();
        bonus.put("next", next == null ? null : next.toInstant().toEpochMilli());
        var current = plugin.lyon().boosts().current();
        bonus.put("active", current != null);
        bonus.put("until", current == null ? null : current.until());
        bonus.put("coins", plugin.getConfig().getDouble("heures-bonus.pieces", 2.0));
        bonus.put("season", plugin.getConfig().getDouble("heures-bonus.saison", 2.0));
        m.put("bonus", bonus);

        Map<String, Object> welcome = new LinkedHashMap<>();
        welcome.put("finalReward", plugin.bundle("accueil.fin.recompense").coins());
        m.put("welcome", welcome);

        String json = GSON.toJson(m);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.createDirectories(dir);
                Path tmp = dir.resolve("communaute.json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, dir.resolve("communaute.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                plugin.getLogger().fine("Export communaute.json impossible : " + e.getMessage());
            }
        });
    }
}
