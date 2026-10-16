package fr.lyonmc.achievements.menu;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class MenuListener implements Listener {

    private final JavaPlugin plugin;

    public MenuListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (!holder.hasAction(slot)) return;
        // Ouvrir un autre inventaire pendant un clic doit se faire au tick suivant
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.4f);
            holder.click(slot, player);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }
}
