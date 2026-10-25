package fr.lyonmc.achievements.jobs;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Configs;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;

/**
 * Métiers (Mineur, Bûcheron, Fermier, Chasseur, Pêcheur) : de l'XP en jouant, des niveaux, des pièces à chaque niveau,
 * une chance de récolte double qui grandit avec le niveau et des titres aux paliers.
 */
public final class JobService {

    public record Job(String id, String name, Material icon, TextColor color, String symbol, List<String> description,
                      Map<Material, Integer> blocks, Map<Material, Integer> harvests, int breed,
                      Map<String, Integer> mobs, Map<String, Integer> fish, Set<Material> doubles,
                      TreeMap<Integer, Bundle> milestones, BossBar.Color barColor) {

        /** Nom coloré avec son symbole : « ⛏ Mineur ». */
        public Component label() {
            return Component.text(symbol + " " + name, color);
        }
    }

    private final LyonAchievements plugin;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    /** cumulative[n] = XP totale pour atteindre le niveau n (cumulative[1] = 0). */
    private long[] cumulative = {0, 0, 0};
    private int maxLevel = 50;
    private double base = 100, mult = 15, exp = 1.6;
    private long coinsPerLevel = 20;
    private double bonusPerLevel = 0.5;
    private double resourceMultiplier = 1.5;
    private double spawnerPercent = 0;
    private boolean bar = true;
    private int announce = 10;
    private boolean enabled = true;

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, long[]> barState = new HashMap<>(); // [dernier affichage, XP gagnée pendant l'affichage]
    private final Map<UUID, String> barJob = new HashMap<>();
    private final Map<String, Double> remainders = new HashMap<>();

    public JobService(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    // ───────────────────────────── Configuration ─────────────────────────────

    public void load() {
        YamlConfiguration y = plugin.configs().jobs();
        ConfigurationSection r = Configs.section(y, "reglages");
        if (r != null) {
            enabled = r.getBoolean("actif", true);
            maxLevel = Math.max(2, r.getInt("niveau-max", 50));
            base = r.getDouble("xp-base", 100);
            mult = r.getDouble("xp-multiplicateur", 15);
            exp = r.getDouble("xp-exposant", 1.6);
            coinsPerLevel = r.getLong("pieces-par-niveau", 20);
            bonusPerLevel = r.getDouble("bonus-par-niveau", 0.5);
            resourceMultiplier = r.getDouble("multiplicateur-monde-ressources", 1.5);
            spawnerPercent = r.getDouble("cage-a-monstres", 0);
            bar = r.getBoolean("barre", true);
            announce = r.getInt("annonce", 10);
        }
        cumulative = new long[maxLevel + 2];
        for (int l = 1; l <= maxLevel; l++) cumulative[l + 1] = cumulative[l] + need(l);

        jobs.clear();
        ConfigurationSection all = Configs.section(y, "metiers");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection c = all.getConfigurationSection(id);
            if (c == null) continue;
            TextColor color = TextColor.fromHexString(c.getString("couleur", "#e6e6e6"));
            BossBar.Color barColor;
            try {
                barColor = BossBar.Color.valueOf(c.getString("barre", defaultBar(id)).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                barColor = BossBar.Color.WHITE;
            }
            TreeMap<Integer, Bundle> milestones = new TreeMap<>();
            ConfigurationSection ps = c.getConfigurationSection("paliers");
            if (ps != null) {
                for (String k : ps.getKeys(false)) {
                    try {
                        milestones.put(Integer.parseInt(k), Bundle.of(ps.getConfigurationSection(k)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            Set<Material> doubles = new HashSet<>();
            for (String s : c.getStringList("double")) {
                Material m = Material.matchMaterial(s);
                if (m != null) doubles.add(m);
            }
            jobs.put(id, new Job(id, c.getString("nom", Items.pretty(id)), Items.material(c.getString("icone"), Material.BOOK),
                    color == null ? Text.WHITE : color, c.getString("symbole", "✦"), c.getStringList("description"),
                    materials(c.getConfigurationSection("blocs")), materials(c.getConfigurationSection("recoltes")),
                    c.getInt("elevage", 0), names(c.getConfigurationSection("monstres")), names(c.getConfigurationSection("peche")),
                    doubles, milestones, barColor));
        }
    }

    private static String defaultBar(String id) {
        return switch (id) {
            case "mineur" -> "BLUE";
            case "bucheron" -> "YELLOW";
            case "fermier" -> "GREEN";
            case "chasseur" -> "RED";
            case "pecheur" -> "PURPLE";
            default -> "WHITE";
        };
    }

    private Map<Material, Integer> materials(ConfigurationSection c) {
        Map<Material, Integer> out = new LinkedHashMap<>();
        if (c == null) return out;
        for (String k : c.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m == null) plugin.getLogger().warning("metiers.yml : bloc inconnu « " + k + " »");
            else if (c.getInt(k) > 0) out.put(m, c.getInt(k));
        }
        return out;
    }

    private static Map<String, Integer> names(ConfigurationSection c) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (c == null) return out;
        for (String k : c.getKeys(false)) if (c.getInt(k) > 0) out.put(k.toUpperCase(Locale.ROOT), c.getInt(k));
        return out;
    }

    public boolean enabled() {
        return enabled && plugin.module("jobs") && !jobs.isEmpty();
    }

    public Collection<Job> all() {
        return jobs.values();
    }

    public Job get(String id) {
        return id == null ? null : jobs.get(id.toLowerCase(Locale.ROOT));
    }

    public int maxLevel() {
        return maxLevel;
    }

    public double resourceMultiplier() {
        return resourceMultiplier;
    }

    public double spawnerPercent() {
        return spawnerPercent;
    }

    public long coinsPerLevel() {
        return coinsPerLevel;
    }

    public double bonusPerLevel() {
        return bonusPerLevel;
    }

    // ───────────────────────────── Niveaux ─────────────────────────────

    /** XP pour passer du niveau n au niveau n+1. */
    public long need(int level) {
        return Math.max(1, Math.round(base + mult * Math.pow(level, exp)));
    }

    public int level(long xp) {
        int l = 1;
        while (l < maxLevel && xp >= cumulative[l + 1]) l++;
        return l;
    }

    public long xp(Profile p, String job) {
        return p == null ? 0 : p.jobs.getOrDefault(job, 0L);
    }

    public int level(Profile p, String job) {
        return level(xp(p, job));
    }

    /** XP déjà faite dans le niveau en cours. */
    public long into(long xp) {
        int l = level(xp);
        return l >= maxLevel ? 0 : xp - cumulative[l];
    }

    /** XP du niveau en cours (0 au niveau maximum). */
    public long span(long xp) {
        int l = level(xp);
        return l >= maxLevel ? 0 : need(l);
    }

    public int totalLevels(Profile p) {
        int sum = 0;
        for (Job j : jobs.values()) sum += level(p, j.id());
        return sum;
    }

    /** Chance (en %) de récolte double à ce niveau. */
    public double doubleChance(int level) {
        return Math.min(100, Math.max(0, level * bonusPerLevel));
    }

    public static String percent(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.FRANCE, "%.1f", v)) + " %";
    }

    public Map.Entry<Integer, Bundle> nextMilestone(Job job, int level) {
        return job.milestones().higherEntry(level);
    }

    // ───────────────────────────── Gagner de l'XP ─────────────────────────────

    /** Le joueur peut-il gagner de l'XP de métier ici et maintenant ? */
    public boolean canGain(Player player) {
        if (!enabled()) return false;
        GameMode m = player.getGameMode();
        if (m != GameMode.SURVIVAL && m != GameMode.ADVENTURE) return false;
        if (plugin.spawnHub() != null && plugin.spawnHub().inHub(player.getLocation())) return false;
        return !plugin.isAfk(player.getUniqueId());
    }

    public boolean inResourceWorld(World w) {
        return w != null && plugin.resources() != null && plugin.resources().enabled() && w.getName().equals(plugin.resources().name());
    }

    /** Ajoute de l'XP (avant bonus) dans un métier. */
    public void gain(Player player, Job job, double amount) {
        if (job == null || amount <= 0 || !canGain(player)) return;
        if (inResourceWorld(player.getWorld())) amount *= resourceMultiplier;
        String key = player.getUniqueId() + ":" + job.id();
        double total = remainders.getOrDefault(key, 0d) + amount;
        long whole = (long) Math.floor(total);
        remainders.put(key, total - whole);
        if (whole <= 0) return;

        Profile p = plugin.profiles().get(player);
        long before = xp(p, job.id());
        long after = before + whole;
        p.jobs.put(job.id(), after);
        p.dirty();
        int from = level(before), to = level(after);
        for (int l = from + 1; l <= to; l++) levelUp(player, job, l);
        showBar(player, job, after, whole);
    }

    private void levelUp(Player player, Job job, int level) {
        long coins = coinsPerLevel * level;
        if (coins > 0) plugin.economy().deposit(player, coins);
        TextColor accent = plugin.text().accent();
        player.showTitle(Title.title(
                Component.text(job.symbol() + " Niveau " + level, job.color()).decorate(TextDecoration.BOLD),
                Component.text(job.name() + (coins > 0 ? "  ·  +" + plugin.economy().format(coins) : ""), Text.WHITE),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2200), Duration.ofMillis(600))));
        player.playSound(player.getLocation(), level % 10 == 0 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        player.sendMessage(plugin.prefixed("<accent>✦</accent> <gray>Métier ").append(job.label())
                .append(plugin.text().parse(" <gray>: niveau <accent>" + level + "</accent> !"
                        + (coins > 0 ? " <dark_gray>(+" + plugin.economy().format(coins) + ", récolte double "
                        + percent(doubleChance(level)) + ")" : ""))));
        Bundle milestone = job.milestones().get(level);
        if (milestone != null && !milestone.isEmpty()) {
            plugin.rewards().grant(player, milestone);
            player.sendMessage(Component.text("   ", Text.DARK).append(Component.text("Palier de métier : ", accent)));
            for (Component c : plugin.rewards().describe(milestone)) player.sendMessage(Component.text("     ", Text.DARK).append(c));
        }
        if ((announce > 0 && level >= announce && level % 10 == 0) || level == maxLevel) {
            Bukkit.broadcast(plugin.text().parse("<accent>✦</accent> <white>" + player.getName() + "</white> <gray>est maintenant ")
                    .append(job.label()).append(plugin.text().parse(" <gray>niveau <accent>" + level + "</accent> !")));
        }
        // Paliers du parcours liés aux métiers (« Cumuler 25 niveaux »...)
        plugin.rewards().check(player);
        if (level == maxLevel) {
            plugin.discord().send("Métier au maximum", player.getName() + " a atteint le niveau " + level + " en " + job.name() + " !",
                    job.color().value(), player.getUniqueId());
        }
    }

    // ───────────────────────────── Barre en haut de l'écran ─────────────────────────────

    private void showBar(Player player, Job job, long xp, long gained) {
        if (!bar) return;
        UUID u = player.getUniqueId();
        long now = System.currentTimeMillis();
        long[] st = barState.computeIfAbsent(u, k -> new long[2]);
        if (!job.id().equals(barJob.put(u, job.id())) || now - st[0] > 4000) st[1] = 0;
        st[0] = now;
        st[1] += gained;
        int lvl = level(xp);
        long span = span(xp), into = into(xp);
        float progress = span == 0 ? 1f : Math.max(0f, Math.min(1f, (float) into / span));
        Component name = Component.text().append(job.label())
                .append(Component.text("  niv. " + lvl, Text.WHITE))
                .append(Component.text(span == 0 ? "  ·  niveau maximum" : "  ·  " + Text.num(into) + " / " + Text.num(span) + " XP", Text.GRAY))
                .append(Component.text("  +" + Text.num(st[1]), plugin.text().accent()))
                .build();
        BossBar b = bars.get(u);
        if (b == null) {
            b = BossBar.bossBar(name, progress, job.barColor(), BossBar.Overlay.NOTCHED_10);
            bars.put(u, b);
        } else {
            b.name(name);
            b.progress(progress);
            b.color(job.barColor());
        }
        player.showBossBar(b);
    }

    /** Toutes les secondes : cache les barres après 4 secondes sans XP. */
    public void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, long[]>> it = barState.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, long[]> e = it.next();
            if (now - e.getValue()[0] < 4000) continue;
            it.remove();
            BossBar b = bars.remove(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            if (b != null && p != null) p.hideBossBar(b);
            barJob.remove(e.getKey());
        }
    }

    public void quit(UUID uuid) {
        barState.remove(uuid);
        bars.remove(uuid);
        barJob.remove(uuid);
        remainders.keySet().removeIf(k -> k.startsWith(uuid.toString()));
    }

    // ───────────────────────────── Administration et classements ─────────────────────────────

    /** /lmc metier <joueur> <métier> <niveau> : fixe le niveau (sans récompenses). */
    public void setLevel(Profile p, Job job, int level) {
        level = Math.max(1, Math.min(maxLevel, level));
        p.jobs.put(job.id(), cumulative[level]);
        p.dirty();
    }

    /** Joueurs triés par niveau puis XP dans un métier (job = null : total des niveaux). */
    public List<Profile> top(Job job) {
        List<Profile> list = new ArrayList<>();
        for (Profile p : plugin.profiles().all()) {
            if (p.name == null) continue;
            if (job == null ? !p.jobs.isEmpty() : xp(p, job.id()) > 0) list.add(p);
        }
        if (job == null) {
            list.sort(Comparator.comparingInt(this::totalLevels).reversed()
                    .thenComparing(Comparator.comparingLong((Profile p) -> p.jobs.values().stream().mapToLong(Long::longValue).sum()).reversed()));
        } else {
            list.sort(Comparator.comparingLong((Profile p) -> xp(p, job.id())).reversed());
        }
        return list;
    }
}
