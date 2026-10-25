package fr.lyonmc.hub.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan du hub de LyonMC (sans Bukkit : testable seul), construit sur le hub médiéval (hub-medieval.schem).
 * Le schematic est collé avec son point de référence en (0, 65, 0) : c'est le spawn, au milieu de la grande
 * place pavée. On y ajoute les podiums, les hologrammes et les PNJ.
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
    /** Zone vidée avant le collage : toute l'île (et ce qu'il y avait au-dessus). */
    public static final int MIN_X = -176, MAX_X = 122, MIN_Z = -148, MAX_Z = 150, MIN_Y = 30, MAX_Y = 160;

    private final Map<Long, Block> blocks = new LinkedHashMap<>();
    public final List<Spot> spots = new ArrayList<>();

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private void set(int x, int y, int z, String data) {
        blocks.put(key(x, y, z), new Block(x, y, z, data.contains(":") ? data : "minecraft:" + data));
    }

    /** Blocs ajoutés par-dessus le hub médiéval (les podiums). */
    public List<Block> blocks() {
        return new ArrayList<>(blocks.values());
    }

    private static float face(double fx, double fz, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }

    public static HubPlan build() {
        HubPlan p = new HubPlan();
        p.podiums();
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
    }

    /** Pour tester le plan hors du serveur : écrit les blocs ajoutés et affiche les points. */
    public static void main(String[] args) throws Exception {
        HubPlan p = build();
        System.out.println("blocs=" + p.blocks.size());
        try (var out = new java.io.PrintWriter(args.length > 0 ? args[0] : "hub-plan.txt", java.nio.charset.StandardCharsets.UTF_8)) {
            for (Block b : p.blocks.values()) out.println(b.x() + " " + b.y() + " " + b.z() + " " + b.data());
        }
        for (Spot s : p.spots) System.out.println(s);
    }
}
