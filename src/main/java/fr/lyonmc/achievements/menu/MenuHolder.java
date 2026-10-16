package fr.lyonmc.achievements.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Inventaire de menu : chaque slot cliquable a une action. */
public final class MenuHolder implements InventoryHolder {

    private Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    void action(int slot, Consumer<Player> action) {
        actions.put(slot, action);
    }

    public boolean hasAction(int slot) {
        return actions.containsKey(slot);
    }

    public void click(int slot, Player player) {
        Consumer<Player> a = actions.get(slot);
        if (a != null) a.accept(player);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
