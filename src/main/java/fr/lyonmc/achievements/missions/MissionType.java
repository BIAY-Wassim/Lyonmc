package fr.lyonmc.achievements.missions;

import org.bukkit.Material;

public enum MissionType {
    KILL(Material.IRON_SWORD),
    MINE(Material.IRON_PICKAXE),
    HARVEST(Material.WHEAT),
    FISH(Material.FISHING_ROD),
    BREED(Material.WHEAT_SEEDS),
    CRAFT(Material.CRAFTING_TABLE),
    SMELT(Material.FURNACE),
    TRADE(Material.EMERALD),
    ENCHANT(Material.ENCHANTING_TABLE),
    WALK(Material.LEATHER_BOOTS),
    EAT(Material.COOKED_BEEF),
    TAME(Material.BONE),
    PLACE(Material.BRICKS);

    private final Material icon;

    MissionType(Material icon) {
        this.icon = icon;
    }

    public Material icon() {
        return icon;
    }
}
