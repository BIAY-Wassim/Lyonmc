package fr.lyonmc.hub.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan du hub tout fait de LyonMC (sans Bukkit : testable seul). Le centre du hub est le bloc (0, 64, 0),
 * le spawn est juste au-dessus, tourné vers le grand écran au nord.
 * Le parkour part de la porte est et serpente dans le vide (long et difficile).
 */
public final class HubPlan {

    /** Un bloc à poser : position et état (« minecraft:gold_block », « lantern[hanging=false] »...). */
    public record Block(int x, int y, int z, String data) {
    }

    /** Un point remarquable : position (centre de bloc), regard, et ce qu'on y met. */
    public record Spot(String kind, String id, double x, double y, double z, float yaw, String arg) {
    }

    public static final int FLOOR_Y = 64;
    /** Zone vidée avant la construction (le hub et le parkour). */
    public static final int MIN_X = -60, MAX_X = 110, MIN_Z = -80, MAX_Z = 60, MIN_Y = 30, MAX_Y = 125;

    private final Map<Long, Block> blocks = new LinkedHashMap<>();
    public final List<Spot> spots = new ArrayList<>();
    /** Parkour : départ, étapes, arrivée (position des plaques = position des pieds). */
    public int[] start, finish;
    public final List<int[]> checkpoints = new ArrayList<>();
    public double[] board;

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private void set(int x, int y, int z, String data) {
        blocks.put(key(x, y, z), new Block(x, y, z, data.contains(":") ? data : "minecraft:" + data));
    }

    public List<Block> blocks() {
        return new ArrayList<>(blocks.values());
    }

    /** Petit bruit déterministe (toujours le même hub). */
    private static double noise(int x, int y, int z) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L;
        h ^= (h >>> 13);
        h *= 0x5bd1e995L;
        h ^= (h >>> 15);
        return ((h & 0xFFFF) / 65535.0);
    }

    private static float face(double fx, double fz, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }

    public static HubPlan build() {
        HubPlan p = new HubPlan();
        p.plaza();
        p.stage();
        p.podiums();
        p.gate();
        p.trees();
        p.parkour();
        p.places();
        return p;
    }

    // ───────────────────────────── Place centrale ─────────────────────────────

    private void plaza() {
        int y = FLOOR_Y;
        for (int x = -27; x <= 27; x++) {
            for (int z = -27; z <= 27; z++) {
                double d = Math.hypot(x, z);
                if (d > 25.5) continue;
                String m;
                if (x == 0 && z == 0) m = "gold_block";
                else if (d <= 2.3) m = "chiseled_quartz_block";
                else if (d >= 6 && d < 7.2) m = "smooth_sandstone";
                else if (d >= 23.3) m = "deepslate_tiles";
                else if ((Math.abs(x) <= 2 || Math.abs(z) <= 2) && d > 7.2) m = "smooth_quartz";
                else if (d >= 15 && d < 15.9) m = "polished_deepslate";
                else m = (x + z & 1) == 0 ? "polished_andesite" : "polished_diorite";
                set(x, y, z, m);
            }
        }
        // Lumières dans le sol
        for (int[] l : new int[][]{{11, 11}, {-11, 11}, {11, -11}, {-11, -11}, {0, 15}, {15, 0}, {-15, 0}, {0, -15}, {4, 4}, {-4, 4}, {4, -4}, {-4, -4}}) {
            set(l[0], y, l[1], "sea_lantern");
        }
        // Dessous rocheux de l'île flottante
        for (int yy = y - 1; yy >= y - 20; yy--) {
            int depth = y - yy;
            for (int x = -27; x <= 27; x++) {
                for (int z = -27; z <= 27; z++) {
                    double d = Math.hypot(x, z);
                    double r = 25.5 - depth * 1.32 + (noise(x, 0, z) - 0.5) * 2.4;
                    if (d > r) continue;
                    double n = noise(x, yy, z);
                    String m = depth == 1 ? "stone" : n < 0.45 ? "stone" : n < 0.65 ? "andesite" : n < 0.82 ? "tuff" : "cobbled_deepslate";
                    set(x, yy, z, m);
                }
            }
        }
        // Bordure basse et lanternes, ouverte à l'est (parkour) et au nord (écran)
        for (int x = -27; x <= 27; x++) {
            for (int z = -27; z <= 27; z++) {
                double d = Math.hypot(x, z);
                if (d <= 24.5 || d > 25.5) continue;
                if (x > 0 && Math.abs(z) <= 2) continue;          // porte est
                if (z <= -18 && Math.abs(x) <= 18) continue;      // scène de l'écran
                set(x, y + 1, z, "deepslate_tile_slab[type=bottom]");
            }
        }
        for (int a = 0; a < 360; a += 30) {
            int x = (int) Math.round(25 * Math.cos(Math.toRadians(a)));
            int z = (int) Math.round(25 * Math.sin(Math.toRadians(a)));
            if (x > 0 && Math.abs(z) <= 3) continue;
            if (z <= -18 && Math.abs(x) <= 18) continue;
            set(x, y + 1, z, "deepslate_brick_wall[up=true]");
            set(x, y + 2, z, "deepslate_brick_wall[up=true]");
            set(x, y + 3, z, "lantern[hanging=false]");
        }
    }

    // ───────────────────────────── Scène du grand écran (nord) ─────────────────────────────

    private void stage() {
        int y = FLOOR_Y;
        for (int x = -18; x <= 18; x++) {
            for (int z = -24; z <= -19; z++) set(x, y, z, z == -19 ? "polished_deepslate" : "deepslate_tiles");
            for (int yy = y - 1; yy >= y - 3; yy--) set(x, yy, -24, "cobbled_deepslate");
        }
        // Mur noir encadré derrière l'écran
        for (int x = -16; x <= 16; x++) {
            for (int yy = y + 1; yy <= y + 12; yy++) {
                boolean frame = Math.abs(x) == 16 || yy == y + 1 || yy == y + 12;
                set(x, yy, -22, frame ? "polished_blackstone_bricks" : "black_concrete");
            }
            set(x, y + 13, -22, "polished_blackstone_brick_slab[type=bottom]");
        }
        // Colonnes avec lanternes
        for (int sx : new int[]{-18, 18}) {
            for (int yy = y + 1; yy <= y + 13; yy++) set(sx, yy, -22, "deepslate_tiles");
            set(sx, y + 14, -22, "chiseled_polished_blackstone");
            set(sx, y + 15, -22, "lantern[hanging=false]");
        }
        // Ligne dorée sous l'écran
        for (int x = -15; x <= 15; x++) set(x, y + 1, -21, "smooth_sandstone_slab[type=bottom]");
        spots.add(new Spot("holo", "hub-ecran", 0.5, y + 1, -20.4, 0f, "ecran"));
    }

    // ───────────────────────────── Podiums (succès au sud, métiers à l'ouest) ─────────────────────────────

    private void podium(String board, int cx, int cz, int dx, int dz, float yaw, String backdrop) {
        int y = FLOOR_Y;
        // 1er au centre (3 blocs), 2e à gauche du public (2 blocs), 3e à droite (1 bloc)
        int[][] pos = {{cx, cz, 3}, {cx + dx * 3, cz + dz * 3, 2}, {cx - dx * 3, cz - dz * 3, 1}};
        String[] mat = {"gold_block", "iron_block", "waxed_copper_block"};
        for (int i = 0; i < 3; i++) {
            for (int h = 1; h <= pos[i][2]; h++) set(pos[i][0], y + h, pos[i][1], mat[i]);
            spots.add(new Spot("podium", "hub-" + board + "-" + (i + 1), pos[i][0] + 0.5, y + pos[i][2] + 1, pos[i][1] + 0.5, yaw, board + ":" + (i + 1)));
        }
        // Mur derrière le podium (côté opposé au public)
        // Derrière = à l'opposé du regard des PNJ
        int behindX = (int) Math.round(Math.sin(Math.toRadians(yaw))), behindZ = (int) -Math.round(Math.cos(Math.toRadians(yaw)));
        int bx = cx + behindX * 3, bz = cz + behindZ * 3;
        for (int s = -5; s <= 5; s++) {
            for (int h = 1; h <= 6; h++) {
                int x = bx + dx * s, z = bz + dz * s;
                boolean edge = Math.abs(s) == 5 || h == 6;
                set(x, y + h, z, edge ? "polished_deepslate" : backdrop);
            }
            set(bx + dx * s, y + 7, bz + dz * s, "deepslate_tile_slab[type=bottom]");
        }
    }

    private void podiums() {
        podium("succes", 0, 17, 1, 0, 180f, "yellow_terracotta");
        podium("metiers", -17, 0, 0, 1, -90f, "light_blue_terracotta");
    }

    // ───────────────────────────── Porte du parkour (est) ─────────────────────────────

    private void gate() {
        int y = FLOOR_Y;
        for (int x = 24; x <= 30; x++) {
            for (int z = -2; z <= 2; z++) set(x, y, z, Math.abs(z) == 2 ? "polished_deepslate" : "smooth_quartz");
            set(x, y - 1, 0, "polished_deepslate");
        }
        for (int s : new int[]{-3, 3}) {
            for (int h = 1; h <= 5; h++) set(24, y + h, s, "deepslate_tiles");
            set(24, y + 6, s, "lantern[hanging=false]");
        }
        for (int z = -3; z <= 3; z++) set(24, y + 5, z, "polished_deepslate");
    }

    // ───────────────────────────── Cerisiers ─────────────────────────────

    private void tree(int tx, int tz) {
        int y = FLOOR_Y;
        set(tx, y, tz, "moss_block");
        for (int h = 1; h <= 5; h++) set(tx, y + h, tz, "cherry_log");
        int cy = y + 6;
        for (int x = -3; x <= 3; x++) {
            for (int yy = -2; yy <= 2; yy++) {
                for (int z = -3; z <= 3; z++) {
                    double d = Math.sqrt(x * x + yy * yy * 1.8 + z * z);
                    if (d > 3.2 || (d > 2.4 && noise(tx + x, cy + yy, tz + z) < 0.35)) continue;
                    if (x == 0 && z == 0 && yy <= 0) continue;
                    set(tx + x, cy + yy, tz + z, "cherry_leaves[persistent=true]");
                }
            }
        }
    }

    private void trees() {
        tree(16, 16);
        tree(-15, 16);
        tree(16, -15);
        tree(-15, -15);
    }

    // ───────────────────────────── Parkour (long et difficile) ─────────────────────────────

    /** Position actuelle (bloc d'appui) et direction. */
    private int cx, cy, cz, fx, fz;

    private void turn(String dir) {
        switch (dir) {
            case "est" -> { fx = 1; fz = 0; }
            case "nord" -> { fx = 0; fz = -1; }
            case "ouest" -> { fx = -1; fz = 0; }
            default -> { fx = 0; fz = 1; }
        }
    }

    /** Saut relatif à la direction : f devant, u en hauteur, r à droite. */
    private void jump(int f, int u, int r, String mat) {
        int rx = -fz, rz = fx; // droite = rotation de 90° de la direction
        cx += fx * f + rx * r;
        cz += fz * f + rz * r;
        cy += u;
        set(cx, cy, cz, mat);
    }

    /** Plateforme 3×3 (étape) ou 5×5 (arrivée) à « dist » blocs devant, avec une plaque au centre. */
    private int[] platform(int dist, int half, String mat, String plate) {
        cx += fx * dist;
        cz += fz * dist;
        for (int a = -half; a <= half; a++) for (int b = -half; b <= half; b++) set(cx + a, cy, cz + b, mat);
        set(cx, cy + 1, cz, plate);
        return new int[]{cx, cy + 1, cz};
    }

    private void parkour() {
        int y = FLOOR_Y;
        // Départ : plateforme 5×5 au bout du pont
        for (int x = 31; x <= 35; x++) for (int z = -2; z <= 2; z++) set(x, y, z, "polished_blackstone");
        set(33, y + 1, 0, "light_weighted_pressure_plate");
        start = new int[]{33, y + 1, 0};
        spots.add(new Spot("label", "Parkour du hub|<gray>Long et difficile : 3 étapes", 33.5, y + 3.2, 0.5, 0f, ""));
        cx = 35;
        cy = y;
        cz = 0;
        turn("est");

        // 1. Échauffement : sauts de 2 blocs
        for (int i = 0; i < 8; i++) jump(3, 0, 0, "light_blue_concrete");
        // 2. La montée : 2 blocs de vide en montant
        for (int i = 0; i < 6; i++) jump(3, 1, 0, "lime_concrete");
        checkpoints.add(platform(4, 1, "polished_blackstone", "light_weighted_pressure_plate"));
        spots.add(new Spot("label", "Étape 1/3", cx + 0.5, cy + 3.2, cz + 0.5, 0f, ""));
        turn("nord");
        cx += fx;
        cz += fz;

        // 3. Les grands sauts : 3 blocs de vide (en courant)
        for (int i = 0; i < 6; i++) jump(4, 0, 0, "orange_concrete");
        // 4. Zigzag
        for (int i = 0; i < 8; i++) jump(2, 0, i % 2 == 0 ? 2 : -2, "magenta_concrete");
        checkpoints.add(platform(4, 1, "polished_blackstone", "light_weighted_pressure_plate"));
        spots.add(new Spot("label", "Étape 2/3", cx + 0.5, cy + 3.2, cz + 0.5, 0f, ""));
        turn("ouest");
        cx += fx;
        cz += fz;

        // 5. La descente : 4 blocs de vide en descendant
        for (int i = 0; i < 5; i++) jump(5, -1, 0, "yellow_concrete");
        // 6. Virages en montant
        int[][] turns = {{3, 0, 0}, {2, 1, 2}, {3, 0, 0}, {2, 1, -2}, {3, 0, 0}, {2, 1, 2}, {3, 0, 0}};
        for (int[] t : turns) jump(t[0], t[1], t[2], "red_concrete");
        checkpoints.add(platform(4, 1, "polished_blackstone", "light_weighted_pressure_plate"));
        spots.add(new Spot("label", "Étape 3/3", cx + 0.5, cy + 3.2, cz + 0.5, 0f, ""));
        cx += fx;
        cz += fz;

        // 7. La tour : spirale de 12 sauts vers le ciel
        int[][] spiral = {{3, 1, 0}, {0, 1, 3}, {-3, 1, 0}, {0, 1, -3}};
        for (int i = 0; i < 12; i++) jump(spiral[i % 4][0], spiral[i % 4][1], spiral[i % 4][2], "purple_concrete");
        // 8. Le final, tout en haut : 3 blocs de vide
        for (int i = 0; i < 4; i++) jump(4, 0, 0, "white_concrete");
        finish = platform(5, 2, "gold_block", "heavy_weighted_pressure_plate");
        spots.add(new Spot("label", "<#ffd86b>Arrivée !|<gray>Bravo, tu fais partie des meilleurs", cx + 0.5, cy + 3.2, cz + 0.5, 0f, ""));
        // Classement affiché près de la porte du parkour, dans le hub
        board = new double[]{21.5, y + 2.6, -4.5};
    }

    // ───────────────────────────── Hologrammes, PNJ, spawn ─────────────────────────────

    private void places() {
        double y = FLOOR_Y + 1;
        spots.add(new Spot("spawn", "spawn", 0.5, y, 0.5, 180f, ""));
        spots.add(new Spot("holo", "hub-bienvenue", -6.5, y, -3.5, 0f, "bienvenue"));
        spots.add(new Spot("holo", "hub-saison", 11.5, y, -10.5, 0f, "saison"));
        spots.add(new Spot("holo", "hub-missions", -10.5, y, -10.5, 0f, "missions"));
        spots.add(new Spot("holo", "hub-fortune", 11.5, y, 11.5, 0f, "fortune"));
        spots.add(new Spot("holo", "hub-grades", -10.5, y, 11.5, 0f, "grades"));
        spots.add(new Spot("holo", "hub-succes", 7.5, y, 15.5, 0f, "succes"));
        spots.add(new Spot("npc", "hub-jouer", 6.5, y, -3.5, face(6.5, -3.5, 0.5, 0.5), "survie|<green><bold>Jouer|<gray>Retour en survie"));
        spots.add(new Spot("npc", "hub-guide", -8.5, y, -0.5, face(-8.5, -0.5, 0.5, 0.5), "aide|<#e8c872><bold>Besoin d'aide ?|<gray>Clique-moi"));
        spots.add(new Spot("npc", "hub-parkour", 21.5, y, 3.5, face(21.5, 3.5, 0.5, 0.5), "parkour|<#9fffe0><bold>Parkour|<gray>Long et difficile"));
    }

    /** Pour tester le plan hors du serveur : affiche le nombre de blocs et les points. */
    public static void main(String[] args) throws Exception {
        HubPlan p = build();
        System.out.println("blocs=" + p.blocks.size());
        try (var out = new java.io.PrintWriter(args.length > 0 ? args[0] : "hub-plan.txt", java.nio.charset.StandardCharsets.UTF_8)) {
            for (Block b : p.blocks.values()) out.println(b.x() + " " + b.y() + " " + b.z() + " " + b.data());
        }
        for (Spot s : p.spots) System.out.println(s);
        System.out.println("depart=" + java.util.Arrays.toString(p.start) + " arrivee=" + java.util.Arrays.toString(p.finish));
        for (int[] c : p.checkpoints) System.out.println("etape=" + java.util.Arrays.toString(c));
    }
}
