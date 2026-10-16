package fr.lyonmc.achievements.menu;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Récupère les vrais skins des joueurs (API Mojang, via Paper) pour les têtes du classement,
 * même quand le joueur est hors ligne. Les profils sont mis en cache.
 */
public final class HeadService implements org.bukkit.event.Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Set<UUID> failed = ConcurrentHashMap.newKeySet();

    public HeadService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Mémorise le skin d'un joueur connecté. */
    public void remember(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        if (profile.hasTextures()) {
            cache.put(player.getUniqueId(), profile.clone());
            failed.remove(player.getUniqueId());
        }
    }

    @org.bukkit.event.EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        remember(event.getPlayer());
    }

    /** Précharge les skins d'une liste de joueurs (ex : le top 10). */
    public void preload(java.util.List<fr.lyonmc.achievements.data.PlayerStats> players) {
        failed.clear();
        for (fr.lyonmc.achievements.data.PlayerStats ps : players) load(ps.uuid(), ps.name(), null);
    }

    public boolean hasSkin(UUID uuid) {
        return cache.containsKey(uuid);
    }

    /** Tête du joueur : avec son skin si connu, sinon tête par défaut (le skin sera chargé). */
    public ItemStack head(UUID uuid, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return item;
        PlayerProfile profile = cache.get(uuid);
        if (profile == null) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.getPlayerProfile().hasTextures()) {
                remember(online);
                profile = cache.get(uuid);
            }
        }
        meta.setPlayerProfile(profile != null ? profile : Bukkit.createProfile(uuid, name));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Charge le skin en arrière-plan (requête à l'API Mojang), puis appelle onReady sur le thread principal.
     * Ne fait rien si le skin est déjà connu ou déjà en cours de chargement.
     */
    public void load(UUID uuid, String name, Runnable onReady) {
        if (cache.containsKey(uuid) || failed.contains(uuid) || !pending.add(uuid)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerProfile found = null;
            try {
                PlayerProfile byId = Bukkit.createProfile(uuid, name);
                if (byId.complete(true) && byId.hasTextures()) found = byId;
            } catch (Exception ignored) {
            }
            if (found == null && name != null) {
                // Serveur en mode hors-ligne : on cherche le compte Mojang par pseudo
                try {
                    PlayerProfile byName = Bukkit.createProfile(name);
                    if (byName.complete(true, true) && byName.hasTextures()) found = byName;
                } catch (Exception ignored) {
                }
            }
            if (found != null) cache.put(uuid, found);
            else failed.add(uuid);
            pending.remove(uuid);
            if (found != null && onReady != null) Bukkit.getScheduler().runTask(plugin, onReady);
        });
    }
}
