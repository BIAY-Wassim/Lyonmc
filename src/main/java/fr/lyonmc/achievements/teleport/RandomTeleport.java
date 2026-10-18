package fr.lyonmc.achievements.teleport;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Recherche d'un point sûr aléatoire (chargement des chunks en asynchrone). */
public final class RandomTeleport {

    private RandomTeleport() {
    }

    public static void find(JavaPlugin plugin, World world, int minRadius, int maxRadius, Consumer<Location> callback) {
        attempt(plugin, world, minRadius, maxRadius, 12, callback);
    }

    private static void attempt(JavaPlugin plugin, World world, int min, int max, int triesLeft, Consumer<Location> callback) {
        if (triesLeft <= 0) {
            callback.accept(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location center = world.getSpawnLocation();
        WorldBorder border = world.getWorldBorder();
        double limit = Math.min(max, border.getSize() / 2 - 16);
        double lower = Math.min(min, Math.max(0, limit - 16));
        double angle = r.nextDouble() * Math.PI * 2;
        double dist = lower + r.nextDouble() * Math.max(1, limit - lower);
        int x = (int) (center.getBlockX() + Math.cos(angle) * dist);
        int z = (int) (center.getBlockZ() + Math.sin(angle) * dist);
        world.getChunkAtAsync(x >> 4, z >> 4, true, chunk -> {
            Location found = check(world, x, z);
            if (found != null) callback.accept(found);
            else attempt(plugin, world, min, max, triesLeft - 1, callback);
        });
    }

    private static Location check(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            for (int y = 32; y < 110; y++) {
                Block feet = world.getBlockAt(x, y, z);
                if (feet.getType().isAir() && feet.getRelative(0, 1, 0).getType().isAir() && safeGround(feet.getRelative(0, -1, 0))) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
            return null;
        }
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block ground = world.getBlockAt(x, y, z);
        if (!safeGround(ground)) return null;
        if (y < world.getSeaLevel() - 1) return null;
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    private static boolean safeGround(Block b) {
        Material m = b.getType();
        if (!m.isSolid() || b.isLiquid()) return false;
        return switch (m) {
            case LAVA, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, POWDER_SNOW, SWEET_BERRY_BUSH, FIRE, SOUL_FIRE,
                 POINTED_DRIPSTONE, BEDROCK -> false;
            default -> true;
        };
    }
}
