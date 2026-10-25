package fr.lyonmc.achievements.missions;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Fait avancer les missions à partir des actions des joueurs. */
public final class MissionListener implements Listener {

    private final LyonAchievements plugin;
    private final Map<UUID, Long> lastDistance = new HashMap<>();

    public MissionListener(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private MissionService missions() {
        return plugin.missions();
    }

    private static boolean playing(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player || !playing(killer)) return;
        missions().progress(killer, MissionType.KILL, dead.getType().name(), dead instanceof Enemy, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!playing(p)) return;
        Block b = event.getBlock();
        Material type = b.getType();
        // Blocs posés par des joueurs : ils ne comptent pas comme "minés" (retenu même après un redémarrage)
        boolean wasPlaced = plugin.placed().isPlaced(b);

        if (b.getBlockData() instanceof Ageable age && isCrop(type)) {
            if (age.getAge() >= age.getMaximumAge()) missions().progress(p, MissionType.HARVEST, type.name(), false, 1);
            return;
        }
        if (wasPlaced) return;
        if (type == Material.MELON || type == Material.PUMPKIN) {
            missions().progress(p, MissionType.HARVEST, type.name(), false, 1);
        }
        missions().progress(p, MissionType.MINE, type.name(), false, 1);
        Profile pr = plugin.profiles().get(p);
        pr.blocksMined++;
        pr.dirty();
        if (pr.blocksMined >= 10_000) plugin.custom().grant(p, "mineur");
    }

    private static boolean isCrop(Material m) {
        return switch (m) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, TORCHFLOWER_CROP, PITCHER_CROP -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        Block b = event.getBlockPlaced();
        if (playing(p)) missions().progress(p, MissionType.PLACE, b.getType().name(), false, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && playing(event.getPlayer())) {
            missions().progress(event.getPlayer(), MissionType.FISH, null, false, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player p && playing(p)) {
            missions().progress(p, MissionType.BREED, event.getEntity().getType().name(), false, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player p && playing(p)) {
            missions().progress(p, MissionType.TAME, event.getEntity().getType().name(), false, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || !playing(p)) return;
        ItemStack result = event.getRecipe().getResult();
        int amount = result.getAmount();
        if (event.isShiftClick()) {
            int crafts = Integer.MAX_VALUE;
            for (ItemStack it : event.getInventory().getMatrix()) {
                if (it != null && !it.getType().isAir()) crafts = Math.min(crafts, it.getAmount());
            }
            if (crafts != Integer.MAX_VALUE) amount *= crafts;
        }
        missions().progress(p, MissionType.CRAFT, result.getType().name(), false, amount);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent event) {
        if (playing(event.getPlayer())) {
            missions().progress(event.getPlayer(), MissionType.SMELT, event.getItemType().name(), false, event.getItemAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        if (playing(event.getPlayer())) missions().progress(event.getPlayer(), MissionType.TRADE, null, false, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (playing(event.getEnchanter())) {
            missions().progress(event.getEnchanter(), MissionType.ENCHANT, event.getItem().getType().name(), false, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent event) {
        if (event.getItem().getType().isEdible() && playing(event.getPlayer())) {
            missions().progress(event.getPlayer(), MissionType.EAT, event.getItem().getType().name(), false, 1);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastDistance.remove(event.getPlayer().getUniqueId());
    }

    /** Distance parcourue (appelé toutes les 20 secondes). */
    public void tickDistance() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            long cm = (long) p.getStatistic(Statistic.WALK_ONE_CM) + p.getStatistic(Statistic.SPRINT_ONE_CM)
                    + p.getStatistic(Statistic.SWIM_ONE_CM) + p.getStatistic(Statistic.WALK_ON_WATER_ONE_CM)
                    + p.getStatistic(Statistic.WALK_UNDER_WATER_ONE_CM);
            Long before = lastDistance.put(p.getUniqueId(), cm);
            if (before == null || !playing(p)) continue;
            int meters = (int) ((cm - before) / 100);
            if (meters > 0 && meters < 2000) missions().progress(p, MissionType.WALK, null, false, meters);
        }
    }
}
