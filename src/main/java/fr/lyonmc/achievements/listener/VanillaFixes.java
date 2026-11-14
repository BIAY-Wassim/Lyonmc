package fr.lyonmc.achievements.listener;

import fr.lyonmc.achievements.LyonAchievements;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Succès du jeu qui ne se débloquent pas sur les serveurs Paper / Purpur (ils marchent en solo).
 * « Shear Brilliance » (husbandry/remove_wolf_armor) : retirer l'armure de son loup avec des cisailles.
 */
public final class VanillaFixes implements Listener {

    private static final NamespacedKey SHEAR_BRILLIANCE = NamespacedKey.minecraft("husbandry/remove_wolf_armor");

    private final LyonAchievements plugin;

    public VanillaFixes(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShearWolf(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Wolf wolf)) return;
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItem(event.getHand());
        if (hand == null || hand.getType() != Material.SHEARS || !hasArmor(wolf)) return;
        // Le tick suivant : l'armure est-elle tombée ?
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || !wolf.isValid() || hasArmor(wolf)) return;
            Advancement adv = Bukkit.getAdvancement(SHEAR_BRILLIANCE);
            if (adv == null) return;
            AdvancementProgress progress = p.getAdvancementProgress(adv);
            if (progress.isDone()) return;
            for (String criterion : progress.getRemainingCriteria()) progress.awardCriteria(criterion);
            plugin.getLogger().info("Succès « Shear Brilliance » donné à " + p.getName() + " (armure de loup retirée avec des cisailles).");
        });
    }

    private static boolean hasArmor(Wolf wolf) {
        EntityEquipment eq = wolf.getEquipment();
        if (eq == null) return false;
        ItemStack armor = eq.getItem(EquipmentSlot.BODY);
        return armor != null && armor.getType() == Material.WOLF_ARMOR;
    }
}
