package fr.lyonmc.achievements.world;

import fr.lyonmc.achievements.LyonAchievements;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pourquoi miner dans le monde ressources plutôt que dans le monde principal :
 * plus de minerais, minage plus rapide, minerais parfois doublés, pièces par minerai et trésors cachés.
 */
public final class ResourceBonusService implements Listener {

    /** Un minerai : ses deux blocs (pierre / deepslate), la clé de config, sa hauteur et la taille du filon. */
    private record Ore(String key, Material stone, Material deep, int minY, int maxY, int size, int weight) {
    }

    private static final List<Ore> ORES = List.of(
            new Ore("coal", Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, 0, 128, 9, 26),
            new Ore("copper", Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, -16, 96, 8, 16),
            new Ore("iron", Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, -48, 72, 7, 24),
            new Ore("gold", Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, -64, 32, 6, 10),
            new Ore("redstone", Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, -64, 16, 6, 9),
            new Ore("lapis", Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE, -64, 32, 5, 7),
            new Ore("diamond", Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, -64, 16, 4, 6),
            new Ore("emerald", Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE, -16, 160, 2, 2));

    private static final Map<Material, String> ORE_KEYS = new LinkedHashMap<>();

    static {
        for (Ore o : ORES) {
            ORE_KEYS.put(o.stone(), o.key());
            ORE_KEYS.put(o.deep(), o.key());
        }
        ORE_KEYS.put(Material.NETHER_QUARTZ_ORE, "quartz");
        ORE_KEYS.put(Material.NETHER_GOLD_ORE, "gold");
        ORE_KEYS.put(Material.ANCIENT_DEBRIS, "ancient_debris");
    }

    private final LyonAchievements plugin;
    private final Map<java.util.UUID, long[]> pendingCoins = new java.util.HashMap<>();

    public ResourceBonusService(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = fr.lyonmc.achievements.core.Configs.section(plugin.getConfig(), "resource-world.bonus");
        return s == null ? new org.bukkit.configuration.MemoryConfiguration() : s;
    }

    private boolean active(World w) {
        return w != null && plugin.resources() != null && plugin.resources().enabled()
                && w.getName().equals(plugin.resources().name()) && cfg().getBoolean("enabled", true);
    }

    public static boolean isOre(Material m) {
        return ORE_KEYS.containsKey(m);
    }

    // ───────────────────────────── Plus de minerais ─────────────────────────────

    /** Ajouté au monde ressources à sa création : des filons en plus dans chaque nouveau chunk. */
    public BlockPopulator populator() {
        return new BlockPopulator() {
            @Override
            public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
                if (!cfg().getBoolean("enabled", true)) return;
                int veins = cfg().getInt("extra-veins", 8);
                int total = 0;
                for (Ore o : ORES) total += o.weight();
                for (int v = 0; v < veins; v++) {
                    int pick = random.nextInt(total);
                    Ore ore = ORES.get(0);
                    for (Ore o : ORES) {
                        pick -= o.weight();
                        if (pick < 0) {
                            ore = o;
                            break;
                        }
                    }
                    int minY = Math.max(ore.minY(), info.getMinHeight() + 1);
                    int maxY = Math.min(ore.maxY(), info.getMaxHeight() - 1);
                    if (maxY <= minY) continue;
                    int x = (chunkX << 4) + random.nextInt(16);
                    int z = (chunkZ << 4) + random.nextInt(16);
                    int y = minY + random.nextInt(maxY - minY);
                    int size = 1 + random.nextInt(ore.size());
                    for (int i = 0; i < size; i++) {
                        if (region.isInRegion(x, y, z)) {
                            Material here = region.getType(x, y, z);
                            if (here == Material.STONE || here == Material.ANDESITE || here == Material.DIORITE
                                    || here == Material.GRANITE || here == Material.TUFF) {
                                region.setType(x, y, z, ore.stone());
                            } else if (here == Material.DEEPSLATE) {
                                region.setType(x, y, z, ore.deep());
                            }
                        }
                        switch (random.nextInt(3)) {
                            case 0 -> x += random.nextBoolean() ? 1 : -1;
                            case 1 -> y += random.nextBoolean() ? 1 : -1;
                            default -> z += random.nextBoolean() ? 1 : -1;
                        }
                    }
                }
            }
        };
    }

    // ───────────────────────────── Minage plus rapide ─────────────────────────────

    /** Toutes les 5 secondes : Célérité pour les joueurs du monde ressources. */
    public void tick() {
        int haste = cfg().getInt("haste", 1);
        if (haste <= 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!active(p.getWorld())) continue;
            PotionEffect cur = p.getPotionEffect(PotionEffectType.HASTE);
            if (cur == null || (cur.getAmplifier() <= haste - 1 && cur.getDuration() < 200)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 260, haste - 1, true, false, true));
            }
        }
        flushCoins();
    }

    // ───────────────────────────── Pièces, minerais doublés, trésors ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        // Pas de minerais posés puis recassés pour gagner des pièces
        if (active(e.getBlock().getWorld()) && isOre(e.getBlock().getType()) && e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("Impossible de poser un minerai dans le monde ressources", fr.lyonmc.achievements.util.Text.GRAY));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (!active(b.getWorld()) || p.getGameMode() != GameMode.SURVIVAL) return;
        // Pierre posée puis recassée : ni pièces, ni trésor
        if (plugin.placed().isPlaced(b)) return;
        String key = ORE_KEYS.get(b.getType());
        if (key != null) {
            long coins = cfg().getLong("coins." + key, 0);
            if (coins > 0) addCoins(p, coins);
            return;
        }
        Material m = b.getType();
        if (m == Material.STONE || m == Material.DEEPSLATE || m == Material.NETHERRACK) treasure(p, b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (!active(e.getBlock().getWorld()) || !isOre(e.getBlockState().getType())) return;
        if (e.getPlayer().getGameMode() != GameMode.SURVIVAL || plugin.placed().isPlaced(e.getBlock())) return;
        ItemStack tool = e.getPlayer().getInventory().getItemInMainHand();
        if (tool.containsEnchantment(Enchantment.SILK_TOUCH)) return;
        double chance = cfg().getDouble("double-drop-chance", 25);
        if (ThreadLocalRandom.current().nextDouble(100) >= chance) return;
        for (Item item : e.getItems()) {
            ItemStack s = item.getItemStack();
            s.setAmount(Math.min(s.getMaxStackSize(), s.getAmount() * 2));
            item.setItemStack(s);
        }
        e.getPlayer().playSound(e.getBlock().getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
        e.getPlayer().sendActionBar(plugin.text().parse("<accent>✦</accent> <white>Minerai doublé !"));
    }

    /** Regroupe les pièces gagnées en minant (un seul message toutes les quelques secondes). */
    private void addCoins(Player p, long amount) {
        plugin.economy().deposit(p, amount);
        long[] acc = pendingCoins.computeIfAbsent(p.getUniqueId(), u -> new long[2]);
        acc[0] += amount;
        acc[1] = System.currentTimeMillis();
        p.sendActionBar(plugin.text().parse("<accent>⛁</accent> <white>+" + acc[0] + "</white> <gray>pièces (monde ressources)"));
    }

    private void flushCoins() {
        long now = System.currentTimeMillis();
        pendingCoins.values().removeIf(acc -> now - acc[1] > 6000);
    }

    private void treasure(Player p, Block b) {
        double chance = cfg().getDouble("treasure-chance", 0.3);
        if (ThreadLocalRandom.current().nextDouble(100) >= chance) return;
        List<String> list = cfg().getStringList("treasures");
        if (list.isEmpty()) list = List.of("coins:40", "coins:40", "coins:80", "coins:150", "item:DIAMOND:1", "item:EMERALD:2",
                "item:GOLD_INGOT:4", "item:IRON_INGOT:6", "item:EXPERIENCE_BOTTLE:6", "item:NAME_TAG:1");
        String pick = list.get(ThreadLocalRandom.current().nextInt(list.size()));
        String[] parts = pick.split(":");
        Component what;
        try {
            if (parts[0].equalsIgnoreCase("coins")) {
                long n = Long.parseLong(parts[1]);
                plugin.economy().deposit(p, n);
                what = plugin.text().parse("<white>" + n + "</white> <gray>pièces");
            } else {
                Material m = Material.valueOf(parts[1].toUpperCase(Locale.ROOT));
                int n = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
                ItemStack it = new ItemStack(m, n);
                b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), it);
                what = Component.text(n + "× ", fr.lyonmc.achievements.util.Text.WHITE).append(Component.translatable(m.translationKey()));
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Trésor mal écrit dans config.yml : " + pick);
            return;
        }
        p.playSound(b.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        b.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5), 12, 0.4, 0.4, 0.4);
        p.sendMessage(plugin.prefixed("<accent>✦</accent> <gray>Trésor caché dans la roche : ").append(what));
    }

    /** Résumé pour le site (guide). */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("haste", cfg().getInt("haste", 1));
        m.put("doubleChance", cfg().getDouble("double-drop-chance", 25));
        m.put("treasureChance", cfg().getDouble("treasure-chance", 0.3));
        m.put("extraVeins", cfg().getInt("extra-veins", 8));
        Map<String, Object> coins = new LinkedHashMap<>();
        ConfigurationSection c = cfg().getConfigurationSection("coins");
        if (c != null) for (String k : c.getKeys(false)) coins.put(k, c.getLong(k));
        m.put("coins", coins);
        return m;
    }

    public List<String> keys() {
        return new ArrayList<>(ORE_KEYS.values());
    }
}
