package fr.lyonmc.achievements.menu;

import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/** Petite fenêtre de confirmation (achat, suppression...). */
public final class ConfirmMenu {

    private ConfirmMenu() {
    }

    public static void open(Player player, String title, ItemStack preview, List<Component> details,
                            Consumer<Player> onConfirm, Consumer<Player> onCancel) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text(title));
        holder.inventory(inv);
        inv.setItem(11, Items.item(Material.LIME_CONCRETE, Component.text("✔ Confirmer", Text.WHITE), details, false));
        if (preview != null) inv.setItem(13, preview);
        inv.setItem(15, Items.item(Material.RED_CONCRETE, Component.text("✘ Annuler", Text.WHITE), List.of(), false));
        holder.action(11, p -> {
            p.closeInventory();
            onConfirm.accept(p);
        });
        holder.action(15, p -> {
            if (onCancel != null) onCancel.accept(p);
            else p.closeInventory();
        });
        Items.fill(inv);
        player.openInventory(inv);
    }
}
