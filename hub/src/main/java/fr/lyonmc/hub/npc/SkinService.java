package fr.lyonmc.hub.npc;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Skins des joueurs (textures signées Mojang), gardés en cache une journée dans skins.yml. */
public final class SkinService {

    public record Skin(String texture, String signature, long fetched) {
    }

    private static final long MAX_AGE = 24L * 3600_000L;

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Skin> cache = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();

    public SkinService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "skins.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s != null) cache.put(k, new Skin(s.getString("texture"), s.getString("signature"), s.getLong("fetched")));
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Skin> e : cache.entrySet()) {
            y.set(e.getKey() + ".texture", e.getValue().texture());
            y.set(e.getKey() + ".signature", e.getValue().signature());
            y.set(e.getKey() + ".fetched", e.getValue().fetched());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'enregistrer skins.yml : " + e.getMessage());
        }
    }

    private static String key(UUID uuid, String name) {
        return uuid != null ? uuid.toString() : "nom-" + name.toLowerCase(Locale.ROOT);
    }

    /** Skin en cache (même un peu ancien), sinon null. */
    public Skin cached(UUID uuid, String name) {
        return cache.get(key(uuid, name));
    }

    /**
     * Donne le skin au callback (sur le thread principal). Utilise le cache s'il a moins d'un jour,
     * le profil du joueur s'il est connecté, sinon interroge Mojang en arrière-plan.
     */
    public void get(UUID uuid, String name, Consumer<Skin> callback) {
        String k = key(uuid, name);
        Skin c = cache.get(k);
        if (c != null && System.currentTimeMillis() - c.fetched() < MAX_AGE) {
            callback.accept(c);
            return;
        }
        Player online = uuid != null ? Bukkit.getPlayer(uuid) : Bukkit.getPlayerExact(name);
        if (online != null) {
            Skin s = fromProfile(online.getPlayerProfile());
            if (s != null) {
                cache.put(k, s);
                callback.accept(s);
                return;
            }
        }
        if (!loading.add(k)) {
            if (c != null) callback.accept(c);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Skin s = null;
            try {
                PlayerProfile p = uuid != null ? Bukkit.createProfile(uuid, name) : Bukkit.createProfile(name);
                if (p.complete(true)) s = fromProfile(p);
                if (s == null && uuid != null && name != null) {
                    // Serveur hors ligne ou UUID inconnu de Mojang : on essaie avec le pseudo
                    PlayerProfile byName = Bukkit.createProfile(name);
                    if (byName.complete(true)) s = fromProfile(byName);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().fine("Skin introuvable pour " + name + " : " + e.getMessage());
            }
            Skin result = s != null ? s : c;
            if (s != null) cache.put(k, s);
            Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(k);
                callback.accept(result);
            });
        });
    }

    private static Skin fromProfile(PlayerProfile p) {
        for (ProfileProperty prop : p.getProperties()) {
            if (prop.getName().equals("textures")) {
                return new Skin(prop.getValue(), prop.getSignature(), System.currentTimeMillis());
            }
        }
        return null;
    }
}
