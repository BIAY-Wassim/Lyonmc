package fr.lyonmc.hub.noel;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * L'arène de la bataille de boules de neige : des étages ronds en neige, avec du relief, empilés les uns
 * au-dessus des autres (comme le mini-jeu Tumble des consoles). Un mur invisible fait le tour, avec une bande
 * rouge et blanche (sucre d'orge) au niveau de chaque étage. Elle est refaite, avec un nouveau relief, à chaque partie.
 */
final class SnowArena {

    final World world;
    final int cx, cy, cz, radius, floors, gap, relief;
    private final boolean ice;
    /** Blocs de neige et de glace posés (pour la fonte). */
    private final List<int[]> placed = new ArrayList<>();
    private int meltIndex;
    /** Hauteur du dessus de l'étage du haut, case par case (pour placer les joueurs au départ). */
    private int[][] topFloor;

    SnowArena(World world, int cx, int cy, int cz, int radius, int floors, int gap, int relief, boolean ice) {
        this.world = world;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.radius = Math.max(5, Math.min(40, radius));
        this.floors = Math.max(1, Math.min(6, floors));
        this.gap = Math.max(4, Math.min(20, gap));
        this.relief = Math.max(0, Math.min(4, relief));
        this.ice = ice;
    }

    int bottomY() {
        return cy - (floors - 1) * gap;
    }

    /** Sous cette hauteur, on est tombé de l'arène. */
    int eliminationY() {
        return bottomY() - relief - 4;
    }

    private int minY() {
        return Math.max(world.getMinHeight(), bottomY() - relief - 3);
    }

    private int maxY() {
        return Math.min(world.getMaxHeight() - 1, cy + relief + 7);
    }

    private double dist(int x, int z) {
        double dx = x - cx, dz = z - cz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Dans le cylindre de l'arène (mur compris, avec une marge). */
    boolean contains(Location l, double margin) {
        if (l.getWorld() == null || !l.getWorld().equals(world)) return false;
        double dx = l.getX() - (cx + 0.5), dz = l.getZ() - (cz + 0.5);
        return dx * dx + dz * dz <= (radius + 1.5 + margin) * (radius + 1.5 + margin)
                && l.getY() >= eliminationY() - 10 && l.getY() <= maxY() + 10;
    }

    /** Ce que les boules de neige cassent : un bloc des étages, dans l'arène. */
    boolean breakable(Block b) {
        Material m = b.getType();
        if (m != Material.SNOW_BLOCK && m != Material.ICE) return false;
        return b.getWorld().equals(world) && dist(b.getX(), b.getZ()) <= radius + 0.5 && b.getY() >= minY() && b.getY() <= maxY();
    }

    int remaining() {
        return placed.size() - meltIndex;
    }

    int total() {
        return placed.size();
    }

    // ───────────────────────────── Construction ─────────────────────────────

    /** Vide l'arène puis reconstruit les étages avec un nouveau relief. */
    void build(long seed) {
        Random r = new Random(seed);
        placed.clear();
        meltIndex = 0;
        int lo = minY(), hi = maxY();
        // 1. On vide le cylindre (seulement ce qui n'est pas déjà de l'air)
        for (int x = cx - radius - 2; x <= cx + radius + 2; x++) {
            for (int z = cz - radius - 2; z <= cz + radius + 2; z++) {
                if (dist(x, z) > radius + 1.6) continue;
                for (int y = lo; y <= hi; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            }
        }
        // 2. Les étages, chacun avec son propre relief
        int size = radius * 2 + 1;
        for (int f = 0; f < floors; f++) {
            int base = cy - f * gap;
            double p1 = r.nextDouble() * Math.PI * 2, p2 = r.nextDouble() * Math.PI * 2, p3 = r.nextDouble() * Math.PI * 2, p4 = r.nextDouble() * Math.PI * 2;
            double f1 = 0.34 + r.nextDouble() * 0.14, f2 = 0.30 + r.nextDouble() * 0.14;
            double q1 = r.nextDouble() * Math.PI * 2, q2 = r.nextDouble() * Math.PI * 2;
            int[][] h = new int[size][size];
            boolean[][] in = new boolean[size][size];
            boolean[][] icy = new boolean[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    int x = cx - radius + i, z = cz - radius + j;
                    in[i][j] = dist(x, z) <= radius + 0.5;
                    double n = 0.5 * Math.sin(x * f1 + p1) * Math.cos(z * f2 + p2)
                            + 0.3 * Math.sin((x + z) * 0.27 + p3)
                            + 0.2 * Math.cos((x - z) * 0.31 + p4);
                    h[i][j] = (int) Math.max(-relief, Math.min(relief, Math.round(n * relief * 1.25)));
                    // Plaques de glace (glissantes) sur les étages du bas
                    icy[i][j] = ice && f > 0 && Math.sin(x * 0.21 + q1) * Math.cos(z * 0.23 + q2) > 0.72;
                }
            }
            if (f == 0) topFloor = new int[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    if (!in[i][j]) continue;
                    // On remplit jusqu'au plus bas des voisins : pas de trou dans les pentes
                    int low = h[i][j];
                    int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] d : around) {
                        int a = i + d[0], b = j + d[1];
                        if (a >= 0 && b >= 0 && a < size && b < size && in[a][b]) low = Math.min(low, h[a][b]);
                    }
                    low = Math.max(low, h[i][j] - 2);
                    int x = cx - radius + i, z = cz - radius + j;
                    Material m = icy[i][j] ? Material.ICE : Material.SNOW_BLOCK;
                    for (int y = base + low; y <= base + h[i][j]; y++) {
                        world.getBlockAt(x, y, z).setType(m, false);
                        placed.add(new int[]{x, y, z});
                    }
                    if (f == 0) topFloor[i][j] = base + h[i][j];
                }
            }
        }
        Collections.shuffle(placed, r);
        // 3. Le tour : mur invisible, avec une bande de sucre d'orge à chaque étage
        for (int x = cx - radius - 2; x <= cx + radius + 2; x++) {
            for (int z = cz - radius - 2; z <= cz + radius + 2; z++) {
                double d = dist(x, z);
                if (d <= radius + 0.5 || d > radius + 1.6) continue;
                double angle = Math.atan2(z - cz, x - cx);
                boolean red = ((int) Math.floor((angle + Math.PI) / (Math.PI * 2) * 48)) % 2 == 0;
                for (int y = lo; y <= hi; y++) {
                    boolean band = false;
                    for (int f = 0; f < floors; f++) if (y == cy - f * gap - relief - 1) band = true;
                    Material m = band ? (red ? Material.RED_CONCRETE : Material.WHITE_CONCRETE) : Material.BARRIER;
                    world.getBlockAt(x, y, z).setType(m, false);
                }
            }
        }
    }

    /** Retire toute l'arène (étages et mur). */
    void clear() {
        for (int x = cx - radius - 2; x <= cx + radius + 2; x++) {
            for (int z = cz - radius - 2; z <= cz + radius + 2; z++) {
                if (dist(x, z) > radius + 1.6) continue;
                for (int y = minY(); y <= maxY(); y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            }
        }
        placed.clear();
        meltIndex = 0;
    }

    /** Points de départ, répartis en cercle sur l'étage du haut, tournés vers le centre. */
    List<Location> spawns(int n, Random r) {
        List<Location> out = new ArrayList<>();
        double start = r.nextDouble() * Math.PI * 2;
        double dist = radius * 0.6;
        for (int k = 0; k < n; k++) {
            double a = start + k * Math.PI * 2 / Math.max(1, n);
            int x = (int) Math.round(cx + Math.cos(a) * dist), z = (int) Math.round(cz + Math.sin(a) * dist);
            int i = x - (cx - radius), j = z - (cz - radius);
            int top = topFloor != null && i >= 0 && j >= 0 && i < topFloor.length && j < topFloor.length && topFloor[i][j] != 0
                    ? topFloor[i][j] : cy + relief;
            Location l = new Location(world, x + 0.5, top + 1, z + 0.5);
            // Regarde vers le centre
            double dx = cx + 0.5 - l.getX(), dz = cz + 0.5 - l.getZ();
            l.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            l.setPitch(10);
            out.add(l);
        }
        return out;
    }

    /** La neige fond : retire jusqu'à « count » blocs au hasard. Renvoie un bloc retiré (pour l'effet), ou null. */
    Block melt(int count) {
        Block last = null;
        while (count > 0 && meltIndex < placed.size()) {
            int[] p = placed.get(meltIndex++);
            Block b = world.getBlockAt(p[0], p[1], p[2]);
            if (b.getType() == Material.SNOW_BLOCK || b.getType() == Material.ICE) {
                b.setType(Material.AIR, false);
                last = b;
                count--;
            }
        }
        return last;
    }
}
