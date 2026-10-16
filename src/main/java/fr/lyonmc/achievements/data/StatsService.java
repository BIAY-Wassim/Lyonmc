package fr.lyonmc.achievements.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.logging.Level;

public final class StatsService {

    private static final DateTimeFormatter MC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    private final JavaPlugin plugin;
    private final AdvancementRegistry registry;
    private final File file;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();

    private List<PlayerStats> ranking = List.of();
    private final Map<UUID, Integer> rankIndex = new HashMap<>();

    public StatsService(JavaPlugin plugin, AdvancementRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    // ───────────────────────────── Accès ─────────────────────────────

    public PlayerStats get(UUID uuid) {
        return stats.get(uuid);
    }

    public PlayerStats getOrCreate(UUID uuid, String name) {
        PlayerStats s = stats.computeIfAbsent(uuid, u -> new PlayerStats(u, name));
        s.name(name);
        return s;
    }

    public PlayerStats findByName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return stats.get(online.getUniqueId());
        for (PlayerStats s : stats.values()) {
            if (s.name() != null && s.name().equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    public Collection<PlayerStats> all() {
        return stats.values();
    }

    // ───────────────────────────── Calcul ─────────────────────────────

    /** Relit la progression réelle d'un joueur connecté. */
    public PlayerStats refreshOnline(Player player) {
        PlayerStats s = getOrCreate(player.getUniqueId(), player.getName());
        Map<String, Long> done = s.done();

        for (AdvancementRegistry.Entry entry : registry.entries()) {
            AdvancementProgress progress = player.getAdvancementProgress(entry.advancement());
            if (progress.isDone()) {
                long time = 0L;
                for (String criteria : progress.getAwardedCriteria()) {
                    Date d = progress.getDateAwarded(criteria);
                    if (d != null && d.getTime() > time) time = d.getTime();
                }
                Long previous = done.get(entry.key());
                if (time == 0L) time = previous != null ? previous : System.currentTimeMillis();
                done.put(entry.key(), time);
            } else {
                done.remove(entry.key());
            }
        }
        s.recompute(registry);
        return s;
    }

    public void recomputeAll() {
        for (PlayerStats s : stats.values()) s.recompute(registry);
    }

    /** Recalcule le classement. Retourne true si l'ordre a changé. */
    public boolean rebuildRanking() {
        List<PlayerStats> list = new ArrayList<>();
        for (PlayerStats s : stats.values()) {
            if (s.count() > 0 && s.name() != null) list.add(s);
        }
        Comparator<PlayerStats> byCount = Comparator.comparingInt((PlayerStats s) -> s.count()).reversed();
        Comparator<PlayerStats> byChallenges = Comparator.comparingInt((PlayerStats s) -> s.challenges()).reversed();
        list.sort(byCount
                .thenComparing(byChallenges)
                .thenComparingLong((PlayerStats s) -> s.lastTime() == 0 ? Long.MAX_VALUE : s.lastTime())
                .thenComparing((PlayerStats s) -> s.name(), String.CASE_INSENSITIVE_ORDER));

        boolean changed = list.size() != ranking.size();
        if (!changed) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) != ranking.get(i)) { changed = true; break; }
            }
        }
        ranking = List.copyOf(list);
        rankIndex.clear();
        for (int i = 0; i < ranking.size(); i++) rankIndex.put(ranking.get(i).uuid(), i + 1);
        return changed;
    }

    public List<PlayerStats> ranking() {
        return ranking;
    }

    /** Rang (1 = premier), 0 si non classé. */
    public int rank(UUID uuid) {
        return rankIndex.getOrDefault(uuid, 0);
    }

    public PlayerStats top(int position) {
        return position >= 1 && position <= ranking.size() ? ranking.get(position - 1) : null;
    }

    /** Pourcentage des joueurs classés possédant ce succès. */
    public double rarity(String key) {
        if (ranking.isEmpty()) return 0;
        int have = 0;
        for (PlayerStats s : ranking) if (s.hasDone(key)) have++;
        return have * 100.0 / ranking.size();
    }

    // ───────────────────────────── Import monde ─────────────────────────────

    /** Lit world/advancements/*.json pour intégrer les joueurs hors-ligne au classement. */
    public void importFromWorld(IntConsumer onDone) {
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (main == null) {
            onDone.accept(0);
            return;
        }
        File dir = new File(main.getWorldFolder(), "advancements");
        Set<String> counted = new HashSet<>();
        for (AdvancementRegistry.Entry e : registry.entries()) counted.add(e.key());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, Map<String, Long>> parsed = new HashMap<>();
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(f.getName().substring(0, f.getName().length() - 5));
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    try (Reader reader = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        Map<String, Long> done = new HashMap<>();
                        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                            if (!counted.contains(e.getKey()) || !e.getValue().isJsonObject()) continue;
                            JsonObject obj = e.getValue().getAsJsonObject();
                            if (!obj.has("done") || !obj.get("done").getAsBoolean()) continue;
                            long time = 0L;
                            if (obj.has("criteria") && obj.get("criteria").isJsonObject()) {
                                for (Map.Entry<String, JsonElement> c : obj.getAsJsonObject("criteria").entrySet()) {
                                    try {
                                        long t = ZonedDateTime.parse(c.getValue().getAsString(), MC_DATE).toInstant().toEpochMilli();
                                        if (t > time) time = t;
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                            done.put(e.getKey(), time);
                        }
                        parsed.put(uuid, done);
                    } catch (Exception ex) {
                        plugin.getLogger().log(Level.FINE, "Lecture impossible : " + f.getName(), ex);
                    }
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Map.Entry<UUID, Map<String, Long>> e : parsed.entrySet()) {
                    if (Bukkit.getPlayer(e.getKey()) != null) continue; // les connectés sont déjà à jour
                    OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
                    PlayerStats existing = stats.get(e.getKey());
                    String name = op.getName() != null ? op.getName() : existing != null ? existing.name() : null;
                    if (name == null) continue;
                    PlayerStats s = getOrCreate(e.getKey(), name);
                    s.done().keySet().removeIf(registry::isCounted);
                    s.done().putAll(e.getValue());
                    s.recompute(registry);
                }
                rebuildRanking();
                onDone.accept(parsed.size());
            });
        });
    }

    // ───────────────────────────── Sauvegarde ─────────────────────────────

    public void load() {
        stats.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String id : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(id);
                ConfigurationSection sec = players.getConfigurationSection(id);
                if (sec == null) continue;
                PlayerStats s = new PlayerStats(uuid, sec.getString("name"));
                s.sidebar(sec.getBoolean("sidebar", true));
                for (String line : sec.getStringList("done")) {
                    int sep = line.lastIndexOf('|');
                    if (sep <= 0) continue;
                    try {
                        s.done().put(line.substring(0, sep), Long.parseLong(line.substring(sep + 1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                s.recompute(registry);
                stats.put(uuid, s);
            } catch (IllegalArgumentException ignored) {
            }
        }
        rebuildRanking();
    }

    private YamlConfiguration snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (PlayerStats s : stats.values()) {
            String base = "players." + s.uuid();
            yaml.set(base + ".name", s.name());
            yaml.set(base + ".sidebar", s.sidebar());
            yaml.set(base + ".count", s.count());
            List<String> done = new ArrayList<>(s.done().size());
            for (Map.Entry<String, Long> e : s.done().entrySet()) done.add(e.getKey() + "|" + e.getValue());
            Collections.sort(done);
            yaml.set(base + ".done", done);
        }
        return yaml;
    }

    public void saveAsync() {
        String data = snapshot().saveToString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
    }

    public void saveSync() {
        write(snapshot().saveToString());
    }

    private synchronized void write(String data) {
        try {
            File dir = file.getParentFile();
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Impossible de créer " + dir);
            File tmp = new File(dir, "data.yml.tmp");
            Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Sauvegarde de data.yml impossible", ex);
        }
    }
}
