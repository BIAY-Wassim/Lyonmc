package fr.lyonmc.achievements.noel;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.core.StoredLocation;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Chasse au trésor : tous les N jours, un coffre de cadeaux est caché dans le monde survie (zones déjà explorées),
 * avec de faux coffres pièges autour. Quatre quêtes donnent des indices de plus en plus précis.
 * Chaque joueur qui trouve le coffre a son cadeau, le premier a un bonus.
 */
public final class TreasureHunt implements Listener {

    private static final TextColor GOLD = TextColor.color(0xFFD86B);
    private static final long DAY = 86_400_000L;
    private static final Set<Material> GROUND = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL,
            Material.MYCELIUM, Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.STONE, Material.SNOW_BLOCK, Material.MOSS_BLOCK,
            Material.MUD, Material.CLAY, Material.TERRACOTTA, Material.SANDSTONE, Material.ROOTED_DIRT, Material.DIRT_PATH,
            Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF, Material.CALCITE, Material.PACKED_ICE);
    private static final Set<Material> FISH = EnumSet.of(Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH);

    private final LyonAchievements plugin;
    private final NoelService noel;
    /** Positions du vrai coffre et des pièges (« monde:x:y:z »), pour reconnaître les clics vite. */
    private String chestKey;
    private final List<String> trapKeys = new ArrayList<>();
    private boolean searching;

    TreasureHunt(LyonAchievements plugin, NoelService noel) {
        this.plugin = plugin;
        this.noel = noel;
        loadKeys();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = noel.cfg().getConfigurationSection("tresor");
        return c == null ? new org.bukkit.configuration.MemoryConfiguration() : c;
    }

    private org.bukkit.configuration.file.YamlConfiguration data() {
        return noel.data();
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private Block block(String key) {
        String[] p = key.split(":");
        World w = Bukkit.getWorld(p[0]);
        return w == null ? null : w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    private void loadKeys() {
        chestKey = data().getString("tresor.coffre");
        trapKeys.clear();
        trapKeys.addAll(data().getStringList("tresor.pieges"));
    }

    public boolean running() {
        return chestKey != null;
    }

    private boolean isTreasureBlock(Block b) {
        if (chestKey == null) return false;
        String k = key(b);
        return k.equals(chestKey) || trapKeys.contains(k);
    }

    // ───────────────────────────── Planning ─────────────────────────────

    private boolean enabled() {
        if (!plugin.module("noel") || !cfg().getBoolean("enabled", true)) return false;
        return !cfg().getBoolean("noel-seulement", true) || noel.active();
    }

    private long period() {
        return Math.max(1, cfg().getInt("tous-les-jours", 5)) * DAY;
    }

    /** Début du créneau en cours (-1 s'il n'y en a pas). */
    private long slotStart() {
        LocalTime at;
        try {
            at = LocalTime.parse(cfg().getString("heure", "18:00"));
        } catch (RuntimeException e) {
            at = LocalTime.of(18, 0);
        }
        boolean xmasOnly = cfg().getBoolean("noel-seulement", true);
        if (xmasOnly && !noel.active()) return -1; // hors de la période de Noël : pas de créneau en cours
        LocalDate first = xmasOnly ? noel.periodStart(noel.today()) : LocalDate.of(2026, 1, 1);
        long base = ZonedDateTime.of(first, at, plugin.text().zone()).toInstant().toEpochMilli();
        long now = noel.now();
        if (now < base) return -1;
        return base + (now - base) / period() * period();
    }

    /** Prochain créneau (pour l'annonce quand aucun trésor n'est caché). */
    private long nextSlot() {
        long s = slotStart();
        if (s >= 0) return s + period();
        LocalTime at;
        try {
            at = LocalTime.parse(cfg().getString("heure", "18:00"));
        } catch (RuntimeException e) {
            at = LocalTime.of(18, 0);
        }
        LocalDate start = noel.periodStart(noel.today());
        if (!start.isAfter(noel.today())) start = start.plusYears(1);
        return ZonedDateTime.of(start, at, plugin.text().zone()).toInstant().toEpochMilli();
    }

    /** Chaque minute. */
    public void tick() {
        if (!enabled()) {
            if (running()) end(true);
            return;
        }
        long now = noel.now();
        if (running() && now >= data().getLong("tresor.fin")) end(true);
        long slot = slotStart();
        if (slot < 0 || searching) return;
        if (data().getLong("tresor.creneau", -1) != slot) start(slot, slot + period());
    }

    // ───────────────────────────── Nouveau trésor ─────────────────────────────

    /** Cache un nouveau trésor (créneau, fin). Le précédent disparaît. */
    public void start(long slot, long end) {
        if (searching) return;
        World w = Bukkit.getWorld(cfg().getString("monde", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        World world = w;
        if (running()) end(false);
        searching = true;
        data().set("tresor.creneau", slot);
        noel.markDirty();
        int min = Math.max(32, cfg().getInt("distance-min", 250)), max = Math.max(min + 16, cfg().getInt("distance-max", 1200));
        Location spawn = world.getSpawnLocation();
        findSpot(world, spawn.getBlockX(), spawn.getBlockZ(), min, max, 40, chest -> {
            if (chest == null) {
                searching = false;
                plugin.getLogger().warning("Chasse au trésor : aucun endroit trouvé (zones explorées entre " + min + " et " + max
                        + " blocs du spawn). Nouvel essai dans une minute.");
                data().set("tresor.creneau", null);
                return;
            }
            place(chest, data().getInt("tresor.numero") + 1, end);
        });
    }

    private void place(Block chest, int number, long end) {
        World w = chest.getWorld();
        data().set("tresor.numero", number);
        data().set("tresor.debut", noel.now());
        data().set("tresor.fin", end);
        data().set("tresor.trouve", null);
        data().set("tresor.noms", null);
        data().set("tresor.progres", null);
        data().set("tresor.farces", null);
        data().set("tresor.avant", null);
        putChest(chest, "Trésor de Noël");
        data().set("tresor.coffre", key(chest));
        // Indices : des cercles de plus en plus petits qui contiennent le coffre
        List<String> zones = new ArrayList<>();
        for (int r : cfg().getIntegerList("zones").isEmpty() ? List.of(400, 120, 30) : cfg().getIntegerList("zones")) {
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), d = ThreadLocalRandom.current().nextDouble() * r * 0.6;
            zones.add(r + ":" + (int) Math.round(chest.getX() + Math.cos(a) * d) + ":" + (int) Math.round(chest.getZ() + Math.sin(a) * d));
        }
        data().set("tresor.zones", zones);
        List<Integer> quests = new ArrayList<>();
        for (int step = 1; step <= 4; step++) {
            int n = cfg().getMapList("quetes." + step).size();
            quests.add(n == 0 ? 0 : ThreadLocalRandom.current().nextInt(n));
        }
        data().set("tresor.quetes", quests);
        data().set("tresor.pieges", new ArrayList<String>());
        loadKeys();
        noel.markDirty();
        noel.saveIfDirty();
        plugin.getLogger().info("Chasse au trésor n°" + number + " : coffre en " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
                + " (" + w.getName() + ").");
        placeTraps(chest, Math.max(0, cfg().getInt("pieges", 2)));
        Bukkit.broadcast(plugin.prefixed("<#ffd86b>✦</#ffd86b> <white>Un trésor de Noël est caché dans le monde !</white> <gray>Réussis les quêtes pour savoir où : "
                + "<click:run_command:'/tresor'><hover:show_text:'<gray>Voir ta quête et tes indices'><white><u>/tresor</u></white></hover></click>"));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.2f);
        plugin.discord().send("Chasse au trésor", "Un nouveau trésor de Noël est caché sur LyonMC ! Tape /tresor en jeu pour ta première quête.", 0xFFD86B, null);
        searching = false;
    }

    private void placeTraps(Block chest, int left) {
        if (left <= 0) return;
        findSpot(chest.getWorld(), chest.getX(), chest.getZ(), 10, 35, 15, trap -> {
            if (trap != null && !isTreasureBlock(trap)) {
                putChest(trap, "Trésor de Noël");
                trapKeys.add(key(trap));
                data().set("tresor.pieges", new ArrayList<>(trapKeys));
                noel.markDirty();
            }
            placeTraps(chest, left - 1);
        });
    }

    private void putChest(Block b, String name) {
        data().set("tresor.avant." + key(b).replace(':', '_'), b.getBlockData().getAsString());
        b.setType(Material.CHEST, false);
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Chest cd) {
            BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
            cd.setFacing(faces[ThreadLocalRandom.current().nextInt(faces.length)]);
            b.setBlockData(cd, false);
        }
        if (b.getState() instanceof org.bukkit.block.Chest st) {
            st.customName(Component.text(name, GOLD));
            st.update(true, false);
        }
        b.getWorld().spawnParticle(Particle.SNOWFLAKE, b.getLocation().add(0.5, 1, 0.5), 20, 0.4, 0.4, 0.4, 0.01);
    }

    /** Cherche un endroit en surface, dans une zone déjà générée, entre min et max blocs du centre (chunks chargés à côté). */
    private void findSpot(World w, int cx, int cz, int min, int max, int tries, Consumer<Block> done) {
        if (tries <= 0) {
            done.accept(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble(Math.PI * 2), d = min + r.nextDouble() * (max - min);
        int x = (int) Math.round(cx + Math.cos(a) * d), z = (int) Math.round(cz + Math.sin(a) * d);
        double border = w.getWorldBorder().getSize() / 2 - 16;
        Location center = w.getWorldBorder().getCenter();
        if (Math.abs(x - center.getX()) > border || Math.abs(z - center.getZ()) > border || !w.isChunkGenerated(x >> 4, z >> 4)) {
            Bukkit.getScheduler().runTask(plugin, () -> findSpot(w, cx, cz, min, max, tries - 1, done));
            return;
        }
        w.getChunkAtAsync(x >> 4, z >> 4, false).thenAccept(chunk -> {
            Block spot = chunk == null ? null : surface(w, x, z);
            if (spot != null) done.accept(spot);
            else findSpot(w, cx, cz, min, max, tries - 1, done);
        });
    }

    private Block surface(World w, int x, int z) {
        Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (!GROUND.contains(top.getType()) || plugin.placed().isPlaced(top)) return null;
        Block spot = top.getRelative(BlockFace.UP), above = spot.getRelative(BlockFace.UP);
        if (!(spot.getType().isAir() || spot.isReplaceable()) || spot.isLiquid() || !above.isPassable() || above.isLiquid()) return null;
        int far = Math.max(0, cfg().getInt("loin-des-homes", 80));
        for (Profile p : plugin.profiles().all()) {
            for (StoredLocation h : p.homes.values()) {
                if (!h.world().equals(w.getName())) continue;
                double dx = h.x() - x, dz = h.z() - z;
                if (dx * dx + dz * dz < (double) far * far) return null;
            }
        }
        return spot;
    }

    /** Fin du trésor : les coffres disparaissent (le monde reprend sa forme d'avant). */
    public void end(boolean announce) {
        int number = data().getInt("tresor.numero");
        List<String> found = data().getStringList("tresor.trouve");
        List<String> all = new ArrayList<>(trapKeys);
        if (chestKey != null) all.add(chestKey);
        for (String k : all) {
            Block b = block(k);
            if (b == null || b.getType() != Material.CHEST) continue;
            String before = data().getString("tresor.avant." + k.replace(':', '_'));
            BlockData bd = null;
            try {
                if (before != null) bd = Bukkit.createBlockData(before);
            } catch (IllegalArgumentException ignored) {
            }
            b.setBlockData(bd == null ? Bukkit.createBlockData(Material.AIR) : bd, false);
        }
        if (announce && chestKey != null && found.isEmpty()) {
            Block b = block(chestKey);
            if (b != null) {
                Bukkit.broadcast(plugin.prefixed("<gray>Personne n'a trouvé le trésor n°" + number + " : il était en <white>"
                        + b.getX() + " " + b.getY() + " " + b.getZ() + "</white>."));
            }
        }
        data().set("tresor.coffre", null);
        data().set("tresor.pieges", null);
        loadKeys();
        noel.markDirty();
        noel.saveIfDirty();
    }

    // ───────────────────────────── Quêtes ─────────────────────────────

    private Map<?, ?> quest(int step) {
        List<Map<?, ?>> list = cfg().getMapList("quetes." + step);
        if (list.isEmpty()) return null;
        List<Integer> chosen = data().getIntegerList("tresor.quetes");
        int i = chosen.size() >= step ? chosen.get(step - 1) : 0;
        return list.get(Math.max(0, Math.min(list.size() - 1, i)));
    }

    private static int num(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String questText(Map<?, ?> q) {
        return q == null ? "?" : String.valueOf(q.get("texte") == null ? q.get("type") : q.get("texte"));
    }

    private int step(UUID u) {
        return data().getInt("tresor.progres." + u + ".etape");
    }

    private void progress(Player p, String type, String target, int amount) {
        if (!running() || amount <= 0 || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        UUID u = p.getUniqueId();
        int step = step(u);
        if (step >= 4) return;
        Map<?, ?> q = quest(step + 1);
        if (q == null || !type.equals(String.valueOf(q.get("type")))) return;
        Object cible = q.get("cible");
        if (cible != null && !String.valueOf(cible).isBlank()) {
            boolean ok = false;
            for (String c : String.valueOf(cible).split(",")) if (c.trim().equalsIgnoreCase(target)) ok = true;
            if (!ok) return;
        }
        int need = Math.max(1, num(q.get("nombre"), 1));
        String base = "tresor.progres." + u;
        int count = data().getInt(base + ".compte") + amount;
        if (count >= need) {
            data().set(base + ".etape", step + 1);
            data().set(base + ".compte", 0);
            noel.markDirty();
            reveal(p, step + 1);
        } else {
            data().set(base + ".compte", count);
            noel.markDirty();
            p.sendActionBar(Component.text("Quête du trésor " + (step + 1) + "/4 : ", GOLD)
                    .append(Component.text(questText(q) + " (" + count + "/" + need + ")", Text.WHITE)));
        }
    }

    private String clue(int step) {
        if (step >= 4) {
            Block b = chestKey == null ? null : block(chestKey);
            return b == null ? "?" : "Le trésor est en <white>X " + b.getX() + "  Y " + b.getY() + "  Z " + b.getZ() + "</white>.";
        }
        List<String> zones = data().getStringList("tresor.zones");
        if (zones.size() < step) return "?";
        String[] z = zones.get(step - 1).split(":");
        return "Le trésor est à moins de <white>" + z[0] + " blocs</white> de <white>X " + z[1] + "  Z " + z[2] + "</white>"
                + (step == 3 ? ", en surface" : "") + ".";
    }

    private void reveal(Player p, int step) {
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
        plugin.msg(p, "<#ffd86b>✦</#ffd86b> <white>Quête " + step + "/4 réussie !</white> <gray>Indice : " + clue(step));
        if (step < 4) plugin.msg(p, "<gray>Quête suivante : <white>" + questText(quest(step + 1)) + "</white>.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakForQuest(BlockBreakEvent e) {
        if (plugin.placed().isPlaced(e.getBlock())) return;
        progress(e.getPlayer(), "casser", e.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer != null) progress(killer, "tuer", e.getEntity().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item item && FISH.contains(item.getItemStack().getType())) {
            progress(e.getPlayer(), "pecher", item.getItemStack().getType().name(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getRecipe().getResult();
        int crafts = 1;
        if (e.isShiftClick()) {
            int min = Integer.MAX_VALUE;
            for (ItemStack it : e.getInventory().getMatrix()) if (it != null && !it.getType().isAir()) min = Math.min(min, it.getAmount());
            crafts = min == Integer.MAX_VALUE ? 1 : min;
        }
        progress(p, "crafter", result.getType().name(), result.getAmount() * crafts);
    }

    // ───────────────────────────── Coffres ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOpen(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !running()) return;
        Block b = e.getClickedBlock();
        if (!isTreasureBlock(b)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        String k = key(b);
        if (k.equals(chestKey)) found(p, b);
        else trap(p, b, trapKeys.indexOf(k));
    }

    private void found(Player p, Block b) {
        List<String> found = new ArrayList<>(data().getStringList("tresor.trouve"));
        if (found.contains(p.getUniqueId().toString())) {
            plugin.msg(p, "<gray>Tu as déjà ton cadeau de ce trésor. Le prochain arrive bientôt !");
            return;
        }
        found.add(p.getUniqueId().toString());
        data().set("tresor.trouve", found);
        data().set("tresor.noms." + p.getUniqueId(), p.getName());
        data().set("tresor.progres." + p.getUniqueId() + ".etape", 4);
        noel.markDirty();
        noel.saveIfDirty();
        Bundle gift = noel.bundle("tresor.cadeau");
        plugin.rewards().grant(p, gift);
        int number = data().getInt("tresor.numero");
        if (found.size() == 1) {
            Bundle bonus = noel.bundle("tresor.bonus-premier");
            plugin.rewards().grant(p, bonus);
            Bukkit.broadcast(plugin.prefixed("<#ffd86b>✦</#ffd86b> <white>" + p.getName() + "</white> <gray>a trouvé le trésor de Noël n°" + number
                    + " <white>en premier</white> ! Les autres peuvent encore trouver le leur : <click:run_command:'/tresor'><white><u>/tresor</u></white></click>"));
            plugin.discord().send("Trésor trouvé !", p.getName() + " a trouvé le trésor de Noël en premier.", 0xFFD86B, p.getUniqueId());
        } else {
            Bukkit.broadcast(plugin.prefixed("<#ffd86b>✦</#ffd86b> <white>" + p.getName() + "</white> <gray>a aussi trouvé le trésor de Noël ("
                    + found.size() + "e)."));
        }
        plugin.msg(p, "<#ffd86b>✦</#ffd86b> <white>Tu as trouvé le trésor de Noël !</white>");
        for (Component line : plugin.rewards().describe(gift)) p.sendMessage(Component.text("  • ", Text.DARK).append(line));
        p.playSound(b.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, b.getLocation().add(0.5, 1, 0.5), 60, 0.5, 0.8, 0.5, 0.25);
        plugin.getLogger().info("Chasse au trésor n°" + number + " : " + p.getName() + " trouve le coffre (" + found.size() + "e).");
    }

    private void trap(Player p, Block b, int index) {
        String path = "tresor.farces." + p.getUniqueId();
        List<Integer> done = new ArrayList<>(data().getIntegerList(path));
        if (done.contains(index)) {
            plugin.msg(p, "<gray>Ce coffre est vide... c'était un piège. Le vrai n'est pas loin !");
            return;
        }
        done.add(index);
        data().set(path, done);
        noel.markDirty();
        Location at = b.getLocation().add(0.5, 1, 0.5);
        p.playSound(at, Sound.ENTITY_WITCH_CELEBRATE, 1f, 1.3f);
        switch (ThreadLocalRandom.current().nextInt(3)) {
            case 0 -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 50, 1, false, true, true));
                plugin.msg(p, "<#ff6b6b>Raté !</#ff6b6b> <gray>Ce coffre était piégé... bon vol !");
            }
            case 1 -> {
                p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 2, 0), 200, 1.2, 1.5, 1.2, 0.02);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 1, false, true, true));
                plugin.msg(p, "<#ff6b6b>Une avalanche de neige !</#ff6b6b> <gray>Ce n'était pas le bon coffre.");
            }
            default -> {
                Allay elf = p.getWorld().spawn(at, Allay.class, a -> {
                    a.customName(Component.text("Lutin farceur", TextColor.color(0x7BD88F)));
                    a.setCustomNameVisible(true);
                    a.setPersistent(false);
                    a.setVelocity(new org.bukkit.util.Vector(0, 0.6, 0));
                });
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (elf.isValid()) {
                        elf.getWorld().spawnParticle(Particle.POOF, elf.getLocation(), 15, 0.2, 0.2, 0.2, 0.02);
                        elf.remove();
                    }
                }, 160L);
                plugin.msg(p, "<#ff6b6b>Un lutin farceur</#ff6b6b> <gray>sort du coffre en riant. Mauvais coffre !");
            }
        }
    }

    // ───────────────────────────── Protection des coffres ─────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreakChest(BlockBreakEvent e) {
        if (!isTreasureBlock(e.getBlock())) return;
        e.setCancelled(true);
        plugin.msg(e.getPlayer(), "<gray>Ce coffre ne se casse pas : ouvre-le !");
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (running()) e.blockList().removeIf(this::isTreasureBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (running()) e.blockList().removeIf(this::isTreasureBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (running() && e.getBlocks().stream().anyMatch(this::isTreasureBlock)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (running() && e.getBlocks().stream().anyMatch(this::isTreasureBlock)) e.setCancelled(true);
    }

    // ───────────────────────────── /tresor ─────────────────────────────

    public void show(Player p) {
        if (!running()) {
            long next = nextSlot();
            plugin.msg(p, "<gray>Pas de trésor caché pour l'instant. Le prochain : <white>" + when(next) + "</white>.");
            return;
        }
        int number = data().getInt("tresor.numero");
        long left = data().getLong("tresor.fin") - noel.now();
        List<String> found = data().getStringList("tresor.trouve");
        plugin.msg(p, "<#ffd86b>✦</#ffd86b> <white>Chasse au trésor de Noël n°" + number + "</white> <dark_gray>(encore " + duration(left) + ")");
        String first = found.isEmpty() ? null : data().getString("tresor.noms." + found.get(0));
        p.sendMessage(plugin.text().parse("  <gray>" + (found.isEmpty() ? "Personne ne l'a encore trouvé." : "Trouvé par <white>" + found.size()
                + "</white> joueur" + (found.size() > 1 ? "s" : "") + ", en premier par <white>" + first + "</white>.")));
        if (found.contains(p.getUniqueId().toString())) {
            p.sendMessage(plugin.text().parse("  <#7bd88f>✔ Tu as trouvé ce trésor.</#7bd88f> <gray>Le prochain arrive dans " + duration(left) + "."));
            return;
        }
        int step = step(p.getUniqueId());
        if (step < 4) {
            Map<?, ?> q = quest(step + 1);
            int need = Math.max(1, num(q == null ? null : q.get("nombre"), 1));
            p.sendMessage(plugin.text().parse("  <gray>Ta quête " + (step + 1) + "/4 : <white>" + questText(q) + "</white> <dark_gray>("
                    + data().getInt("tresor.progres." + p.getUniqueId() + ".compte") + "/" + need + ")"));
        }
        if (step == 0) {
            p.sendMessage(plugin.text().parse("  <dark_gray>Chaque quête réussie te donne un indice sur l'endroit du coffre. Attention aux faux coffres !"));
            return;
        }
        p.sendMessage(plugin.text().parse("  <gray>Tes indices :"));
        for (int i = 1; i <= Math.min(4, step); i++) p.sendMessage(plugin.text().parse("   <dark_gray>" + i + ".</dark_gray> <gray>" + clue(i)));
    }

    public void admin(Player p, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "nouveau" -> {
                long slot = slotStart();
                start(slot < 0 ? -1 : slot, (slot < 0 ? noel.now() : slot) + period());
                plugin.msg(p, "<gray>Recherche d'un endroit pour le nouveau trésor...");
            }
            case "fin" -> {
                end(true);
                plugin.msg(p, "<gray>Trésor retiré.");
            }
            case "ou" -> {
                if (!running()) {
                    plugin.msg(p, "<gray>Aucun trésor caché.");
                    return;
                }
                plugin.msg(p, "<gray>Coffre : <white>" + chestKey.replace(':', ' ') + "</white> <dark_gray>· pièges : " + String.join(", ", trapKeys));
            }
            default -> plugin.msg(p, "<gray>Usage : <white>/tresor admin nouveau|ou|fin");
        }
    }

    private String when(long millis) {
        ZonedDateTime t = java.time.Instant.ofEpochMilli(millis).atZone(plugin.text().zone());
        return t.getDayOfMonth() + " " + t.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.FRANCE) + " à "
                + String.format(Locale.FRANCE, "%dh%02d", t.getHour(), t.getMinute());
    }

    private static String duration(long ms) {
        long h = Math.max(0, ms) / 3_600_000L;
        if (h >= 24) return (h / 24) + " j " + (h % 24) + " h";
        return Math.max(1, h) + " h";
    }

    // ───────────────────────────── Site ─────────────────────────────

    public Map<String, Object> webData() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean on = running();
        m.put("actif", on);
        m.put("numero", data().getInt("tresor.numero"));
        m.put("tousLesJours", Math.max(1, cfg().getInt("tous-les-jours", 5)));
        if (on) {
            m.put("fin", data().getLong("tresor.fin"));
            List<String> names = new ArrayList<>();
            for (String u : data().getStringList("tresor.trouve")) names.add(data().getString("tresor.noms." + u, "?"));
            m.put("trouveurs", names);
            List<String> quests = new ArrayList<>();
            for (int i = 1; i <= 4; i++) quests.add(questText(quest(i)));
            m.put("quetes", quests);
        } else {
            m.put("prochain", nextSlot());
        }
        m.put("cadeau", noel.describe(cfg().getConfigurationSection("cadeau")));
        m.put("bonus", noel.describe(cfg().getConfigurationSection("bonus-premier")));
        return m;
    }
}
