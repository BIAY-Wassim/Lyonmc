package fr.lyonmc.achievements.season;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** /saison : podium de la saison, classement, récompenses et historique. */
public final class SeasonMenu {

    private static final TextColor GOLD = TextColor.color(0xFFD86B);
    private static final TextColor SILVER = TextColor.color(0xC8C8C8);
    private static final TextColor BRONZE = TextColor.color(0xD29A6A);

    private final LyonAchievements plugin;

    public SeasonMenu(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        SeasonService ss = plugin.seasons();
        List<Profile> ranking = ss.ranking();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(ss.name()));
        holder.inventory(inv);
        Items.frame(inv, 0);
        Items.frame(inv, 5);

        Profile me = plugin.profiles().get(player);
        int myRank = ss.rank(player.getUniqueId());
        List<Component> info = new ArrayList<>();
        info.add(Items.kv("Fin dans", ss.timeLeft()));
        info.add(Items.kv("Tes points", Text.num(ss.points(me))));
        info.add(Items.kv("Ta position", myRank == 0 ? "non classé" : "#" + myRank + " sur " + ranking.size()));
        if (ss.isWeekend()) {
            info.add(Items.EMPTY);
            info.add(Component.text("✦ Week-end : points x" + ss.multiplier(), plugin.text().accent()));
        }
        info.add(Items.EMPTY);
        info.add(Items.dark("Gagne des points avec les succès,"));
        info.add(Items.dark("les missions et le boss mondial."));
        info.add(Items.dark("Le classement repart à zéro chaque mois."));
        inv.setItem(4, Items.item(Material.CLOCK, Component.text(ss.name(), Text.WHITE).decorate(TextDecoration.BOLD), info, ss.isWeekend()));

        int[][] podium = {{13, 22, 31}, {20, 29}, {24, 33}};
        Material[] blocks = {Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.COPPER_BLOCK};
        TextColor[] colors = {GOLD, SILVER, BRONZE};
        String[] labels = {"♛ 1er", "✧ 2e", "✧ 3e"};
        for (int i = 0; i < 3; i++) {
            Profile p = i < ranking.size() ? ranking.get(i) : null;
            int[] slots = podium[i];
            if (p != null) {
                inv.setItem(slots[0], head(p, colors[i], labels[i]));
                refreshWhenLoaded(player, inv, slots[0], p, colors[i], labels[i]);
            } else {
                inv.setItem(slots[0], Items.item(Material.SKELETON_SKULL, Component.text(labels[i] + "  ·  place libre", Text.GRAY), List.of(), false));
            }
            for (int j = 1; j < slots.length; j++) {
                Bundle b = Bundle.of(plugin.configs().events().getConfigurationSection("season.rewards." + (i + 1)));
                List<Component> lore = new ArrayList<>();
                lore.add(Items.line("Récompenses de fin de saison :"));
                for (Component c : plugin.rewards().describe(b)) lore.add(Component.text(" ").append(c));
                inv.setItem(slots[j], Items.item(blocks[i], Component.text(labels[i], colors[i]), lore, false));
            }
        }
        for (int pos = 4; pos <= 10; pos++) {
            Profile p = pos <= ranking.size() ? ranking.get(pos - 1) : null;
            if (p == null) continue;
            int slot = 37 + (pos - 4);
            inv.setItem(slot, head(p, Text.WHITE, "#" + pos));
        }

        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(45, pl -> plugin.hub().open(pl));

        List<Component> hist = new ArrayList<>();
        List<String> h = ss.history();
        if (h.isEmpty()) hist.add(Items.dark("Aucune saison terminée pour l'instant."));
        for (int i = 0; i < Math.min(6, h.size()); i++) {
            String[] parts = h.get(i).split(";");
            hist.add(Component.text("Saison " + parts[0] + "  ", Text.GRAY).append(Component.text(parts.length > 1 ? parts[1] : "", Text.DARK)));
            for (int k = 2; k < parts.length; k++) {
                String[] w = parts[k].split(":");
                if (w.length >= 3) hist.add(Component.text("  " + w[0] + ". " + w[1] + "  ", Text.WHITE)
                        .append(Component.text(w[2] + " pts", Text.DARK)));
            }
        }
        inv.setItem(49, Items.item(Material.BOOK, Component.text("Saisons précédentes", Text.WHITE), hist, false));

        Bundle top10 = Bundle.of(plugin.configs().events().getConfigurationSection("season.rewards.top10"));
        Bundle part = Bundle.of(plugin.configs().events().getConfigurationSection("season.rewards.participation"));
        List<Component> rw = new ArrayList<>();
        rw.add(Items.line("Top 4 à 10 :"));
        for (Component c : plugin.rewards().describe(top10)) rw.add(Component.text(" ").append(c));
        rw.add(Items.EMPTY);
        rw.add(Items.line("Participation (" + plugin.configs().events().getInt("season.participation-min-points", 100) + " pts) :"));
        for (Component c : plugin.rewards().describe(part)) rw.add(Component.text(" ").append(c));
        inv.setItem(53, Items.item(Material.CHEST, Component.text("Autres récompenses", Text.WHITE), rw, false));

        Items.fill(inv);
        player.openInventory(inv);
    }

    private ItemStack head(Profile p, TextColor color, String label) {
        ItemStack item = plugin.heads().head(p.uuid(), p.name);
        boolean online = Bukkit.getPlayer(p.uuid()) != null;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(online ? "● En ligne" : "○ Hors ligne", online ? TextColor.color(0x7BD88F) : Text.DARK));
        lore.add(Items.EMPTY);
        lore.add(Component.text("✦ ", plugin.text().accent()).append(Items.kv("Points", Text.num(plugin.seasons().points(p)))));
        lore.add(Items.kv("Missions terminées", Text.num(p.missionsDone)));
        Component name = Component.text(label + "  ", color).decorate(TextDecoration.BOLD)
                .append(Component.text(p.name, Text.WHITE).decoration(TextDecoration.BOLD, false));
        return Items.decorate(item, name, lore, false);
    }

    private void refreshWhenLoaded(Player viewer, Inventory inv, int slot, Profile p, TextColor color, String label) {
        if (plugin.heads().hasSkin(p.uuid())) return;
        plugin.heads().load(p.uuid(), p.name, () -> {
            if (viewer.isOnline() && viewer.getOpenInventory().getTopInventory() == inv) inv.setItem(slot, head(p, color, label));
        });
    }
}
