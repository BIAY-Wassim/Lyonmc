package fr.lyonmc.achievements.core;

import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Petits outils pour construire les objets des menus. */
public final class Items {

    public static final Component EMPTY = Component.empty();

    private Items() {
    }

    public static ItemStack item(Material material, Component name, List<Component> lore, boolean glint) {
        return decorate(new ItemStack(material), name, lore, glint);
    }

    public static ItemStack item(Material material, String name, TextColor color, List<Component> lore) {
        return decorate(new ItemStack(material), Component.text(name, color), lore, false);
    }

    public static ItemStack decorate(ItemStack item, Component name, List<Component> lore, boolean glint) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (name != null) meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> clean = new ArrayList<>(lore.size());
        for (Component c : lore) clean.add(c.decoration(TextDecoration.ITALIC, false));
        meta.lore(clean);
        meta.setEnchantmentGlintOverride(glint);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack pane() {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setHideTooltip(true);
        pane.setItemMeta(meta);
        return pane;
    }

    /** Remplit une rangée (0 à 5) avec des vitres là où il n'y a rien. */
    public static void frame(Inventory inv, int row) {
        ItemStack pane = pane();
        for (int i = row * 9; i < row * 9 + 9 && i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, pane);
        }
    }

    public static void fill(Inventory inv) {
        ItemStack pane = pane();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, pane);
        }
    }

    public static Component kv(String key, String value) {
        return Component.text(key + "  ", Text.GRAY).append(Component.text(value, Text.WHITE));
    }

    public static Component line(String s) {
        return Component.text(s, Text.GRAY);
    }

    public static Component dark(String s) {
        return Component.text(s, Text.DARK);
    }

    public static Component action(String s, TextColor accent) {
        return Component.text("➤ " + s, accent);
    }

    /** "DIAMOND:3" -> ItemStack, null si invalide. */
    public static ItemStack parse(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] p = spec.trim().split(":");
        Material m = Material.matchMaterial(p[0].toUpperCase(Locale.ROOT));
        if (m == null || !m.isItem() || m.isAir()) return null;
        int amount = 1;
        if (p.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(p[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return new ItemStack(m, amount);
    }

    public static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return m == null || !m.isItem() || m.isAir() ? fallback : m;
    }

    /** Nom lisible d'un matériau : DIAMOND_ORE -> Diamond ore (fallback). */
    public static String pretty(String id) {
        String s = id.toLowerCase(Locale.ROOT).replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
