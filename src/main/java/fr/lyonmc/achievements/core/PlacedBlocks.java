package fr.lyonmc.achievements.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocs posés par des joueurs (ou fabriqués par un générateur de pierre) : ils ne font avancer ni les missions,
 * ni les métiers, et ne donnent pas de trésor. Retenus dans les données de chaque chunk, donc rien ne se perd
 * au redémarrage. Stockage : 4096 bits (512 octets) par tranche de 16 blocs de haut qui contient un bloc posé.
 */
public final class PlacedBlocks implements Listener {

    private static final int BYTES = 512;

    private final JavaPlugin plugin;
    private final Map<Integer, NamespacedKey> keys = new HashMap<>();
    private final NamespacedKey fallingKey;

    public PlacedBlocks(JavaPlugin plugin) {
        this.plugin = plugin;
        this.fallingKey = new NamespacedKey(plugin, "pose-tombe");
    }

    private NamespacedKey key(int section) {
        return keys.computeIfAbsent(section, s -> new NamespacedKey(plugin, "poses" + (s < 0 ? "m" + (-s) : String.valueOf(s))));
    }

    private static int index(Block b) {
        return ((b.getY() & 15) << 8) | ((b.getZ() & 15) << 4) | (b.getX() & 15);
    }

    /** Le bloc a-t-il été posé par un joueur ? */
    public boolean isPlaced(Block b) {
        byte[] bits = b.getChunk().getPersistentDataContainer().get(key(b.getY() >> 4), PersistentDataType.BYTE_ARRAY);
        if (bits == null || bits.length < BYTES) return false;
        int i = index(b);
        return (bits[i >> 3] & (1 << (i & 7))) != 0;
    }

    public void set(Block b, boolean placed) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        NamespacedKey k = key(b.getY() >> 4);
        byte[] bits = pdc.get(k, PersistentDataType.BYTE_ARRAY);
        if (bits == null || bits.length < BYTES) {
            if (!placed) return;
            bits = new byte[BYTES];
        }
        int i = index(b);
        boolean current = (bits[i >> 3] & (1 << (i & 7))) != 0;
        if (current == placed) return;
        if (placed) bits[i >> 3] |= (byte) (1 << (i & 7));
        else bits[i >> 3] &= (byte) ~(1 << (i & 7));
        if (!placed && empty(bits)) pdc.remove(k);
        else pdc.set(k, PersistentDataType.BYTE_ARRAY, bits);
    }

    private static boolean empty(byte[] bits) {
        for (byte b : bits) if (b != 0) return false;
        return true;
    }

    /** Cultures et pousses : elles comptent quand elles ont poussé, même si un joueur les a plantées. */
    private static boolean grows(Material m) {
        return switch (m) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, TORCHFLOWER_CROP, PITCHER_CROP,
                 MELON_STEM, PUMPKIN_STEM, SWEET_BERRY_BUSH, BAMBOO_SAPLING, CAVE_VINES, CAVE_VINES_PLANT -> true;
            default -> Tag.SAPLINGS.isTagged(m);
        };
    }

    // ───────────────────────────── Évènements ─────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e instanceof BlockMultiPlaceEvent multi) {
            for (BlockState s : multi.getReplacedBlockStates()) if (!grows(s.getBlock().getType())) set(s.getBlock(), true);
            return;
        }
        Block b = e.getBlockPlaced();
        if (!grows(b.getType())) set(b, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        // Au tick suivant : les autres plugins (missions, métiers...) doivent encore voir que le bloc était posé
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (b.getType().isAir() || b.isLiquid()) set(b, false);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        move(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        move(e.getBlocks(), e.getDirection());
    }

    /** Un bloc posé poussé par un piston reste un bloc posé. */
    private void move(List<Block> blocks, BlockFace direction) {
        List<Block> moved = new ArrayList<>();
        for (Block b : blocks) if (isPlaced(b)) moved.add(b);
        for (Block b : moved) set(b, false);
        for (Block b : moved) set(b.getRelative(direction), true);
    }

    /** Pierre, pierre taillée, obsidienne et basalte des générateurs (lave + eau) : comme des blocs posés. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        Material m = e.getNewState().getType();
        if (m == Material.COBBLESTONE || m == Material.STONE || m == Material.OBSIDIAN || m == Material.BASALT) set(e.getBlock(), true);
    }

    /** Ce qui pousse dans le vide (canne à sucre, melon...) est naturel. L'herbe qui recouvre de la terre posée ne l'est pas. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        if (e.getBlock().getType().isAir()) set(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getBlock().getType().isAir()) set(e.getBlock(), false);
    }

    /** Un arbre qui pousse est naturel, même à l'endroit où un joueur a planté la pousse. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructure(StructureGrowEvent e) {
        for (BlockState s : e.getBlocks()) set(s.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) set(b, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) set(b, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        set(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        set(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        Material m = e.getNewState().getType();
        if (m.isAir() || m == Material.WATER || m == Material.LAVA) set(e.getBlock(), false);
    }

    /** Sable et gravier qui tombent : un bloc posé le reste une fois retombé. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        Block b = e.getBlock();
        if (e.getEntity() instanceof FallingBlock falling) {
            if (e.getTo().isAir()) {
                if (isPlaced(b)) {
                    set(b, false);
                    falling.getPersistentDataContainer().set(fallingKey, PersistentDataType.BYTE, (byte) 1);
                }
            } else if (falling.getPersistentDataContainer().has(fallingKey, PersistentDataType.BYTE)) {
                set(b, true);
            }
            return;
        }
        // Bloc retiré par une créature (enderman...) : il n'existe plus
        if (e.getTo().isAir()) set(b, false);
    }
}
