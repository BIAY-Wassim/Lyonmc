package fr.lyonmc.achievements.missions;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** Menu /missions : missions du jour et de la semaine. */
public final class MissionMenu {

    private final LyonAchievements plugin;

    public MissionMenu(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        MissionService ms = plugin.missions();
        Profile p = plugin.profiles().get(player);
        ms.ensure(p);

        MenuHolder holder = new MenuHolder();
        int dailyDone = 0;
        for (Profile.MissionSlot s : p.daily) if (s.done) dailyDone++;
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Missions  ·  " + dailyDone + " / " + p.daily.size()
                + " aujourd'hui"));
        holder.inventory(inv);

        ZonedDateTime now = ZonedDateTime.now(plugin.text().zone());
        ZonedDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay(plugin.text().zone());
        ZonedDateTime monday = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(plugin.text().zone());
        int streak = ms.activeStreak(p);
        int bonus = (int) Math.round((ms.streakMultiplier(p) - 1) * 100);

        List<Component> head = new ArrayList<>();
        head.add(Items.kv("Série", streak + " jour" + (streak > 1 ? "s" : "") + "  (record " + p.bestMissionStreak + ")"));
        head.add(Items.kv("Bonus de série", "+" + bonus + " % de pièces"));
        head.add(Items.kv("Missions terminées", Text.num(p.missionsDone)));
        head.add(Items.EMPTY);
        head.add(Items.dark("Termine les " + p.daily.size() + " missions du jour pour"));
        head.add(Items.dark("faire grimper ta série et ton bonus."));
        inv.setItem(4, Items.item(Material.CAMPFIRE, Component.text("Missions", Text.WHITE).decorate(TextDecoration.BOLD), head, streak > 0));

        inv.setItem(13, Items.item(Material.PAPER, Component.text("Missions du jour", plugin.text().accent()), List.of(
                Items.line("Nouvelles missions dans " + until(now, midnight)),
                Items.dark(p.dailyBonusGiven ? "✔ Bonus du jour obtenu" : "Bonus si les " + p.daily.size() + " sont terminées")), p.dailyBonusGiven));
        int[] dailySlots = slots(p.daily.size(), 18);
        for (int i = 0; i < p.daily.size() && i < dailySlots.length; i++) {
            Profile.MissionSlot slot = p.daily.get(i);
            MissionDef def = ms.def(slot.id);
            if (def == null) continue;
            int index = i;
            inv.setItem(dailySlots[i], missionItem(p, def, slot, false));
            if (!slot.done) {
                holder.click(dailySlots[i], (pl, type) -> {
                    if (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) {
                        plugin.missions().reroll(pl, index);
                        open(pl);
                    }
                });
            }
        }

        inv.setItem(31, Items.item(Material.BOOK, Component.text("Missions de la semaine", plugin.text().accent()), List.of(
                Items.line("Nouvelles missions dans " + until(now, monday))), false));
        int[] weeklySlots = slots(p.weekly.size(), 36);
        for (int i = 0; i < p.weekly.size() && i < weeklySlots.length; i++) {
            Profile.MissionSlot slot = p.weekly.get(i);
            MissionDef def = ms.def(slot.id);
            if (def != null) inv.setItem(weeklySlots[i], missionItem(p, def, slot, true));
        }

        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(45, pl -> plugin.hub().open(pl));
        inv.setItem(47, Items.item(Material.KNOWLEDGE_BOOK, Component.text("Succès", Text.WHITE),
                List.of(Items.line("Les succès qu'il te reste à obtenir")), false));
        holder.action(47, pl -> plugin.achievementsMenu().open(pl));
        inv.setItem(49, Items.item(Material.GOLD_INGOT, Component.text("Parcours de récompenses", Text.WHITE),
                List.of(Items.line("Les paliers débloqués par tes missions"), Items.line("et tes succès")), false));
        holder.action(49, pl -> plugin.rewardMenus().openPath(pl, 1));
        inv.setItem(51, Items.item(p.actionbar ? Material.LIME_DYE : Material.GRAY_DYE,
                Component.text("Progression à l'écran : " + (p.actionbar ? "activée" : "désactivée"), Text.WHITE),
                List.of(Items.line("Affiche l'avancement des missions"), Items.line("au-dessus de ta barre d'objets")), false));
        holder.action(51, pl -> {
            Profile pr = plugin.profiles().get(pl);
            pr.actionbar = !pr.actionbar;
            pr.dirty();
            open(pl);
        });
        Items.fill(inv);
        player.openInventory(inv);
    }

    private org.bukkit.inventory.ItemStack missionItem(Profile p, MissionDef def, Profile.MissionSlot slot, boolean weekly) {
        MissionService ms = plugin.missions();
        Bundle reward = Bundle.of(plugin.configs().missions().getConfigurationSection("rewards." + def.tier()))
                .scaled(ms.streakMultiplier(p), 1);
        List<Component> lore = new ArrayList<>();
        lore.add(Items.dark(def.tierName()));
        lore.add(Items.EMPTY);
        lore.add(Component.text().append(plugin.text().bar(slot.progress, def.amount(), 20))
                .append(Component.text("  " + Text.num(slot.progress) + "/" + Text.num(def.amount()), Text.WHITE)).build());
        lore.add(Items.EMPTY);
        lore.add(Items.line("Récompenses :"));
        for (Component c : plugin.rewards().describe(reward)) lore.add(Component.text(" ").append(c));
        lore.add(Items.EMPTY);
        if (slot.done) {
            lore.add(Component.text("✔ Terminée", plugin.text().accent()));
        } else if (!weekly) {
            long price = ms.rerollPrice(p);
            lore.add(Items.dark("Clic droit : changer de mission"));
            lore.add(Items.dark(price == 0 ? "(gratuit)" : "(" + plugin.economy().format(price) + ")"));
        }
        return Items.item(slot.done ? Material.LIME_DYE : def.icon(),
                Component.text(def.name(), slot.done ? plugin.text().accent() : Text.WHITE), lore, slot.done);
    }

    /** Positions centrées sur la rangée qui commence à rowStart (avec une case d'écart si possible). */
    private static int[] slots(int n, int rowStart) {
        int[] out = new int[Math.min(n, 9)];
        if (n <= 4) {
            int width = n * 2 - 1;
            int start = rowStart + (9 - width) / 2;
            for (int i = 0; i < out.length; i++) out[i] = start + i * 2;
        } else {
            int start = rowStart + (9 - out.length) / 2;
            for (int i = 0; i < out.length; i++) out[i] = start + i;
        }
        return out;
    }

    private static String until(ZonedDateTime now, ZonedDateTime target) {
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(now, target));
        long d = minutes / 1440, h = (minutes % 1440) / 60, m = minutes % 60;
        if (d > 0) return d + " j " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        return m + " min";
    }
}
