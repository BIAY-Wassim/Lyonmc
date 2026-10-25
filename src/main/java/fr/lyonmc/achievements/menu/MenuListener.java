package fr.lyonmc.achievements.menu;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class MenuListener implements Listener {

    private final JavaPlugin plugin;
    /** Un seul clic traité par tick et par joueur : un double-clic ne paie pas deux fois. */
    private final Set<UUID> pending = new HashSet<>();

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
        ClickType type = event.getClick();
        if (type == ClickType.DOUBLE_CLICK || !pending.add(player.getUniqueId())) return;
        // Ouvrir un autre inventaire pendant un clic doit se faire au tick suivant
        Bukkit.getScheduler().runTask(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            // Le menu a été fermé ou remplacé entre-temps (ex. : confirmation déjà validée) : rien à faire
            if (player.getOpenInventory().getTopInventory().getHolder(false) != holder) return;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.4f);
            holder.click(slot, player, type);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }
}
