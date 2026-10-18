package fr.lyonmc.achievements.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Inventaire de menu : chaque slot cliquable a une action. */
public final class MenuHolder implements InventoryHolder {

    private Inventory inventory;
    private final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public void action(int slot, Consumer<Player> action) {
        actions.put(slot, (p, c) -> action.accept(p));
    }

    /** Action qui dépend du type de clic (gauche, droit, maj...). */
    public void click(int slot, BiConsumer<Player, ClickType> action) {
        actions.put(slot, action);
    }

    public boolean hasAction(int slot) {
        return actions.containsKey(slot);
    }

    public void click(int slot, Player player, ClickType type) {
        BiConsumer<Player, ClickType> a = actions.get(slot);
        if (a != null) a.accept(player, type);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
