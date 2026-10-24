package fr.lyonmc.communaute;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Données des joueurs (joueurs.yml) et du serveur (etat.yml). */
public final class Store {

    private final Communaute plugin;
    private final File playersFile;
    private final File stateFile;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private YamlConfiguration state;

    public Store(Communaute plugin) {
        this.plugin = plugin;
        this.playersFile = new File(plugin.getDataFolder(), "joueurs.yml");
        this.stateFile = new File(plugin.getDataFolder(), "etat.yml");
    }

    public void load() {
        players.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(playersFile);
        for (String k : y.getKeys(false)) {
            ConfigurationSection c = y.getConfigurationSection(k);
            if (c == null) continue;
            try {
                UUID u = UUID.fromString(k);
                players.put(u, PlayerData.load(u, c));
            } catch (IllegalArgumentException ignored) {
            }
        }
        state = YamlConfiguration.loadConfiguration(stateFile);
        if (state.getString("sel") == null) {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            state.set("sel", HexFormat.of().formatHex(salt));
            saveState();
        }
    }

    public YamlConfiguration state() {
        return state;
    }

    public void saveState() {
        try {
            state.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'enregistrer etat.yml", e);
        }
    }

    /** Enregistre tout : synchrone (arrêt) ou en arrière-plan. */
    public void save(boolean async) {
        YamlConfiguration y = new YamlConfiguration();
        for (PlayerData d : players.values()) d.save(y.createSection(d.uuid.toString()));
        String text = y.saveToString();
        Runnable write = () -> {
            try {
                java.nio.file.Files.writeString(playersFile.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Impossible d'enregistrer joueurs.yml", e);
            }
        };
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        else write.run();
        saveState();
    }

    public PlayerData get(UUID uuid) {
        return players.get(uuid);
    }

    public PlayerData getOrCreate(UUID uuid, String name) {
        return players.computeIfAbsent(uuid, u -> {
            PlayerData d = new PlayerData(u, name);
            d.firstSeen = System.currentTimeMillis();
            return d;
        });
    }

    public PlayerData get(Player p) {
        PlayerData d = getOrCreate(p.getUniqueId(), p.getName());
        d.name = p.getName();
        return d;
    }

    public Collection<PlayerData> all() {
        return players.values();
    }

    /** Empreinte de l'adresse IP (on ne garde jamais l'adresse elle-même). */
    public String hashIp(Player p) {
        if (p.getAddress() == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(state.getString("sel", "").getBytes(StandardCharsets.UTF_8));
            md.update(p.getAddress().getAddress().getHostAddress().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 20);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
