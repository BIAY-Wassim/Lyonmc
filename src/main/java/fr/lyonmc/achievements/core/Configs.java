package fr.lyonmc.achievements.core;

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

    private final JavaPlugin plugin;
    private YamlConfiguration missions;
    private YamlConfiguration rewards;
    private YamlConfiguration events;
    private YamlConfiguration state;
    private final File stateFile;

    public Configs(JavaPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "state.yml");
    }

    public void load() {
        missions = loadOrCreate("missions.yml");
        rewards = loadOrCreate("recompenses.yml");
        events = loadOrCreate("evenements.yml");
        state = stateFile.exists() ? YamlConfiguration.loadConfiguration(stateFile) : new YamlConfiguration();
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
