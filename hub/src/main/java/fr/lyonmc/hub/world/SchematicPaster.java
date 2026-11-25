package fr.lyonmc.hub.world;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Colle un fichier .schem (format Sponge, comme WorldEdit) petit à petit, sans bloquer le serveur.
 * Les clôtures, murets, vitres et escaliers sont ensuite raccordés entre eux.
 */
public final class SchematicPaster {

    public record Schem(String name, int width, int height, int length, BlockData[] palette, char[] blocks,
                        int offsetX, int offsetY, int offsetZ, int nonAir, List<Head> heads) {
    }

    /** Tête décorative du schematic (position dans le fichier et texture du skin). */
    public record Head(int x, int y, int z, java.util.UUID id, String texture) {
    }

    private final JavaPlugin plugin;
    private BukkitTask running;

    public SchematicPaster(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public File folder() {
        File f = new File(plugin.getDataFolder(), "schematics");
        if (!f.isDirectory()) f.mkdirs();
        return f;
    }

    public List<String> available() {
        List<String> out = new ArrayList<>();
        File[] files = folder().listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".schem"));
        if (files != null) for (File f : files) out.add(f.getName().substring(0, f.getName().length() - 6));
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    public boolean busy() {
        return running != null;
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    public Schem load(String name) throws IOException {
        File file = new File(folder(), name + ".schem");
        if (!file.isFile()) throw new IOException("Fichier introuvable : plugins/HUB/schematics/" + name + ".schem");
        Map<String, Object> root = Nbt.read(file);
        if (root.size() == 1 && root.get("Schematic") instanceof Map<?, ?> inner) root = (Map<String, Object>) inner; // format v3
        int w = num(root.get("Width")) & 0xFFFF, h = num(root.get("Height")) & 0xFFFF, l = num(root.get("Length")) & 0xFFFF;
        Map<String, Object> palette;
        byte[] data;
        if (root.get("Blocks") instanceof Map<?, ?> blocks) { // v3
            palette = (Map<String, Object>) blocks.get("Palette");
            data = (byte[]) blocks.get("Data");
        } else { // v2
            palette = (Map<String, Object>) root.get("Palette");
            data = (byte[]) root.get("BlockData");
        }
        if (palette == null || data == null || w * h * l <= 0) throw new IOException("Format de schematic non reconnu (il faut un .schem de WorldEdit)");
        int max = 0;
        for (Object v : palette.values()) max = Math.max(max, num(v));
        if (max >= Character.MAX_VALUE) throw new IOException("Palette trop grande");
        BlockData[] states = new BlockData[max + 1];
        int unknown = 0;
        for (Map.Entry<String, Object> e : palette.entrySet()) {
            BlockData bd = parse(e.getKey());
            if (bd == null) unknown++;
            states[num(e.getValue())] = bd;
        }
        if (unknown > 0) plugin.getLogger().warning(unknown + " types de blocs inconnus ignorés dans " + name);
        char[] blocks = new char[w * h * l];
        int i = 0, pos = 0, nonAir = 0;
        while (pos < data.length && i < blocks.length) {
            int v = 0, shift = 0, b;
            do {
                b = data[pos++];
                v |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && pos < data.length);
            blocks[i++] = (char) v;
            BlockData bd = v < states.length ? states[v] : null;
            if (bd != null && !bd.getMaterial().isAir()) nonAir++;
        }
        int ox = 0, oy = 0, oz = 0;
        if (root.get("Metadata") instanceof Map<?, ?> meta) {
            ox = num(meta.get("WEOffsetX"));
            oy = num(meta.get("WEOffsetY"));
            oz = num(meta.get("WEOffsetZ"));
        }
        return new Schem(name, w, h, l, states, blocks, ox, oy, oz, nonAir, heads(root));
    }

    /** Têtes avec un skin (ex. : Head Database) : ancien format (SkullOwner) et nouveau (profile). */
    @SuppressWarnings("unchecked")
    private static List<Head> heads(Map<String, Object> root) {
        Object list = root.get("BlockEntities");
        if (list == null && root.get("Blocks") instanceof Map<?, ?> b) list = b.get("BlockEntities"); // v3
        List<Head> out = new ArrayList<>();
        if (!(list instanceof List<?> entities)) return out;
        for (Object o : entities) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> e = (Map<String, Object>) raw;
            if (!(e.get("Pos") instanceof int[] pos) || pos.length < 3) continue;
            Map<String, Object> data = e.get("Data") instanceof Map<?, ?> d ? (Map<String, Object>) d : e;
            String texture = null;
            java.util.UUID id = null;
            if (data.get("SkullOwner") instanceof Map<?, ?> owner) {
                id = uuid(owner.get("Id"));
                if (owner.get("Properties") instanceof Map<?, ?> props && props.get("textures") instanceof List<?> t && !t.isEmpty()
                        && t.get(0) instanceof Map<?, ?> first) texture = (String) first.get("Value");
            } else if (data.get("profile") instanceof Map<?, ?> profile) {
                id = uuid(profile.get("id"));
                if (profile.get("properties") instanceof List<?> props) {
                    for (Object p : props) {
                        if (p instanceof Map<?, ?> m && "textures".equals(m.get("name"))) texture = (String) m.get("value");
                    }
                }
            }
            if (texture != null && !texture.isBlank()) out.add(new Head(pos[0], pos[1], pos[2], id == null ? java.util.UUID.randomUUID() : id, texture));
        }
        return out;
    }

    private static java.util.UUID uuid(Object o) {
        if (o instanceof int[] a && a.length == 4) {
            return new java.util.UUID((long) a[0] << 32 | a[1] & 0xFFFFFFFFL, (long) a[2] << 32 | a[3] & 0xFFFFFFFFL);
        }
        if (o instanceof String s) {
            try {
                return java.util.UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Pose la texture d'une tête déjà collée. */
    private static void applyHead(World world, int x, int y, int z, Head h) {
        if (!(world.getBlockAt(x, y, z).getState() instanceof org.bukkit.block.Skull skull)) return;
        com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(h.id());
        profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", h.texture()));
        skull.setPlayerProfile(profile);
        skull.update(true, false);
    }

    private static BlockData parse(String state) {
        // Blocs renommés depuis les anciennes versions (schematics plus vieux que le serveur)
        if (state.equals("minecraft:grass") || state.startsWith("minecraft:grass[")) state = "minecraft:short_grass" + state.substring(15);
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException e) {
            int b = state.indexOf('[');
            if (b < 0) return null;
            try {
                return Bukkit.createBlockData(state.substring(0, b));
            } catch (IllegalArgumentException e2) {
                return null;
            }
        }
    }

    private static boolean connects(BlockData d) {
        return d instanceof MultipleFacing || d instanceof Wall || d instanceof Stairs || d instanceof Chest || d instanceof RedstoneWire;
    }

    /**
     * Colle le schematic : son point de référence (là où se tenait celui qui l'a copié) arrive
     * sous les pieds du joueur. Les blocs d'air du fichier ne remplacent rien.
     */
    public void paste(Player player, Schem s, boolean removeStartPlatform) {
        paste(player, s, player.getLocation(), removeStartPlatform, null);
    }

    /**
     * Enlève un schematic collé (coin en baseX, baseY, baseZ) : seuls les blocs du monde identiques à ceux du fichier
     * sont retirés, petit à petit. « done » reçoit le nombre de blocs retirés.
     */
    public void unpaste(Schem s, World world, int baseX, int baseY, int baseZ, java.util.function.Consumer<Long> done) {
        int[] y = {0};
        long[] removed = {0};
        running = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long deadline = System.nanoTime() + 25_000_000L;
            while (System.nanoTime() < deadline && y[0] < s.height()) {
                int wy = baseY + y[0];
                if (wy >= world.getMinHeight() && wy < world.getMaxHeight()) {
                    for (int z = 0; z < s.length(); z++) {
                        int row = (y[0] * s.length() + z) * s.width();
                        for (int x = 0; x < s.width(); x++) {
                            int id = s.blocks()[row + x];
                            BlockData bd = id < s.palette().length ? s.palette()[id] : null;
                            if (bd == null || bd.getMaterial().isAir()) continue;
                            org.bukkit.block.Block b = world.getBlockAt(baseX + x, wy, baseZ + z);
                            if (b.getType() == bd.getMaterial()) {
                                b.setType(Material.AIR, false);
                                removed[0]++;
                            }
                        }
                    }
                }
                y[0]++;
            }
            if (y[0] >= s.height()) {
                running.cancel();
                running = null;
                done.accept(removed[0]);
            }
        }, 1L, 1L);
    }

    /** Colle le schematic avec son point de référence en « at » ; « done » est lancé à la fin (sinon : message au joueur). */
    public void paste(Player player, Schem s, Location at, boolean removeStartPlatform, Runnable done) {
        World world = at.getWorld();
        int baseX = at.getBlockX() + s.offsetX(), baseY = at.getBlockY() + s.offsetY(), baseZ = at.getBlockZ() + s.offsetZ();
        if (baseY < world.getMinHeight() || baseY + s.height() > world.getMaxHeight()) {
            player.sendMessage(Component.text("Le schematic dépasse les limites de hauteur du monde ici : monte ou descends un peu.", NamedTextColor.RED));
            return;
        }
        int cx0 = baseX >> 4, cz0 = baseZ >> 4, cx1 = (baseX + s.width() - 1) >> 4, cz1 = (baseZ + s.length() - 1) >> 4;
        List<int[]> chunks = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) chunks.add(new int[]{cx, cz});
        List<long[]> toShape = new ArrayList<>();
        long[] placed = {0};
        int[] chunkIndex = {0};
        int[] shapeIndex = {0};
        int[] headIndex = {0};
        long[] lastReport = {0};
        ServerLevel level = ((CraftWorld) world).getHandle();

        running = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long deadline = System.nanoTime() + 25_000_000L; // 25 ms par tick au maximum
            while (System.nanoTime() < deadline) {
                if (chunkIndex[0] < chunks.size()) {
                    int[] c = chunks.get(chunkIndex[0]++);
                    world.addPluginChunkTicket(c[0], c[1], plugin);
                    int minX = Math.max(baseX, c[0] << 4), maxX = Math.min(baseX + s.width() - 1, (c[0] << 4) + 15);
                    int minZ = Math.max(baseZ, c[1] << 4), maxZ = Math.min(baseZ + s.length() - 1, (c[1] << 4) + 15);
                    for (int y = 0; y < s.height(); y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            int row = (y * s.length() + (z - baseZ)) * s.width();
                            for (int x = minX; x <= maxX; x++) {
                                int id = s.blocks()[row + (x - baseX)];
                                BlockData bd = id < s.palette().length ? s.palette()[id] : null;
                                if (bd == null || bd.getMaterial() == Material.AIR) continue;
                                world.getBlockAt(x, baseY + y, z).setBlockData(bd, false);
                                placed[0]++;
                                if (connects(bd)) toShape.add(new long[]{x, baseY + y, z});
                            }
                        }
                    }
                } else if (shapeIndex[0] < toShape.size()) {
                    long[] p = toShape.get(shapeIndex[0]++);
                    BlockPos pos = new BlockPos((int) p[0], (int) p[1], (int) p[2]);
                    BlockState st = level.getBlockState(pos);
                    BlockState ns = Block.updateFromNeighbourShapes(st, level, pos);
                    if (ns != st) level.setBlock(pos, ns, 2 | 16);
                } else if (headIndex[0] < s.heads().size()) {
                    Head h = s.heads().get(headIndex[0]++);
                    try {
                        applyHead(world, baseX + h.x(), baseY + h.y(), baseZ + h.z(), h);
                    } catch (RuntimeException e) {
                        plugin.getLogger().fine("Tête non posée : " + e.getMessage());
                    }
                } else {
                    if (removeStartPlatform) {
                        // Plateforme de départ (0, 64, 0) : on enlève ce que la construction n'a pas recouvert
                        for (int x = -4; x <= 4; x++) {
                            for (int z = -4; z <= 4; z++) {
                                int sx = x - baseX, sy = 64 - baseY, sz = z - baseZ;
                                boolean inside = sx >= 0 && sy >= 0 && sz >= 0 && sx < s.width() && sy < s.height() && sz < s.length();
                                int id = inside ? s.blocks()[(sy * s.length() + sz) * s.width() + sx] : 0;
                                BlockData bd = id < s.palette().length ? s.palette()[id] : null;
                                boolean covered = inside && bd != null && !bd.getMaterial().isAir();
                                if (!covered && world.getBlockAt(x, 64, z).getType() == Material.SMOOTH_STONE) {
                                    world.getBlockAt(x, 64, z).setType(Material.AIR, false);
                                }
                            }
                        }
                    }
                    for (int[] c : chunks) world.removePluginChunkTicket(c[0], c[1], plugin);
                    running.cancel();
                    running = null;
                    plugin.getLogger().info("Schematic " + s.name() + " collé : " + placed[0] + " blocs"
                            + (s.heads().isEmpty() ? "." : ", " + s.heads().size() + " têtes décorées."));
                    if (done != null) {
                        done.run();
                        return;
                    }
                    if (player.isOnline()) {
                        player.sendMessage(Component.text("✦ Collage terminé : " + String.format(Locale.FRANCE, "%,d", placed[0])
                                + " blocs. Mets-toi au point d'arrivée et tape /setspawn.", NamedTextColor.GREEN));
                    }
                    return;
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastReport[0] > 2000 && player.isOnline()) {
                lastReport[0] = now;
                int pct = chunkIndex[0] < chunks.size() ? (int) (chunkIndex[0] * 95L / chunks.size())
                        : 95 + (int) (shapeIndex[0] * 5L / Math.max(1, toShape.size()));
                player.sendActionBar(Component.text("Collage du hub : " + pct + " %", NamedTextColor.GOLD));
            }
        }, 1L, 1L);
    }
}
