package fr.lyonmc.achievements.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Fichiers de configuration additionnels (missions.yml, recompenses.yml, evenements.yml)
 * et fichier d'état du serveur (state.yml).
 */
public final class Configs {

    /**
     * Section d'un fichier de config, en prenant celle du .jar si le fichier du serveur ne l'a pas encore
     * (Bukkit renverrait sinon une section vide, sans les valeurs par défaut).
     */
    public static org.bukkit.configuration.ConfigurationSection section(org.bukkit.configuration.Configuration root, String path) {
        if (root.isSet(path)) return root.getConfigurationSection(path);
        org.bukkit.configuration.Configuration defaults = root.getDefaults();
        return defaults == null ? null : defaults.getConfigurationSection(path);
    }

    /**
     * Ajoute (en mémoire, sans toucher au fichier) les éléments du .jar absents d'une section du serveur :
     * sinon un nouveau titre ou une nouvelle mission n'apparaîtrait jamais sur un serveur déjà installé.
     */
    static void mergeChildren(YamlConfiguration yaml, String... paths) {
        org.bukkit.configuration.Configuration def = yaml.getDefaults();
        if (def == null) return;
        for (String path : paths) {
            if (!yaml.isSet(path)) continue; // la section entière vient déjà du .jar
            org.bukkit.configuration.ConfigurationSection mine = yaml.getConfigurationSection(path);
            org.bukkit.configuration.ConfigurationSection theirs = def.getConfigurationSection(path);
            if (mine == null || theirs == null) continue;
            java.util.Set<String> have = mine.getKeys(false);
            for (String k : theirs.getKeys(false)) {
                if (have.contains(k)) continue;
                Object v = theirs.get(k);
                if (v instanceof org.bukkit.configuration.ConfigurationSection s) copy(s, mine.createSection(k));
                else mine.set(k, v);
            }
        }
    }

    private static void copy(org.bukkit.configuration.ConfigurationSection from, org.bukkit.configuration.ConfigurationSection to) {
        for (String k : from.getKeys(false)) {
            Object v = from.get(k);
            if (v instanceof org.bukkit.configuration.ConfigurationSection s) copy(s, to.createSection(k));
            else to.set(k, v);
        }
    }

    private final JavaPlugin plugin;
    private YamlConfiguration missions;
    private YamlConfiguration rewards;
    private YamlConfiguration events;
    private YamlConfiguration jobs;
    private YamlConfiguration news;
    private YamlConfiguration state;
    private final File stateFile;

    public Configs(JavaPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "state.yml");
    }

    public void load() {
        missions = loadOrCreate("missions.yml");
        rewards = loadOrCreate("recompenses.yml");
        // Les nouveautés des mises à jour (titres, couleurs, paliers, missions...) s'ajoutent aux anciens fichiers
        mergeChildren(rewards, "titles", "colors", "trails", "paliers", "grades");
        mergeChildren(missions, "daily.easy", "daily.medium", "daily.hard", "weekly");
        events = loadOrCreate("evenements.yml");
        upgradeBoss();
        jobs = loadOrCreate("metiers.yml");
        news = loadOrCreate("actualites.yml");
        mergeChildren(jobs, "metiers");
        state = stateFile.exists() ? YamlConfiguration.loadConfiguration(stateFile) : new YamlConfiguration();
    }

    /**
     * Boss plus dur (7.1) : ajoute les nouveaux réglages du boss dans evenements.yml (avec leurs commentaires)
     * et retire les anciens (vie limitée à 1024).
     */
    private void upgradeBoss() {
        org.bukkit.configuration.Configuration def = events.getDefaults();
        ConfigurationSection boss = events.getConfigurationSection("boss");
        if (def == null || boss == null || events.isSet("boss.vie-base")) return;
        for (String k : new String[]{"vie-base", "vie-par-joueur", "degats-distance", "phases", "gardiens-par-joueur", "gardiens-max",
                "gardien-vie", "sbire-vie", "sbires-max", "xp-boss", "xp-gardien", "xp-sbire"}) {
            String path = "boss." + k;
            if (events.isSet(path) || !def.isSet(path)) continue;
            events.set(path, def.get(path));
            if (def instanceof YamlConfiguration y) events.setComments(path, y.getComments(path));
        }
        events.set("boss.health-base", null);
        events.set("boss.health-per-player", null);
        try {
            events.save(new File(plugin.getDataFolder(), "evenements.yml"));
            plugin.getLogger().info("Boss mondial : nouveaux réglages ajoutés à evenements.yml (vie, phases, gardiens, XP).");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible de mettre à jour evenements.yml", e);
        }
    }

    private YamlConfiguration loadOrCreate(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // Valeurs par défaut (pour les clés absentes d'un ancien fichier)
        try (Reader r = new InputStreamReader(plugin.getResource(name), StandardCharsets.UTF_8)) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(r));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Valeurs par défaut introuvables pour " + name, e);
        }
        return yaml;
    }

    public YamlConfiguration missions() {
        return missions;
    }

    public YamlConfiguration rewards() {
        return rewards;
    }

    public YamlConfiguration events() {
        return events;
    }

    /** actualites.yml (sans valeurs par défaut : la liste est celle du fichier) */
    public YamlConfiguration news() {
        return news;
    }

    public void saveNews() {
        try {
            news.save(new File(plugin.getDataFolder(), "actualites.yml"));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible d'enregistrer actualites.yml", e);
        }
    }

    /** metiers.yml */
    public YamlConfiguration jobs() {
        return jobs;
    }

    /** État persistant du serveur (saison en cours, spawn, hologrammes...). */
    public YamlConfiguration state() {
        return state;
    }

    public void saveState() {
        try {
            state.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible d'enregistrer state.yml", e);
        }
    }
}
