package fr.lyonmc.achievements.rewards;

import fr.lyonmc.achievements.core.Items;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lot de récompenses (pièces, titre, cosmétiques, homes, XP, objets, commandes, points de saison). */
public record Bundle(long coins, List<String> titles, String color, String trail, int homes,
                     int xpLevels, int xpPoints, List<String> items, List<String> commands, int seasonPoints) {

    public static final Bundle EMPTY = new Bundle(0, List.of(), null, null, 0, 0, 0, List.of(), List.of(), 0);

    public static Bundle of(ConfigurationSection c) {
        if (c == null) return EMPTY;
        List<String> titles = new ArrayList<>(c.getStringList("titles"));
        if (c.isString("title")) titles.add(c.getString("title"));
        return new Bundle(
                c.getLong("coins"),
                List.copyOf(titles),
                c.getString("color"),
                c.getString("trail"),
                c.getInt("homes"),
                c.getInt("xp-levels"),
                c.getInt("xp"),
                List.copyOf(c.getStringList("items")),
                List.copyOf(c.getStringList("commands")),
                c.getInt("season-points"));
    }

    /** Pour les listes de la config écrites en { coins: 50, ... } */
    @SuppressWarnings("unchecked")
    public static Bundle of(Map<?, ?> m) {
        if (m == null) return EMPTY;
        List<String> titles = new ArrayList<>();
        if (m.get("title") instanceof String s) titles.add(s);
        if (m.get("titles") instanceof List<?> l) for (Object o : l) titles.add(String.valueOf(o));
        List<String> items = new ArrayList<>();
        if (m.get("items") instanceof List<?> l) for (Object o : l) items.add(String.valueOf(o));
        List<String> commands = new ArrayList<>();
        if (m.get("commands") instanceof List<?> l) for (Object o : l) commands.add(String.valueOf(o));
        return new Bundle(
                num(m.get("coins")),
                List.copyOf(titles),
                m.get("color") instanceof String s ? s : null,
                m.get("trail") instanceof String s ? s : null,
                (int) num(m.get("homes")),
                (int) num(m.get("xp-levels")),
                (int) num(m.get("xp")),
                List.copyOf(items),
                List.copyOf(commands),
                (int) num(m.get("season-points")));
    }

    private static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    public List<ItemStack> itemStacks() {
        List<ItemStack> out = new ArrayList<>();
        for (String s : items) {
            ItemStack it = Items.parse(s);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** Multiplie les pièces et points (bonus de série, week-end...). */
    public Bundle scaled(double coinFactor, double pointFactor) {
        return new Bundle(Math.round(coins * coinFactor), titles, color, trail, homes, xpLevels, xpPoints, items, commands,
                (int) Math.round(seasonPoints * pointFactor));
    }

    public boolean isEmpty() {
        return coins == 0 && titles.isEmpty() && color == null && trail == null && homes == 0 && xpLevels == 0
                && xpPoints == 0 && items.isEmpty() && commands.isEmpty() && seasonPoints == 0;
    }
}
