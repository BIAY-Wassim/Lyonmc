package fr.lyonmc.hub.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan du hub de LyonMC (sans Bukkit : testable seul), construit sur le hub médiéval (hub-medieval.schem).
 * Le schematic est collé avec son point de référence en (0, 65, 0) : c'est le spawn, au milieu de la grande
 * place pavée. On y ajoute les podiums, les hologrammes, les PNJ et un parkour long et difficile dans le ciel.
 */
public final class HubPlan {

    /** Un bloc à poser : position et état (« minecraft:gold_block », « lantern[hanging=false] »...). */
    public record Block(int x, int y, int z, String data) {
    }

    /** Un point remarquable : position (centre de bloc), regard, et ce qu'on y met. */
    public record Spot(String kind, String id, double x, double y, double z, float yaw, String arg) {
    }

    /** Le schematic du hub médiéval : nom, taille et décalage attendus. */
    public static final String SCHEM = "hub-medieval";
    public static final int SCHEM_W = 295, SCHEM_H = 92, SCHEM_L = 295;
    /** Point de référence du collage (là où se tenait celui qui a copié le hub). */
    public static final int ANCHOR_X = 0, ANCHOR_Y = 65, ANCHOR_Z = 0;
    public static final int FLOOR_Y = 64;
    /** Zone vidée avant le collage : toute l'île et le parkour du ciel. */
    public static final int MIN_X = -176, MAX_X = 122, MIN_Z = -148, MAX_Z = 150, MIN_Y = 30, MAX_Y = 160;

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

    /** Blocs ajoutés par-dessus le hub médiéval (podiums et parkour). */
    public List<Block> blocks() {
        return new ArrayList<>(blocks.values());
    }

    private static float face(double fx, double fz, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }

    public static HubPlan build() {
        HubPlan p = new HubPlan();
        p.podiums();
        p.parkour();
        p.places();
        return p;
    }

    // ───────────────────────────── Podiums (pierre taillée, sur la place) ─────────────────────────────

    private void podiums() {
        // Podium des succès au sud de la place, tourné vers le nord ; celui des métiers au nord, tourné vers le sud
        podium("succes", -12, 21, 180f);
        podium("metiers", -12, -12, 0f);
    }

    /** Trois colonnes alignées sur l'axe x : 1er au centre (3 blocs), 2e (2 blocs), 3e (1 bloc). */
    private void podium(String board, int cx, int cz, float yaw) {
        int y = FLOOR_Y + 1;
        // vu depuis la place : le 2e est à la gauche du public, le 3e à sa droite
        int left = yaw == 180f ? 1 : -1;
        int[][] pos = {{cx, 3}, {cx + left * 3, 2}, {cx - left * 3, 1}};
        String[] tops = {"gold_block", "iron_block", "waxed_copper_block"};
        for (int i = 0; i < 3; i++) {
            int x = pos[i][0], h = pos[i][1];
            for (int k = 0; k < h - 1; k++) set(x, y + k, cz, "stone_bricks");
            set(x, y + h - 1, cz, tops[i]);
            // petites marches de chaque côté
            set(x, y, cz + (yaw == 180f ? -1 : 1), "stone_brick_slab[type=bottom]");
            spots.add(new Spot("podium", "hub-podium-" + board + "-" + (i + 1), x + 0.5, y + h, cz + 0.5, yaw, board + ":" + (i + 1)));
        }
        // Lanternes sur murets aux deux bouts
        for (int s : new int[]{-6, 6}) {
            set(cx + s, y, cz, "cobblestone_wall");
            set(cx + s, y + 1, cz, "cobblestone_wall");
            set(cx + s, y + 2, cz, "lantern[hanging=false]");
        }
    }

    // ───────────────────────────── Parkour dans le ciel (long et difficile) ─────────────────────────────

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

    /** Plateforme 3×3 (étape) ou 5×5 (arrivée) à « dist » blocs devant, avec une plaque au centre et des lanternes. */
    private int[] platform(int dist, int half, String mat, String plate) {
        cx += fx * dist;
        cz += fz * dist;
        for (int a = -half; a <= half; a++) for (int b = -half; b <= half; b++) set(cx + a, cy, cz + b, mat);
        set(cx, cy + 1, cz, plate);
        set(cx + half, cy + 1, cz + half, "lantern[hanging=false]");
        set(cx - half, cy + 1, cz - half, "lantern[hanging=false]");
        return new int[]{cx, cy + 1, cz};
    }

    private void parkour() {
        // Départ : plateforme 5×5 en pierre taillée, au-dessus des toits au nord-est de la place
        int sx = 40, sy = 115, sz = -6;
        for (int x = sx - 2; x <= sx + 2; x++) for (int z = sz - 2; z <= sz + 2; z++) set(x, sy, z, (x + z & 1) == 0 ? "stone_bricks" : "mossy_stone_bricks");
        set(sx - 2, sy + 1, sz - 2, "lantern[hanging=false]");
        set(sx - 2, sy + 1, sz + 2, "lantern[hanging=false]");
        set(sx, sy + 1, sz, "light_weighted_pressure_plate");
        start = new int[]{sx, sy + 1, sz};
        spots.add(new Spot("label", "Parkour du ciel|<gray>Long et difficile : 3 étapes", sx + 0.5, sy + 3.2, sz + 0.5, 0f, ""));
        cx = sx + 2;
        cy = sy;
        cz = sz;
        turn("est");

        // 1. Échauffement : sauts de 2 blocs
        for (int i = 0; i < 8; i++) jump(3, 0, 0, "spruce_planks");
        // 2. La montée : 2 blocs de vide en montant
        for (int i = 0; i < 6; i++) jump(3, 1, 0, "mossy_cobblestone");
        checkpoints.add(platform(4, 1, "spruce_planks", "light_weighted_pressure_plate"));
        spots.add(new Spot("label", "Étape 1/3", cx + 0.5, cy + 3.2, cz + 0.5, 0f, ""));
        turn("nord");
        cx += fx;
        cz += fz;

        // 3. Les grands sauts : 3 blocs de vide (en courant)
        for (int i = 0; i < 6; i++) jump(4, 0, 0, "stone_bricks");
        // 4. Zigzag
        for (int i = 0; i < 8; i++) jump(2, 0, i % 2 == 0 ? 2 : -2, "dark_oak_planks");
        checkpoints.add(platform(4, 1, "spruce_planks", "light_weighted_pressure_plate"));
        spots.add(new Spot("label", "Étape 2/3", cx + 0.5, cy + 3.2, cz + 0.5, 0f, ""));
        turn("ouest");
        cx += fx;
        cz += fz;

        // 5. La descente : 4 blocs de vide en descendant
        for (int i = 0; i < 5; i++) jump(5, -1, 0, "hay_block");
        // 6. Virages en montant
        int[][] turns = {{3, 0, 0}, {2, 1, 2}, {3, 0, 0}, {2, 1, -2}, {3, 0, 0}, {2, 1, 2}, {3, 0, 0}};
        for (int[] t : turns) jump(t[0], t[1], t[2], "chiseled_stone_bricks");
        checkpoints.add(platform(4, 1, "spruce_planks", "light_weighted_pressure_plate"));
        spots.add(new Spot("label", "Étape 3/3", cx + 0.5, cy + 3.2, cz + 0.5, 0f, ""));
        cx += fx;
        cz += fz;

        // 7. La tour : spirale de 12 sauts vers le ciel
        int[][] spiral = {{3, 1, 0}, {0, 1, 3}, {-3, 1, 0}, {0, 1, -3}};
        for (int i = 0; i < 12; i++) jump(spiral[i % 4][0], spiral[i % 4][1], spiral[i % 4][2], "mossy_stone_bricks");
        // 8. Le final, tout en haut : 3 blocs de vide
        for (int i = 0; i < 4; i++) jump(4, 0, 0, "polished_andesite");
        finish = platform(5, 2, "gold_block", "heavy_weighted_pressure_plate");
        spots.add(new Spot("label", "<#ffd86b>Arrivée !|<gray>Bravo, tu fais partie des meilleurs", cx + 0.5, cy + 3.2, cz + 0.5, 0f, ""));
        // Classement des meilleurs temps : sur la place, près de la fontaine et du PNJ du parkour
        board = new double[]{27.5, 63.5 + 1.6, -5.5};
    }

    // ───────────────────────────── Spawn, hologrammes, PNJ (hauteurs du sol de la place) ─────────────────────────────

    private void places() {
        // Spawn au milieu de la place, tourné vers l'ouest : le grand écran est en face, au bout de la place
        spots.add(new Spot("spawn", "spawn", 0.5, 65, 0.5, 90f, ""));
        // Grand écran posé à l'ouest, regardant vers l'est (vers le spawn)
        spots.add(new Spot("holo", "hub-ecran", -26.5, 65, 0.5, -90f, "ecran"));
        spots.add(new Spot("holo", "hub-bienvenue", -7.5, 64.5, -7.5, 0f, "bienvenue"));
        spots.add(new Spot("holo", "hub-succes", -5.5, 65, 19.5, 0f, "succes"));
        spots.add(new Spot("holo", "hub-saison", 12.5, 64, -8.5, 0f, "saison"));
        spots.add(new Spot("holo", "hub-missions", 12.5, 64, 9.5, 0f, "missions"));
        spots.add(new Spot("holo", "hub-fortune", 22.5, 63.5, -8.5, 0f, "fortune"));
        spots.add(new Spot("holo", "hub-grades", 22.5, 63.5, 9.5, 0f, "grades"));
        spots.add(new Spot("npc", "hub-jouer", -4.5, 65, -3.5, face(-4.5, -3.5, 0.5, 0.5), "survie|<green><bold>Jouer|<gray>Retour en survie"));
        spots.add(new Spot("npc", "hub-guide", -4.5, 64, 4.5, face(-4.5, 4.5, 0.5, 0.5), "aide|<#e8c872><bold>Besoin d'aide ?|<gray>Clique-moi"));
        spots.add(new Spot("npc", "hub-parkour", 27.5, 63.5, 6.5, face(27.5, 6.5, 0.5, 0.5), "parkour|<#9fffe0><bold>Parkour du ciel|<gray>Long et difficile"));
    }

    /** Pour tester le plan hors du serveur : écrit les blocs ajoutés et affiche les points. */
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
