package fr.lyonmc.achievements.missions;

import fr.lyonmc.achievements.core.Items;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Définition d'une mission (tirée de missions.yml). */
public record MissionDef(String id, String tier, MissionType type, Set<String> targets, boolean any, boolean hostile,
                         int amount, String name, Material icon) {

    public static MissionDef of(String id, String tier, ConfigurationSection c) {
        MissionType type;
        try {
            type = MissionType.valueOf(c.getString("type", "KILL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        Set<String> targets = new HashSet<>();
        for (String t : c.getStringList("targets")) targets.add(t.toUpperCase(Locale.ROOT));
        boolean any = targets.isEmpty() || targets.contains("ANY");
        boolean hostile = targets.contains("HOSTILE");
        int amount = Math.max(1, c.getInt("amount", 1));
        return new MissionDef(tier + "/" + id, tier, type, Set.copyOf(targets), any && !hostile, hostile, amount,
                c.getString("name", id).replace("<amount>", fr.lyonmc.achievements.util.Text.num(amount)),
                Items.material(c.getString("icon"), type.icon()));
    }

    public boolean matches(String key, boolean isHostile) {
        if (any) return true;
        if (hostile && isHostile) return true;
        return key != null && targets.contains(key.toUpperCase(Locale.ROOT));
    }

    public String tierName() {
        return switch (tier) {
            case "easy" -> "Facile";
            case "medium" -> "Moyenne";
            case "hard" -> "Difficile";
            default -> "Hebdomadaire";
        };
    }
}
