package fr.lyonmc.hub.player;

import fr.lyonmc.hub.world.HubWorlds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Les joueurs au hub : leur inventaire de survie est mis de côté (jamais supprimé) dans
 * plugins/HUB/inventaires/, ils reçoivent les objets du hub et passent en mode aventure.
 * En quittant le hub, tout leur est rendu (objets, armure, expérience, vie, faim, effets, mode de jeu).
 */
public final class HubPlayers implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final HubWorlds worlds;
    private final HubMenus menus;
    private final NamespacedKey itemKey;
    private final File folder;
    private final Map<UUID, Location> returnPoints = new HashMap<>();
    private final Set<UUID> builders = new HashSet<>();
    private final Set<UUID> switching = new HashSet<>();

    public HubPlayers(JavaPlugin plugin, HubWorlds worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.itemKey = new NamespacedKey(plugin, "objet");
        this.folder = new File(plugin.getDataFolder(), "inventaires");
        if (!folder.isDirectory()) folder.mkdirs();
        this.menus = new HubMenus(plugin, this);
    }

    public HubMenus menus() {
        return menus;
    }

    public boolean inHub(Player p) {
        return worlds.isHub(p.getWorld());
    }

    private File file(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    public boolean building(Player p) {
        return builders.contains(p.getUniqueId());
    }

    // ───────────────────────────── Entrée et sortie du hub ─────────────────────────────

    /** Met l'inventaire de survie de côté (s'il ne l'est pas déjà) puis donne les objets du hub. */
    public void enter(Player p, Location returnPoint) {
        File f = file(p.getUniqueId());
        if (!f.exists()) {
            if (!save(p, returnPoint, f)) {
                p.sendMessage(Component.text("Ton inventaire n'a pas pu être mis de côté : il reste avec toi.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
        } else if (returnPoint != null) {
            updateReturnPoint(f, returnPoint);
        }
        applyHubState(p);
    }

    /** Rend l'inventaire de survie (si le joueur en avait un de côté). */
    public void leave(Player p) {
        builders.remove(p.getUniqueId());
        File f = file(p.getUniqueId());
        if (!f.exists()) {
            removeHubItems(p);
            if (p.getGameMode() == GameMode.ADVENTURE) setMode(p, GameMode.SURVIVAL);
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        PlayerInventory inv = p.getInventory();
        List<String> items = y.getStringList("objets");
        ItemStack[] contents = new ItemStack[inv.getContents().length];
        for (int i = 0; i < contents.length && i < items.size(); i++) {
            String s = items.get(i);
            if (s == null || s.isEmpty()) continue;
            try {
                contents[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Objet illisible dans " + f.getName() + " (case " + i + ") : le fichier est gardé.", e);
                p.sendMessage(Component.text("Un objet n'a pas pu être rendu : préviens un admin (fichier gardé).", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
        }
        inv.clear();
        inv.setContents(contents);
        p.setLevel(y.getInt("niveau"));
        p.setExp((float) y.getDouble("experience"));
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        double health = y.getDouble("vie", 20);
        p.setHealth(Math.max(1, Math.min(max == null ? 20 : max.getValue(), health)));
        p.setFoodLevel(y.getInt("faim", 20));
        p.setSaturation((float) y.getDouble("saturation", 5));
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        List<?> effects = y.getList("effets");
        if (effects != null) for (Object o : effects) if (o instanceof PotionEffect e) p.addPotionEffect(e);
        GameMode mode;
        try {
            mode = GameMode.valueOf(y.getString("mode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            mode = GameMode.SURVIVAL;
        }
        setMode(p, mode);
        // Le fichier n'est supprimé qu'une fois tout rendu
        if (!f.delete()) plugin.getLogger().warning("Impossible de supprimer " + f.getName());
    }

    private boolean save(Player p, Location returnPoint, File f) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("pseudo", p.getName());
        List<String> items = new ArrayList<>();
        for (ItemStack it : p.getInventory().getContents()) {
            items.add(it == null || it.getType().isAir() || isHubItem(it) ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        }
        y.set("objets", items);
        y.set("niveau", p.getLevel());
        y.set("experience", (double) p.getExp());
        y.set("vie", p.getHealth());
        y.set("faim", p.getFoodLevel());
        y.set("saturation", (double) p.getSaturation());
        y.set("effets", new ArrayList<>(p.getActivePotionEffects()));
        y.set("mode", (p.getGameMode() == GameMode.ADVENTURE ? GameMode.SURVIVAL : p.getGameMode()).name());
        writeLocation(y, returnPoint);
        try {
            y.save(f);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible d'enregistrer l'inventaire de " + p.getName(), e);
            return false;
        }
    }

    private static void writeLocation(YamlConfiguration y, Location l) {
        if (l == null || l.getWorld() == null) return;
        y.set("retour.monde", l.getWorld().getName());
        y.set("retour.x", l.getX());
        y.set("retour.y", l.getY());
        y.set("retour.z", l.getZ());
        y.set("retour.yaw", (double) l.getYaw());
        y.set("retour.pitch", (double) l.getPitch());
    }

    private void updateReturnPoint(File f, Location l) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        writeLocation(y, l);
        try {
            y.save(f);
        } catch (IOException ignored) {
        }
    }

    /** Endroit où le joueur était en survie (là où « Jouer » le ramène), ou null. */
    public Location returnPoint(Player p) {
        File f = file(p.getUniqueId());
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            World w = Bukkit.getWorld(y.getString("retour.monde", ""));
            if (w != null && !worlds.isHub(w)) {
                return new Location(w, y.getDouble("retour.x"), y.getDouble("retour.y"), y.getDouble("retour.z"),
                        (float) y.getDouble("retour.yaw"), (float) y.getDouble("retour.pitch"));
            }
        }
        Location mem = returnPoints.get(p.getUniqueId());
        return mem != null && mem.getWorld() != null && !worlds.isHub(mem.getWorld()) ? mem : null;
    }

    private void applyHubState(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        p.setLevel(0);
        p.setExp(0);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        if (building(p)) {
            setMode(p, GameMode.CREATIVE);
            return;
        }
        setMode(p, GameMode.ADVENTURE);
        giveHubItems(p);
    }

    private void setMode(Player p, GameMode mode) {
        switching.add(p.getUniqueId());
        try {
            p.setGameMode(mode);
        } finally {
            switching.remove(p.getUniqueId());
        }
    }

    // ───────────────────────────── Objets du hub ─────────────────────────────

    public boolean isHubItem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.STRING);
    }

    private String action(ItemStack it) {
        return it == null || !it.hasItemMeta() ? null : it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    private void removeHubItems(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isHubItem(inv.getItem(i))) inv.setItem(i, null);
    }

    public void giveHubItems(Player p) {
        var sec = plugin.getConfig().getConfigurationSection("objets");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            var c = sec.getConfigurationSection(key);
            if (c == null) continue;
            Material m = Material.matchMaterial(c.getString("objet", "STONE"));
            if (m == null || m.isAir()) continue;
            ItemStack it = new ItemStack(m);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(MM.deserialize(c.getString("nom", key)).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            for (String l : c.getStringList("description")) lore.add(MM.deserialize(l).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, c.getString("action", "menu"));
            if (meta instanceof SkullMeta skull && m == Material.PLAYER_HEAD) skull.setPlayerProfile(p.getPlayerProfile());
            it.setItemMeta(meta);
            int slot = c.getInt("case", 0);
            if (slot >= 0 && slot < 36) p.getInventory().setItem(slot, it);
        }
        p.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, plugin.getConfig().getInt("case-en-main", 4))));
    }

    /** Mode construction (admins) : créatif au hub, sans les objets du hub. */
    public void toggleBuild(Player p) {
        if (!inHub(p)) {
            p.sendMessage(MM.deserialize("<gray>Va d'abord au hub."));
            return;
        }
        if (builders.remove(p.getUniqueId())) {
            applyHubState(p);
            p.sendMessage(MM.deserialize("<gray>Mode construction <red>désactivé</red>."));
        } else {
            builders.add(p.getUniqueId());
            applyHubState(p);
            p.sendMessage(MM.deserialize("<gray>Mode construction <green>activé</green> : créatif, inventaire libre. Ton inventaire de survie reste de côté."));
        }
    }

    // ───────────────────────────── Évènements ─────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        // On retient où le joueur était en survie avant d'arriver au hub
        if (!worlds.isHub(e.getFrom().getWorld()) && worlds.isHub(e.getTo().getWorld())) {
            returnPoints.put(e.getPlayer().getUniqueId(), e.getFrom().clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!worlds.isHub(p.getWorld()) && worlds.isHub(e.getRespawnLocation().getWorld())) {
            returnPoints.put(p.getUniqueId(), p.getLocation().clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        boolean fromHub = worlds.isHub(e.getFrom()), toHub = worlds.isHub(p.getWorld());
        if (toHub && !fromHub) enter(p, returnPoints.remove(p.getUniqueId()));
        else if (fromHub && !toHub) leave(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (inHub(p)) {
            enter(p, null);
        } else if (file(p.getUniqueId()).exists()) {
            // Déconnecté au hub puis arrivé ailleurs : on rend l'inventaire tout de suite
            leave(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (switching.contains(p.getUniqueId()) || !inHub(p) || building(p)) return;
        // Au hub on reste en aventure (sauf mode construction)
        if (e.getNewGameMode() != GameMode.ADVENTURE && e.getNewGameMode() != GameMode.SPECTATOR) {
            e.setCancelled(true);
            p.sendMessage(MM.deserialize("<gray>Au hub, tout le monde est en aventure. <white>/lyonhub construire</white> pour construire."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (!inHub(e.getEntity())) return;
        e.getDrops().clear();
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inHub(p) || building(p)) return;
        String action = action(e.getItem());
        if (action == null) return;
        e.setCancelled(true);
        if (e.getAction() == Action.PHYSICAL) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        menus.run(p, action);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !inHub(p) || building(p)) return;
        if (e.getInventory().getHolder() instanceof HubMenus.Holder) return; // géré par les menus
        if (e.getClickedInventory() == p.getInventory() || e.isShiftClick() || isHubItem(e.getCurrentItem()) || isHubItem(e.getCursor())) {
            e.setCancelled(true);
            String action = action(e.getCurrentItem());
            if (action != null) Bukkit.getScheduler().runTask(plugin, () -> menus.run(p, action));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && inHub(p) && !building(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        if (inHub(e.getPlayer()) && !building(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (inHub(e.getPlayer()) && !building(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && inHub(p) && !building(p)) e.setCancelled(true);
    }

    /** À l'arrêt du serveur : tout le monde récupère son inventaire (rien ne reste coincé si le plugin est retiré). */
    public void restoreAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (file(p.getUniqueId()).exists()) leave(p);
        }
    }
}
