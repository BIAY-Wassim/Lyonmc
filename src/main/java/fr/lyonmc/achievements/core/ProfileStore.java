package fr.lyonmc.achievements.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Profils des joueurs : un fichier YAML par joueur dans plugins/LyonAchievements/profiles/. */
public final class ProfileStore {

    private final JavaPlugin plugin;
    private final File folder;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();

    public ProfileStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "profiles");
    }

    public void load() {
        profiles.clear();
        if (!folder.exists()) return;
        File[] files = folder.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            try {
                UUID uuid = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                profiles.put(uuid, Profile.load(uuid, YamlConfiguration.loadConfiguration(f)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        plugin.getLogger().info(profiles.size() + " profils joueurs chargés.");
    }

    public Profile get(UUID uuid) {
        return profiles.get(uuid);
    }

    public Profile get(Player player) {
        return getOrCreate(player.getUniqueId(), player.getName());
    }

    public Profile getOrCreate(UUID uuid, String name) {
        Profile p = profiles.computeIfAbsent(uuid, u -> {
            Profile np = new Profile(u, name);
            np.dirty();
            return np;
        });
        if (name != null && !name.equals(p.name)) {
            p.name = name;
            p.dirty();
        }
        return p;
    }

    public Profile byName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return get(online);
        for (Profile p : profiles.values()) {
            if (p.name != null && p.name.equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    public Collection<Profile> all() {
        return profiles.values();
    }

    // ───────────────────────────── Sauvegarde ─────────────────────────────

    /** Sérialise sur le thread principal, écrit en asynchrone. */
    public void saveAsync() {
        Map<UUID, String> data = snapshot(false);
        if (data.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
    }

    public void saveSync() {
        write(snapshot(true));
    }

    private Map<UUID, String> snapshot(boolean all) {
        Map<UUID, String> out = new HashMap<>();
        for (Profile p : profiles.values()) {
            if (!all && !p.isDirty()) continue;
            out.put(p.uuid(), p.save().saveToString());
            p.clean();
        }
        return out;
    }

    private synchronized void write(Map<UUID, String> data) {
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().severe("Impossible de créer " + folder);
            return;
        }
        for (Map.Entry<UUID, String> e : data.entrySet()) {
            File file = new File(folder, e.getKey() + ".yml");
            File tmp = new File(folder, e.getKey() + ".yml.tmp");
            try {
                Files.writeString(tmp.toPath(), e.getValue(), StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Sauvegarde du profil " + e.getKey() + " impossible", ex);
            }
        }
    }
}
