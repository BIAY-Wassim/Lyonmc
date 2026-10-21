package fr.lyonmc.achievements.teleport;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.EnumSet;
import java.util.Set;

/**
 * Hub : à chaque connexion, les joueurs arrivent au spawn (/setspawn), où sont posés les hologrammes.
 * Une zone autour du spawn est protégée (pas de casse, pas de dégâts, pas de monstres, pas de faim).
 * /back ramène là où le joueur s'était déconnecté.
 */
public final class HubService implements Listener {

    private static final Set<CreatureSpawnEvent.SpawnReason> ALLOWED_SPAWNS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.CUSTOM, CreatureSpawnEvent.SpawnReason.COMMAND,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);

    private final LyonAchievements plugin;

    private String cachedRaw;
    private Location cachedSpawn;

    public HubService(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private Location spawn() {
        String raw = plugin.configs().state().getString("spawn");
        if (raw == null) return null;
        if (!raw.equals(cachedRaw) || cachedSpawn == null || cachedSpawn.getWorld() == null) {
            cachedRaw = raw;
            cachedSpawn = plugin.teleportCommands().spawn();
        }
        return cachedSpawn;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("hub.enabled", true) && plugin.configs().state().getString("spawn") != null;
    }

    public boolean teleportsOnJoin() {
        return enabled() && plugin.module("teleport") && plugin.getConfig().getBoolean("hub.teleport-on-join", true);
    }

    private boolean protect(String what) {
        return enabled() && plugin.getConfig().getBoolean("hub.protect." + what, true);
    }

    /** Le point est-il dans la zone protégée du hub ? (cylindre autour du spawn) */
    public boolean inHub(Location l) {
        if (l == null || !enabled()) return false;
        Location spawn = spawn();
        if (spawn == null || spawn.getWorld() == null || !spawn.getWorld().equals(l.getWorld())) return false;
        double r = plugin.getConfig().getDouble("hub.radius", 48);
        double dx = l.getX() - spawn.getX();
        double dz = l.getZ() - spawn.getZ();
        return dx * dx + dz * dz <= r * r;
    }

    private static boolean bypass(Player p) {
        return p != null && p.hasPermission("lyonmc.admin");
    }

    private boolean guarded(Player p, Location l, String what) {
        return protect(what) && !bypass(p) && inHub(l);
    }

    // ───────────────────────────── Arrivée au hub ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (!teleportsOnJoin()) return;
        Player p = event.getPlayer();
        Location from = p.getLocation().clone();
        boolean returning = p.hasPlayedBefore();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || p.isDead()) return;
            Location hub = plugin.teleportCommands().spawn();
            if (returning && !inHub(from)) plugin.teleporter().remember(p.getUniqueId(), from);
            p.teleportAsync(hub);
            if (returning && !inHub(from)) {
                p.sendMessage(plugin.prefixed(plugin.getConfig().getString("hub.join-message",
                        "<gray>Bienvenue au hub ! <white><click:run_command:'/back'><hover:show_text:'<gray>Retourner où tu étais'><u>/back</u></hover></click></white> <gray>pour retourner où tu t'étais déconnecté.")));
            }
        });
    }

    // ───────────────────────────── Protection ─────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (guarded(e.getPlayer(), e.getBlock().getLocation(), "build")) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("Le hub est protégé", Text.GRAY));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (guarded(e.getPlayer(), e.getBlock().getLocation(), "build")) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("Le hub est protégé", Text.GRAY));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (guarded(e.getPlayer(), e.getBlock().getLocation(), "build")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (guarded(e.getPlayer(), e.getBlock().getLocation(), "build")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        // Piétiner les cultures
        if (e.getAction() == Action.PHYSICAL && b.getType() == Material.FARMLAND) {
            if (guarded(e.getPlayer(), b.getLocation(), "build")) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Coffres, fours, tonneaux... du hub (les portes et boutons restent utilisables)
        BlockState state = b.getState(false);
        if (state instanceof InventoryHolder && guarded(e.getPlayer(), b.getLocation(), "containers")) {
            e.setCancelled(true);
            return;
        }
        // Briquet, seau de lave... posés depuis un bloc du hub
        Material hand = e.getItem() == null ? Material.AIR : e.getItem().getType();
        if ((hand == Material.FLINT_AND_STEEL || hand == Material.FIRE_CHARGE || hand.name().endsWith("_SPAWN_EGG")
                || hand == Material.ARMOR_STAND || hand == Material.END_CRYSTAL || hand.name().endsWith("BOAT") || hand.name().endsWith("MINECART"))
                && guarded(e.getPlayer(), b.getLocation(), "build")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if ((t instanceof Hanging || t instanceof org.bukkit.entity.ArmorStand) && guarded(e.getPlayer(), t.getLocation(), "build")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (guarded(e.getPlayer(), e.getRightClicked().getLocation(), "build")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (!protect("build") || !inHub(e.getEntity().getLocation())) return;
        if (e instanceof org.bukkit.event.hanging.HangingBreakByEntityEvent be && be.getRemover() instanceof Player p && bypass(p)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (protect("explosions")) e.blockList().removeIf(b -> inHub(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (protect("explosions")) e.blockList().removeIf(b -> inHub(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (protect("fire") && inHub(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!protect("fire") || !inHub(e.getBlock().getLocation())) return;
        if (e.getPlayer() != null && bypass(e.getPlayer())) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        // Endermen, moutons, piétinement par les mobs...
        if (e.getEntity() instanceof Player p && bypass(p)) return;
        if (e.getEntity() instanceof org.bukkit.entity.FallingBlock) return;
        if (protect("build") && inHub(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !protect("damage") || !inHub(p.getLocation())) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.VOID || c == EntityDamageEvent.DamageCause.KILL) return;
        e.setCancelled(true);
        if (p.getFireTicks() > 0) p.setFireTicks(0);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHitEntity(EntityDamageByEntityEvent e) {
        // Les animaux, villageois et décorations du hub ne peuvent pas être frappés
        if (e.getEntity() instanceof Player || e.getEntity() instanceof Enemy) return;
        Player attacker = e.getDamager() instanceof Player p ? p
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker != null && guarded(attacker, e.getEntity().getLocation(), "animals")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && e.getFoodLevel() < p.getFoodLevel()
                && protect("hunger") && inHub(p.getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy) || ALLOWED_SPAWNS.contains(e.getSpawnReason())) return;
        if (protect("monsters") && inHub(e.getLocation())) e.setCancelled(true);
    }
}
