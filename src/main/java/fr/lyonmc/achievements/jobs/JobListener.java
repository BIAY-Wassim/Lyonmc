package fr.lyonmc.achievements.jobs;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Ce qui rapporte de l'XP de métier, et la récolte double. */
public final class JobListener implements Listener {

    private final LyonAchievements plugin;

    public JobListener(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private JobService jobs() {
        return plugin.jobs();
    }

    /** Cultures qui ne comptent que mûres (leur âge indique la maturité). */
    static boolean isCrop(Material m) {
        return switch (m) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, PITCHER_CROP, TORCHFLOWER_CROP, SWEET_BERRY_BUSH -> true;
            default -> false;
        };
    }

    private static boolean ripe(BlockData data) {
        return data instanceof Ageable age && age.getAge() >= age.getMaximumAge();
    }

    // ───────────────────────────── Blocs ─────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!jobs().canGain(p)) return;
        Block b = e.getBlock();
        Material m = b.getType();
        if (isCrop(m)) {
            if (!ripe(b.getBlockData())) return;
        } else if (plugin.placed().isPlaced(b)) {
            return;
        }
        for (JobService.Job job : jobs().all()) {
            Integer xp = job.blocks().get(m);
            if (xp != null) jobs().gain(p, job, xp);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent e) {
        Player p = e.getPlayer();
        if (!jobs().canGain(p) || e.getItemsHarvested().isEmpty()) return;
        Material m = e.getHarvestedBlock().getType();
        for (JobService.Job job : jobs().all()) {
            Integer xp = job.harvests().get(m);
            if (xp != null) jobs().gain(p, job, xp);
        }
    }

    /** Récolte double : minerais, bûches, cultures mûres (blocs naturels seulement). */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        if (e.getItems().isEmpty() || !jobs().canGain(p)) return;
        BlockState state = e.getBlockState();
        Material m = state.getType();
        if (isCrop(m) ? !ripe(state.getBlockData()) : plugin.placed().isPlaced(e.getBlock())) return;
        for (JobService.Job job : jobs().all()) {
            if (!job.doubles().contains(m)) continue;
            ItemStack tool = p.getInventory().getItemInMainHand();
            if (job.id().equals("mineur") && tool.containsEnchantment(Enchantment.SILK_TOUCH)) return;
            if (!roll(p, job)) return;
            for (Item item : e.getItems()) {
                ItemStack s = item.getItemStack();
                s.setAmount(Math.min(s.getMaxStackSize(), s.getAmount() * 2));
                item.setItemStack(s);
            }
            celebrate(p, job);
            return;
        }
    }

    private boolean roll(Player p, JobService.Job job) {
        Profile pr = plugin.profiles().get(p);
        double chance = jobs().doubleChance(jobs().level(pr, job.id()));
        return chance > 0 && ThreadLocalRandom.current().nextDouble(100) < chance;
    }

    private void celebrate(Player p, JobService.Job job) {
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.6f);
        p.sendActionBar(plugin.text().parse("<accent>✦</accent> <white>Récolte double !</white> <dark_gray>(")
                .append(job.label()).append(plugin.text().parse("<dark_gray>)")));
    }

    // ───────────────────────────── Élevage ─────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player p) || !jobs().canGain(p)) return;
        for (JobService.Job job : jobs().all()) if (job.breed() > 0) jobs().gain(p, job, job.breed());
    }

    // ───────────────────────────── Chasse ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player || dead instanceof ArmorStand) return;
        if (dead instanceof Tameable t && t.isTamed()) return;
        if (!jobs().canGain(killer)) return;
        boolean spawner = dead.fromMobSpawner();
        double factor = spawner ? jobs().spawnerPercent() / 100.0 : 1.0;
        String type = dead.getType().name();
        for (JobService.Job job : jobs().all()) {
            if (job.mobs().isEmpty()) continue;
            Integer xp = job.mobs().get(type);
            if (xp == null) xp = job.mobs().get(dead instanceof Enemy ? "HOSTILE" : "PASSIVE");
            if (xp != null && factor > 0) jobs().gain(killer, job, xp * factor);
            // Butin double (jamais les objets ramassés ou portés par la créature, ni ceux des cages)
            if (!spawner && !e.getDrops().isEmpty() && roll(killer, job)) {
                List<ItemStack> worn = equipment(dead);
                boolean doubled = false;
                for (ItemStack drop : e.getDrops()) {
                    if (drop == null || drop.getMaxStackSize() <= 1 || drop.hasItemMeta() && drop.getItemMeta().hasDisplayName()) continue;
                    boolean isWorn = false;
                    for (ItemStack w : worn) if (w.isSimilar(drop)) isWorn = true;
                    if (isWorn) continue;
                    drop.setAmount(Math.min(drop.getMaxStackSize(), drop.getAmount() * 2));
                    doubled = true;
                }
                if (doubled) celebrate(killer, job);
            }
        }
    }

    private static List<ItemStack> equipment(LivingEntity e) {
        List<ItemStack> out = new ArrayList<>();
        EntityEquipment eq = e.getEquipment();
        if (eq == null) return out;
        for (ItemStack it : new ItemStack[]{eq.getItemInMainHand(), eq.getItemInOffHand(), eq.getHelmet(), eq.getChestplate(),
                eq.getLeggings(), eq.getBoots()}) {
            if (it != null && !it.getType().isAir()) out.add(it);
        }
        return out;
    }

    // ───────────────────────────── Pêche ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item item)) return;
        Player p = e.getPlayer();
        if (!jobs().canGain(p)) return;
        ItemStack caught = item.getItemStack();
        String kind = kind(caught);
        for (JobService.Job job : jobs().all()) {
            Integer xp = job.fish().get(kind);
            if (xp == null) continue;
            jobs().gain(p, job, xp);
            if (kind.equals("POISSON") && caught.getMaxStackSize() > 1 && roll(p, job)) {
                caught.setAmount(Math.min(caught.getMaxStackSize(), caught.getAmount() * 2));
                item.setItemStack(caught);
                celebrate(p, job);
            }
        }
    }

    static String kind(ItemStack it) {
        return switch (it.getType()) {
            case COD, SALMON, TROPICAL_FISH, PUFFERFISH -> "POISSON";
            case BOW, ENCHANTED_BOOK, NAME_TAG, NAUTILUS_SHELL, SADDLE -> "TRESOR";
            case FISHING_ROD -> it.getEnchantments().isEmpty() ? "DECHET" : "TRESOR";
            default -> "DECHET";
        };
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        jobs().quit(e.getPlayer().getUniqueId());
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static boolean survival(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }
}
