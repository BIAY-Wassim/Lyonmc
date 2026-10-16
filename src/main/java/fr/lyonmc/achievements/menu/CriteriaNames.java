package fr.lyonmc.achievements.menu;

import net.kyori.adventure.text.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Transforme un critère d'advancement ("minecraft:plains", "baked_potato"...) en nom lisible.
 * Utilise les traductions du jeu quand c'est possible : le joueur voit le nom dans sa langue.
 */
final class CriteriaNames {

    private enum Kind { BIOME, ENTITY, ITEM, BLOCK, PLAIN }

    private static final Map<String, Kind> KINDS = Map.ofEntries(
            Map.entry("minecraft:adventure/adventuring_time", Kind.BIOME),
            Map.entry("minecraft:nether/explore_nether", Kind.BIOME),
            Map.entry("minecraft:adventure/kill_all_mobs", Kind.ENTITY),
            Map.entry("minecraft:adventure/kill_a_mob", Kind.ENTITY),
            Map.entry("minecraft:husbandry/bred_all_animals", Kind.ENTITY),
            Map.entry("minecraft:husbandry/balanced_diet", Kind.ITEM),
            Map.entry("minecraft:husbandry/froglights", Kind.BLOCK)
    );

    private CriteriaNames() {
    }

    static Component of(String advancementKey, String criterion) {
        String id = criterion.contains(":") ? criterion.substring(criterion.indexOf(':') + 1) : criterion;
        String fallback = pretty(id);
        Kind kind = KINDS.getOrDefault(advancementKey, Kind.PLAIN);
        String key = switch (kind) {
            case BIOME -> "biome.minecraft." + id;
            case ENTITY -> "entity.minecraft." + id;
            case ITEM -> "item.minecraft." + id;
            case BLOCK -> "block.minecraft." + id;
            case PLAIN -> null;
        };
        return key == null ? Component.text(fallback) : Component.translatable(key, fallback);
    }

    private static String pretty(String id) {
        String s = id.replace('/', ' ').replace('_', ' ').trim();
        if (s.isEmpty()) return id;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
