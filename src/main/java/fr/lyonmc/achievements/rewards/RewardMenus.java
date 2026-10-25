package fr.lyonmc.achievements.rewards;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.menu.ConfirmMenu;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Menus : titres, cosmétiques, boutique, parcours de paliers, récompense quotidienne. */
public final class RewardMenus {

    private static final int[] GRID = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    private final LyonAchievements plugin;

    public RewardMenus(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private RewardService rewards() {
        return plugin.rewards();
    }

    private TextColor accent() {
        return plugin.text().accent();
    }

    private void nav(MenuHolder holder, Inventory inv, int page, int pages, java.util.function.BiConsumer<Player, Integer> open) {
        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(45, p -> plugin.hub().open(p));
        if (page > 1) {
            inv.setItem(48, Items.item(Material.ARROW, Component.text("Page précédente", Text.WHITE),
                    List.of(Items.dark("Page " + (page - 1) + " / " + pages)), false));
            holder.action(48, p -> open.accept(p, page - 1));
        }
        if (page < pages) {
            inv.setItem(50, Items.item(Material.ARROW, Component.text("Page suivante", Text.WHITE),
                    List.of(Items.dark("Page " + (page + 1) + " / " + pages)), false));
            holder.action(50, p -> open.accept(p, page + 1));
        }
    }

    // ───────────────────────────── Titres ─────────────────────────────

    public void openTitles(Player player, int page) {
        Profile p = plugin.profiles().get(player);
        List<RewardService.TitleDef> list = new ArrayList<>(rewards().titles());
        list.sort(Comparator.comparing((RewardService.TitleDef d) -> !rewards().hasTitle(p, d.id())));
        int pages = Math.max(1, (list.size() + GRID.length - 1) / GRID.length);
        int current = Math.max(1, Math.min(pages, page));

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Titres  ·  " + rewards().unlockedTitles(p)
                + " / " + list.size()));
        holder.inventory(inv);

        List<Component> info = new ArrayList<>();
        info.add(Items.line("Ton titre apparaît devant ton pseudo"));
        info.add(Items.line("dans le chat."));
        info.add(Items.EMPTY);
        info.add(Component.text("Actuel  ", Text.GRAY).append(p.title == null ? Component.text("aucun", Text.DARK) : rewards().titleRaw(p)));
        info.add(Items.EMPTY);
        info.add(Component.text("Grade  ", Text.GRAY).append(plugin.grades().display(player.getUniqueId())).decoration(TextDecoration.ITALIC, false));
        inv.setItem(4, Items.item(Material.NAME_TAG, Component.text("Titres", Text.WHITE).decorate(TextDecoration.BOLD), info, false));

        int start = (current - 1) * GRID.length;
        for (int i = start; i < Math.min(list.size(), start + GRID.length); i++) {
            RewardService.TitleDef def = list.get(i);
            boolean owned = rewards().hasTitle(p, def.id());
            boolean equipped = def.id().equals(p.title) && owned;
            List<Component> lore = new ArrayList<>();
            lore.add(Items.dark(def.description()));
            lore.add(Items.EMPTY);
            if (equipped) lore.add(Component.text("✔ Équipé", accent()));
            else if (owned) lore.add(Items.action("Cliquer pour équiper", accent()));
            else if (def.price() > 0) {
                lore.add(Items.line("En vente : " + plugin.economy().format(def.price())));
                lore.add(Items.action("Cliquer pour l'acheter", accent()));
            } else lore.add(Items.dark("✘ Verrouillé"));
            int slot = GRID[i - start];
            inv.setItem(slot, Items.item(owned ? Material.NAME_TAG : Material.GRAY_DYE,
                    plugin.text().parse(def.display()), lore, equipped));
            if (owned) {
                holder.action(slot, pl -> {
                    rewards().selectTitle(plugin.profiles().get(pl), def.id());
                    pl.sendMessage(plugin.prefixed("<gray>Titre équipé : ").append(plugin.text().parse(def.display())));
                    openTitles(pl, current);
                });
            } else if (def.price() > 0) {
                holder.action(slot, pl -> confirmBuy(pl, plugin.text().parse(def.display()), def.price(),
                        new ItemStack(Material.NAME_TAG), () -> {
                            Profile pr = plugin.profiles().get(pl);
                            pr.titles.add(def.id());
                            pr.title = def.id();
                            pr.dirty();
                        }, () -> openTitles(pl, current)));
            }
        }

        inv.setItem(47, gradeButton(player));
        holder.action(47, this::openGrades);
        inv.setItem(49, Items.item(Material.BARRIER, Component.text("Retirer le titre", Text.WHITE), List.of(), false));
        holder.action(49, pl -> {
            rewards().selectTitle(plugin.profiles().get(pl), null);
            openTitles(pl, current);
        });
        nav(holder, inv, current, pages, this::openTitles);
        Items.fill(inv);
        player.openInventory(inv);
    }

    // ───────────────────────────── Grades ─────────────────────────────

    private static String plain(Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    private ItemStack gradeButton(Player player) {
        GradeService gs = plugin.grades();
        GradeService.Grade natural = gs.of(player.getUniqueId());
        GradeService.Grade next = gs.next(natural);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Affiché  ", Text.GRAY).append(gs.display(player.getUniqueId())).decoration(TextDecoration.ITALIC, false));
        lore.add(Items.dark(String.format(java.util.Locale.FRANCE, "%.1f %% des succès", gs.percent(player.getUniqueId()))));
        lore.add(Items.EMPTY);
        if (gs.isTop(natural)) {
            lore.add(Items.line("Tu es Légende : choisis le grade"));
            lore.add(Items.line("que tu veux afficher."));
            lore.add(Items.action("Cliquer pour choisir", accent()));
        } else {
            lore.add(Items.line("Ton grade monte tout seul avec"));
            lore.add(Items.line("tes succès."));
            if (next != null) lore.add(Items.dark("Prochain : " + plain(plugin.text().parse(next.display())) + " à " + (int) next.percent() + " %"));
            lore.add(Items.dark("Les Légendes choisissent leur grade."));
        }
        return Items.item(Material.EXPERIENCE_BOTTLE, Component.text("Grade", Text.WHITE).decorate(TextDecoration.BOLD), lore, gs.isTop(natural));
    }

    public void openGrades(Player player) {
        GradeService gs = plugin.grades();
        boolean legend = gs.isTop(gs.of(player.getUniqueId()));
        GradeService.Grade shown = gs.shown(player.getUniqueId());
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Grade affiché"));
        holder.inventory(inv);
        List<GradeService.Grade> list = gs.choosable();
        int start = 13 - list.size() / 2;
        for (int i = 0; i < list.size() && i < 7; i++) {
            GradeService.Grade g = list.get(i);
            boolean current = g == shown;
            List<Component> lore = new ArrayList<>();
            lore.add(Items.dark("Dès " + (int) g.percent() + " % des succès"));
            lore.add(Items.EMPTY);
            if (!legend) lore.add(Items.dark("✘ Réservé aux Légendes"));
            else if (current) lore.add(Component.text("✔ Affiché", accent()));
            else lore.add(Items.action("Cliquer pour l'afficher", accent()));
            int slot = start + i;
            inv.setItem(slot, Items.item(legend ? Material.NAME_TAG : Material.GRAY_DYE, plugin.text().parse(g.display()), lore, current && legend));
            holder.action(slot, pl -> {
                if (!gs.choose(pl, g.id())) {
                    pl.sendMessage(plugin.prefixed("<gray>Atteins le grade ").append(plugin.text().parse(list.get(list.size() - 1).display()))
                            .append(plugin.text().parse("<gray> pour choisir ton grade.")));
                    pl.playSound(pl.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1f);
                    return;
                }
                pl.sendMessage(plugin.prefixed("<gray>Grade affiché : ").append(plugin.text().parse(g.display())));
                openGrades(pl);
            });
        }
        inv.setItem(18, Items.item(Material.ARROW, Component.text("Retour aux titres", Text.WHITE), List.of(), false));
        holder.action(18, pl -> openTitles(pl, 1));
        Items.fill(inv);
        player.openInventory(inv);
    }

    // ───────────────────────────── Cosmétiques ─────────────────────────────

    public void openCosmetics(Player player) {
        Profile p = plugin.profiles().get(player);
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Cosmétiques"));
        holder.inventory(inv);

        List<Component> info = new ArrayList<>();
        info.add(Component.text("Pseudo  ", Text.GRAY).append(rewards().coloredName(p.uuid(), player.getName(), Text.WHITE)));
        RewardService.CosmeticDef trail = rewards().cosmetic("trail", p.trail);
        info.add(Items.kv("Traînée", trail == null ? "aucune" : trail.display()));
        info.add(Items.EMPTY);
        info.add(Items.dark("Couleurs en haut, traînées en bas."));
        info.add(Items.dark("Les cosmétiques se débloquent avec"));
        info.add(Items.dark("les paliers (/parcours) ou à la boutique."));
        inv.setItem(4, Items.item(Material.BLAZE_POWDER, Component.text("Cosmétiques", Text.WHITE).decorate(TextDecoration.BOLD), info, false));

        int[] colorSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int[] trailSlots = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        List<RewardService.CosmeticDef> colors = new ArrayList<>(rewards().colors());
        List<RewardService.CosmeticDef> trails = new ArrayList<>(rewards().trails());
        for (int i = 0; i < Math.min(colors.size(), colorSlots.length); i++) {
            cosmeticItem(holder, inv, colorSlots[i], p, player, colors.get(i));
        }
        for (int i = 0; i < Math.min(trails.size(), trailSlots.length); i++) {
            cosmeticItem(holder, inv, trailSlots[i], p, player, trails.get(i));
        }

        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(45, pl -> plugin.hub().open(pl));
        inv.setItem(48, Items.item(Material.WHITE_DYE, Component.text("Retirer la couleur", Text.WHITE), List.of(), false));
        holder.action(48, pl -> {
            Profile pr = plugin.profiles().get(pl);
            pr.color = null;
            pr.dirty();
            openCosmetics(pl);
        });
        inv.setItem(50, Items.item(Material.GUNPOWDER, Component.text("Retirer la traînée", Text.WHITE), List.of(), false));
        holder.action(50, pl -> {
            Profile pr = plugin.profiles().get(pl);
            pr.trail = null;
            pr.dirty();
            openCosmetics(pl);
        });
        Items.fill(inv);
        player.openInventory(inv);
    }

    private void cosmeticItem(MenuHolder holder, Inventory inv, int slot, Profile p, Player player, RewardService.CosmeticDef def) {
        boolean owned = rewards().hasCosmetic(p, def.kind(), def.id());
        boolean color = def.kind().equals("color");
        boolean equipped = owned && def.id().equals(color ? p.color : p.trail);
        Component name = color ? rewards().colorPreview(def, def.display()) : Component.text(def.display(), Text.WHITE);
        List<Component> lore = new ArrayList<>();
        lore.add(Items.dark(color ? "Couleur du pseudo" : "Traînée de particules"));
        if (color) lore.add(Component.text("Aperçu  ", Text.GRAY).append(rewards().colorPreview(def, player.getName())));
        lore.add(Items.EMPTY);
        if (equipped) lore.add(Component.text("✔ Équipé", accent()));
        else if (owned) lore.add(Items.action("Cliquer pour équiper", accent()));
        else if (def.price() > 0) {
            lore.add(Items.line("En vente : " + plugin.economy().format(def.price())));
            lore.add(Items.action("Cliquer pour l'acheter", accent()));
        } else {
            lore.add(Items.dark("✘ Verrouillé"));
            String how = unlockedBy(def);
            if (how != null) lore.add(Items.dark(how));
        }
        inv.setItem(slot, Items.item(owned ? def.icon() : Material.GRAY_DYE, name, lore, equipped));
        if (owned) {
            holder.action(slot, pl -> {
                Profile pr = plugin.profiles().get(pl);
                if (color) pr.color = def.id();
                else pr.trail = def.id();
                pr.dirty();
                plugin.custom().grant(pl, "style");
                openCosmetics(pl);
            });
        } else if (def.price() > 0) {
            holder.action(slot, pl -> confirmBuy(pl, name, def.price(), new ItemStack(def.icon()), () -> {
                Profile pr = plugin.profiles().get(pl);
                rewards().unlockCosmetic(pr, def.kind(), def.id());
                if (color) pr.color = def.id();
                else pr.trail = def.id();
                pr.dirty();
                plugin.custom().grant(pl, "style");
            }, () -> openCosmetics(pl)));
        }
    }

    private String unlockedBy(RewardService.CosmeticDef def) {
        for (RewardService.Palier pa : rewards().paliers()) {
            if (def.kind().equals("color") && def.id().equals(pa.reward().color())) return "Palier : " + pa.name();
            if (def.kind().equals("trail") && def.id().equals(pa.reward().trail())) return "Palier : " + pa.name();
        }
        for (var job : plugin.jobs().all()) {
            for (var e : job.milestones().entrySet()) {
                var b = e.getValue();
                if (def.kind().equals("color") && def.id().equals(b.color()) || def.kind().equals("trail") && def.id().equals(b.trail())) {
                    return "Métier " + job.name() + ", niveau " + e.getKey() + " (/metiers)";
                }
            }
        }
        return null;
    }

    // ───────────────────────────── Boutique ─────────────────────────────

    private record ShopEntry(String kind, String id, Component name, Material icon, long price, boolean owned, String detail) {
    }

    public void openShop(Player player, int page) {
        Profile p = plugin.profiles().get(player);
        List<ShopEntry> entries = new ArrayList<>();

        long homePrice = homePrice(p);
        int maxHomes = plugin.configs().rewards().getInt("shop.home-slot.max", 3);
        entries.add(new ShopEntry("home", "home", Component.text("+1 emplacement de home", Text.WHITE), Material.RED_BED,
                homePrice, p.boughtHomes >= maxHomes, "Achetés : " + p.boughtHomes + " / " + maxHomes));
        for (RewardService.TitleDef t : rewards().titles()) {
            if (t.price() <= 0) continue;
            entries.add(new ShopEntry("title", t.id(), plugin.text().parse(t.display()), Material.NAME_TAG, t.price(),
                    rewards().hasTitle(p, t.id()), "Titre"));
        }
        for (RewardService.CosmeticDef c : rewards().colors()) {
            if (c.price() <= 0) continue;
            entries.add(new ShopEntry("color", c.id(), rewards().colorPreview(c, c.display()), c.icon(), c.price(),
                    rewards().hasCosmetic(p, "color", c.id()), "Couleur de pseudo"));
        }
        for (RewardService.CosmeticDef c : rewards().trails()) {
            if (c.price() <= 0) continue;
            entries.add(new ShopEntry("trail", c.id(), Component.text(c.display(), Text.WHITE), c.icon(), c.price(),
                    rewards().hasCosmetic(p, "trail", c.id()), "Traînée de particules"));
        }

        int pages = Math.max(1, (entries.size() + GRID.length - 1) / GRID.length);
        int current = Math.max(1, Math.min(pages, page));
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Boutique  ·  " + plugin.economy().format(p.coins)));
        holder.inventory(inv);

        inv.setItem(4, Items.item(Material.EMERALD, Component.text("Boutique", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.kv("Ton solde", plugin.economy().format(p.coins)),
                Items.EMPTY,
                Items.dark("Gagne des pièces avec les succès,"),
                Items.dark("les missions, la récompense du jour,"),
                Items.dark("le boss et l'hôtel des ventes.")), false));

        int start = (current - 1) * GRID.length;
        for (int i = start; i < Math.min(entries.size(), start + GRID.length); i++) {
            ShopEntry e = entries.get(i);
            int slot = GRID[i - start];
            List<Component> lore = new ArrayList<>();
            lore.add(Items.dark(e.detail()));
            lore.add(Items.EMPTY);
            if (e.owned()) {
                lore.add(Component.text(e.kind().equals("home") ? "Maximum atteint" : "✔ Déjà possédé", accent()));
            } else {
                lore.add(Items.kv("Prix", plugin.economy().format(e.price())));
                lore.add(p.coins >= e.price() ? Items.action("Cliquer pour acheter", accent())
                        : Items.dark("Il te manque " + plugin.economy().format(e.price() - p.coins)));
            }
            inv.setItem(slot, Items.item(e.icon(), e.name(), lore, e.owned()));
            if (e.owned()) continue;
            holder.action(slot, pl -> confirmBuy(pl, e.name(), e.price(), new ItemStack(e.icon()), () -> {
                Profile pr = plugin.profiles().get(pl);
                switch (e.kind()) {
                    case "home" -> pr.boughtHomes++;
                    case "title" -> {
                        pr.titles.add(e.id());
                        pr.title = e.id();
                    }
                    case "color" -> {
                        rewards().unlockCosmetic(pr, "color", e.id());
                        pr.color = e.id();
                        plugin.custom().grant(pl, "style");
                    }
                    case "trail" -> {
                        rewards().unlockCosmetic(pr, "trail", e.id());
                        pr.trail = e.id();
                        plugin.custom().grant(pl, "style");
                    }
                    default -> {
                    }
                }
                pr.dirty();
            }, () -> openShop(pl, current)));
        }
        nav(holder, inv, current, pages, this::openShop);
        Items.fill(inv);
        player.openInventory(inv);
    }

    private long homePrice(Profile p) {
        long base = plugin.configs().rewards().getLong("shop.home-slot.price", 4000);
        long inc = plugin.configs().rewards().getLong("shop.home-slot.price-increase", 2000);
        return base + inc * p.boughtHomes;
    }

    /** Confirmation d'achat puis débit et action. */
    private void confirmBuy(Player player, Component name, long price, ItemStack preview, Runnable onBought, Runnable reopen) {
        ConfirmMenu.open(player, "Acheter pour " + plugin.economy().format(price), Items.decorate(preview, name, List.of(), false),
                List.of(Items.kv("Prix", plugin.economy().format(price)),
                        Items.kv("Ton solde", plugin.economy().format(plugin.economy().balance(player.getUniqueId())))),
                pl -> {
                    if (!plugin.economy().withdraw(pl.getUniqueId(), price)) {
                        plugin.msg(pl, "<gray>Tu n'as pas assez de " + plugin.economy().plural() + ".");
                        pl.playSound(pl.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                        return;
                    }
                    onBought.run();
                    pl.playSound(pl.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                    pl.sendMessage(plugin.prefixed("<gray>Achat effectué : ").append(name));
                    plugin.rewards().check(pl);
                    reopen.run();
                }, pl -> reopen.run());
    }

    // ───────────────────────────── Parcours ─────────────────────────────

    public void openPath(Player player, int page) {
        Profile p = plugin.profiles().get(player);
        List<RewardService.Palier> list = rewards().paliers();
        int done = 0;
        for (RewardService.Palier pa : list) if (p.claimed.contains(pa.id())) done++;
        int pages = Math.max(1, (list.size() + GRID.length - 1) / GRID.length);
        int current = Math.max(1, Math.min(pages, page));

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Parcours  ·  " + done + " / " + list.size()));
        holder.inventory(inv);
        inv.setItem(4, Items.item(Material.GOLD_INGOT, Component.text("Parcours de récompenses", Text.WHITE).decorate(TextDecoration.BOLD),
                List.of(Items.kv("Paliers", done + " / " + list.size()),
                        plugin.text().bar(done, list.size(), 30),
                        Items.EMPTY,
                        Items.dark("Chaque palier se débloque tout seul"),
                        Items.dark("quand tu remplis sa condition.")), false));

        int start = (current - 1) * GRID.length;
        for (int i = start; i < Math.min(list.size(), start + GRID.length); i++) {
            RewardService.Palier pa = list.get(i);
            boolean claimed = p.claimed.contains(pa.id());
            double cur = rewards().current(player, p, pa.condition());
            double target = rewards().target(pa.condition());
            List<Component> lore = new ArrayList<>();
            lore.add(Items.line(rewards().describe(pa.condition())));
            if (!claimed && !pa.condition().type().equals("category")) {
                int c = (int) Math.floor(Math.min(cur, target));
                int t = (int) Math.ceil(target);
                lore.add(Component.text().append(plugin.text().bar(c, t, 20))
                        .append(Component.text("  " + Text.num(c) + "/" + Text.num(t), Text.WHITE)).build());
            }
            lore.add(Items.EMPTY);
            lore.add(Items.line("Récompenses :"));
            for (Component l : rewards().describe(pa.reward())) lore.add(Component.text(" ").append(l));
            lore.add(Items.EMPTY);
            lore.add(claimed ? Component.text("✔ Débloqué", accent()) : Items.dark("✘ Pas encore débloqué"));
            String prefix = (i + 1) + ". ";
            inv.setItem(GRID[i - start], Items.item(claimed ? pa.icon() : Material.GRAY_DYE,
                    Component.text(prefix + pa.name(), claimed ? accent() : Text.GRAY), lore, claimed));
        }
        nav(holder, inv, current, pages, this::openPath);
        Items.fill(inv);
        player.openInventory(inv);
    }

    // ───────────────────────────── Récompense quotidienne ─────────────────────────────

    public void openDaily(Player player) {
        DailyRewardService daily = plugin.daily();
        Profile p = plugin.profiles().get(player);
        boolean can = daily.canClaim(p);
        int streak = can ? daily.streakIfClaimed(p) : p.loginStreak;
        int todayIndex = daily.dayIndex(streak);
        List<Bundle> cycle = daily.cycle();

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Récompense du jour"));
        holder.inventory(inv);
        inv.setItem(4, Items.item(Material.CLOCK, Component.text("Série de connexion", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.kv("Série actuelle", daily.currentStreak(p) + " jour(s)"),
                Items.kv("Record", p.bestLoginStreak + " jour(s)"),
                Items.EMPTY,
                Items.dark("Reviens chaque jour : la récompense"),
                Items.dark("grossit jusqu'au 7e jour."),
                Items.dark("Rater un jour remet la série à zéro.")), false));

        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < Math.min(cycle.size(), slots.length); i++) {
            Bundle b = cycle.get(i);
            List<Component> lore = new ArrayList<>(rewards().describe(b));
            lore.add(Items.EMPTY);
            Material icon;
            boolean glint = false;
            String state;
            if (i < todayIndex || (i == todayIndex && !can)) {
                icon = Material.LIME_STAINED_GLASS_PANE;
                state = "✔ Récupéré";
            } else if (i == todayIndex) {
                icon = i == cycle.size() - 1 ? Material.ENDER_CHEST : Material.CHEST;
                glint = true;
                state = "➤ Cliquer pour récupérer";
            } else {
                icon = i == cycle.size() - 1 ? Material.ENDER_CHEST : Material.BARREL;
                state = "À venir";
            }
            lore.add(Component.text(state, i == todayIndex && can ? accent() : Text.DARK));
            inv.setItem(slots[i], Items.item(icon, Component.text("Jour " + (i + 1), i == todayIndex ? accent() : Text.WHITE), lore, glint));
            if (i == todayIndex && can) {
                holder.action(slots[i], pl -> {
                    plugin.daily().claim(pl);
                    openDaily(pl);
                });
            }
        }
        inv.setItem(22, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(22, pl -> plugin.hub().open(pl));
        Items.fill(inv);
        player.openInventory(inv);
    }
}
