package fr.lyonmc.achievements.menu;

import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.util.Text;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * Menu graphique des succès :
 *  - accueil : profil, catégories, raccourcis
 *  - listes paginées : chaque succès avec sa progression et les critères restants
 */
public final class MissionsMenu {

    public enum View { CATEGORY, ALMOST, CHALLENGES, ALL }

    public enum Filter {
        MISSING("Manquants"), ALL("Tous"), DONE("Obtenus");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final int PAGE_SIZE = 45;
    private static final Component EMPTY = Component.empty();

    private final AdvancementRegistry registry;
    private final StatsService stats;
    private final Text text;

    public MissionsMenu(AdvancementRegistry registry, StatsService stats, Text text) {
        this.registry = registry;
        this.stats = stats;
        this.text = text;
    }

    /** Progression d'un succès pour un joueur (groupes de critères validés). */
    private record Progress(boolean done, int groupsDone, int groupsTotal, List<List<String>> remaining, long doneTime) {
        double ratio() {
            return groupsTotal == 0 ? 0 : (double) groupsDone / groupsTotal;
        }
    }

    private Progress progress(Player p, AdvancementRegistry.Entry e) {
        AdvancementProgress ap = p.getAdvancementProgress(e.advancement());
        Collection<String> awarded = ap.getAwardedCriteria();
        int done = 0;
        List<List<String>> remaining = new ArrayList<>();
        for (List<String> group : e.requirements()) {
            boolean ok = false;
            for (String c : group) {
                if (awarded.contains(c)) {
                    ok = true;
                    break;
                }
            }
            if (ok) done++;
            else remaining.add(group);
        }
        long time = 0L;
        for (String c : awarded) {
            Date d = ap.getDateAwarded(c);
            if (d != null && d.getTime() > time) time = d.getTime();
        }
        return new Progress(ap.isDone(), done, e.requirements().size(), remaining, time);
    }

    // ───────────────────────────── Accueil ─────────────────────────────

    public void openMain(Player player) {
        PlayerStats s = stats.refreshOnline(player);
        int total = registry.total();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("Succès  ·  " + s.count() + " / " + total));
        holder.inventory(inv);

        frame(inv, 0);
        frame(inv, 5);

        // Profil
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(player);
            head.setItemMeta(sm);
        }
        int rank = stats.rank(player.getUniqueId());
        List<Component> headLore = new ArrayList<>();
        headLore.add(kv("Rang", rank == 0 ? "non classé" : "#" + rank + " sur " + stats.ranking().size()));
        headLore.add(kv("Succès", s.count() + " / " + total));
        headLore.add(text.bar(s.count(), total, 30));
        headLore.add(Component.text(Text.percent(s.count(), total) + " % complété", Text.DARK));
        headLore.add(EMPTY);
        for (AdvancementDisplay.Frame f : List.of(AdvancementDisplay.Frame.TASK, AdvancementDisplay.Frame.GOAL, AdvancementDisplay.Frame.CHALLENGE)) {
            headLore.add(kv(AdvancementRegistry.frameName(f) + "s", s.count(f) + " / " + registry.total(f)));
        }
        AdvancementRegistry.Entry last = s.lastKey() == null ? null : registry.get(s.lastKey());
        if (last != null) {
            headLore.add(EMPTY);
            headLore.add(Component.text("Dernier succès", Text.GRAY));
            headLore.add(last.title().colorIfAbsent(Text.WHITE));
            headLore.add(Component.text(Text.timeAgo(s.lastTime()), Text.DARK));
        }
        inv.setItem(4, decorate(head, Component.text(player.getName(), Text.WHITE).decorate(TextDecoration.BOLD), headLore, false));

        // Catégories (rangée 3, centrées)
        List<AdvancementRegistry.Category> cats = registry.usedCategories();
        int n = Math.min(cats.size(), 7);
        int[] slots = centered(18, n);
        for (int i = 0; i < n; i++) {
            AdvancementRegistry.Category c = cats.get(i);
            int done = s.count(c.id());
            int tot = registry.total(c.id());
            int challengesLeft = 0;
            for (AdvancementRegistry.Entry e : registry.entries()) {
                if (e.category().equals(c.id()) && e.frame() == AdvancementDisplay.Frame.CHALLENGE && !s.hasDone(e.key())) {
                    challengesLeft++;
                }
            }
            List<Component> lore = new ArrayList<>();
            lore.add(kv("Progression", done + " / " + tot));
            lore.add(text.bar(done, tot, 30));
            lore.add(Component.text(Text.percent(done, tot) + " %", Text.DARK));
            lore.add(EMPTY);
            if (done >= tot) {
                lore.add(Component.text("Catégorie terminée", text.accent()));
            } else {
                lore.add(kv("Restants", String.valueOf(tot - done)));
                if (challengesLeft > 0) lore.add(kv("Dont défis", String.valueOf(challengesLeft)));
            }
            lore.add(EMPTY);
            lore.add(Component.text("Cliquer pour ouvrir", text.accent()));
            ItemStack icon = c.id().equals(AdvancementRegistry.OTHER) ? new ItemStack(Material.BOOK) : registry.rootIcon(c.id());
            inv.setItem(slots[i], decorate(icon, Component.text(c.name(), Text.WHITE).decorate(TextDecoration.BOLD), lore, done >= tot));
            String id = c.id();
            holder.action(slots[i], p -> openList(p, View.CATEGORY, id, Filter.MISSING, 1));
        }

        // Raccourcis (rangée 5)
        List<Item> almost = items(player, View.ALMOST, null, Filter.MISSING);
        inv.setItem(38, decorate(new ItemStack(Material.SPYGLASS), title("Presque terminés"), List.of(
                Component.text("Les succès à ta portée,", Text.GRAY),
                Component.text("classés du plus avancé au moins avancé.", Text.GRAY),
                EMPTY,
                kv("Disponibles", String.valueOf(almost.size())),
                EMPTY,
                Component.text("Cliquer pour ouvrir", text.accent())), false));
        holder.action(38, p -> openList(p, View.ALMOST, null, Filter.MISSING, 1));

        int chDone = s.count(AdvancementDisplay.Frame.CHALLENGE);
        int chTotal = registry.total(AdvancementDisplay.Frame.CHALLENGE);
        inv.setItem(40, decorate(new ItemStack(Material.NETHER_STAR), title("Défis"), List.of(
                Component.text("Les succès les plus difficiles du jeu.", Text.GRAY),
                EMPTY,
                kv("Réussis", chDone + " / " + chTotal),
                text.bar(chDone, chTotal, 30),
                EMPTY,
                Component.text("Cliquer pour ouvrir", text.accent())), false));
        holder.action(40, p -> openList(p, View.CHALLENGES, null, Filter.MISSING, 1));

        inv.setItem(42, decorate(new ItemStack(Material.WRITABLE_BOOK), title("Tous les succès"), List.of(
                Component.text("La liste complète, toutes catégories.", Text.GRAY),
                EMPTY,
                kv("Restants", String.valueOf(total - s.count())),
                EMPTY,
                Component.text("Cliquer pour ouvrir", text.accent())), false));
        holder.action(42, p -> openList(p, View.ALL, null, Filter.MISSING, 1));

        // Barre du bas
        inv.setItem(48, decorate(new ItemStack(Material.PAPER), title("Classement"),
                List.of(Component.text("Afficher le classement dans le chat", Text.GRAY)), false));
        holder.action(48, p -> {
            p.closeInventory();
            p.performCommand("succes top");
        });
        inv.setItem(49, decorate(new ItemStack(Material.BARRIER), title("Fermer"), List.of(), false));
        holder.action(49, Player::closeInventory);
        inv.setItem(50, decorate(new ItemStack(Material.COMPASS), title("Scoreboard"),
                List.of(Component.text("Afficher ou masquer le scoreboard", Text.GRAY)), false));
        holder.action(50, p -> p.performCommand("succes sidebar"));

        player.openInventory(inv);
    }

    // ───────────────────────────── Listes ─────────────────────────────

    private record Item(AdvancementRegistry.Entry entry, Progress progress) {
    }

    private List<Item> items(Player player, View view, String category, Filter filter) {
        List<Item> out = new ArrayList<>();
        for (AdvancementRegistry.Entry e : registry.entries()) {
            if (view == View.CATEGORY && !e.category().equals(category)) continue;
            if (view == View.CHALLENGES && e.frame() != AdvancementDisplay.Frame.CHALLENGE) continue;
            Progress pr = progress(player, e);
            if (filter == Filter.MISSING && pr.done()) continue;
            if (filter == Filter.DONE && !pr.done()) continue;
            if (view == View.ALMOST) {
                if (pr.done() || e.hidden()) continue;
                boolean started = pr.groupsTotal() > 1 && pr.groupsDone() > 0;
                boolean unlocked = parentDone(player, e);
                if (!started && !unlocked) continue;
            }
            out.add(new Item(e, pr));
        }
        if (view == View.ALMOST) {
            out.sort(Comparator.comparingDouble((Item i) -> i.progress().ratio()).reversed()
                    .thenComparingInt(i -> i.entry().frame().ordinal()));
        }
        return out;
    }

    public void openList(Player player, View view, String category, Filter filter, int page) {
        List<Item> list = items(player, view, category, filter);
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(1, Math.min(pages, page));

        String name = switch (view) {
            case CATEGORY -> registry.category(category).name();
            case ALMOST -> "Presque terminés";
            case CHALLENGES -> "Défis";
            case ALL -> "Tous les succès";
        };
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(
                name + "  ·  " + (view == View.ALMOST ? list.size() + " à portée" : filter.label.toLowerCase(Locale.ROOT))
                        + (pages > 1 ? "  ·  " + current + "/" + pages : "")));
        holder.inventory(inv);

        int start = (current - 1) * PAGE_SIZE;
        for (int i = start; i < Math.min(list.size(), start + PAGE_SIZE); i++) {
            inv.setItem(i - start, advancementItem(player, list.get(i)));
        }
        if (list.isEmpty()) {
            inv.setItem(22, decorate(new ItemStack(Material.LIME_DYE), title(filter == Filter.MISSING ? "Tout est terminé" : "Rien à afficher"),
                    List.of(Component.text(filter == Filter.MISSING ? "Il ne te reste rien à faire ici." : "Aucun succès dans cette liste.", Text.GRAY)), true));
        }

        frame(inv, 5);
        inv.setItem(45, decorate(new ItemStack(Material.ARROW), title("Retour"), List.of(), false));
        holder.action(45, this::openMain);

        if (current > 1) {
            inv.setItem(47, decorate(new ItemStack(Material.ARROW), title("Page précédente"),
                    List.of(Component.text("Page " + (current - 1) + " / " + pages, Text.DARK)), false));
            holder.action(47, p -> openList(p, view, category, filter, current - 1));
        }
        if (current < pages) {
            inv.setItem(51, decorate(new ItemStack(Material.ARROW), title("Page suivante"),
                    List.of(Component.text("Page " + (current + 1) + " / " + pages, Text.DARK)), false));
            holder.action(51, p -> openList(p, view, category, filter, current + 1));
        }

        // Résumé
        int done = 0, tot = 0;
        for (AdvancementRegistry.Entry e : registry.entries()) {
            if (view == View.CATEGORY && !e.category().equals(category)) continue;
            if (view == View.CHALLENGES && e.frame() != AdvancementDisplay.Frame.CHALLENGE) continue;
            tot++;
            if (player.getAdvancementProgress(e.advancement()).isDone()) done++;
        }
        List<Component> info = new ArrayList<>();
        info.add(kv("Progression", done + " / " + tot));
        info.add(text.bar(done, tot, 30));
        info.add(Component.text(Text.percent(done, tot) + " %", Text.DARK));
        info.add(EMPTY);
        info.add(kv("Page", current + " / " + pages));
        inv.setItem(49, decorate(new ItemStack(Material.BOOK), title(name), info, false));

        if (view != View.ALMOST) {
            List<Component> fl = new ArrayList<>();
            for (Filter f : Filter.values()) {
                fl.add(f == filter
                        ? Component.text("› " + f.label, text.accent())
                        : Component.text("  " + f.label, Text.DARK));
            }
            fl.add(EMPTY);
            fl.add(Component.text("Cliquer pour changer", text.accent()));
            inv.setItem(53, decorate(new ItemStack(Material.HOPPER), title("Filtre"), fl, false));
            holder.action(53, p -> openList(p, view, category, filter.next(), 1));
        }

        player.openInventory(inv);
    }

    private ItemStack advancementItem(Player viewer, Item item) {
        AdvancementRegistry.Entry e = item.entry();
        Progress pr = item.progress();
        AdvancementRegistry.Category cat = registry.category(e.category());

        if (e.hidden() && !pr.done()) {
            return decorate(new ItemStack(Material.GRAY_DYE), Component.text("Succès secret", Text.GRAY), List.of(
                    Component.text("Ce succès est caché.", Text.DARK),
                    Component.text("Continue d'explorer pour le découvrir.", Text.DARK),
                    EMPTY,
                    kv("Catégorie", cat.name()),
                    kv("Type", AdvancementRegistry.frameName(e.frame()))), false);
        }

        TextColor titleColor = pr.done()
                ? (e.frame() == AdvancementDisplay.Frame.CHALLENGE ? text.accent() : Text.WHITE)
                : Text.GRAY;
        List<Component> lore = new ArrayList<>();
        lore.add(e.description().colorIfAbsent(Text.DARK));
        lore.add(EMPTY);
        lore.add(kv("Type", AdvancementRegistry.frameName(e.frame())));
        lore.add(kv("Catégorie", cat.name()));
        lore.add(kv("Rareté", String.format(Locale.FRANCE, "%.1f", stats.rarity(e.key())) + " % des joueurs"));

        if (pr.groupsTotal() > 1) {
            lore.add(EMPTY);
            lore.add(kv("Progression", pr.groupsDone() + " / " + pr.groupsTotal()));
            lore.add(text.bar(pr.groupsDone(), pr.groupsTotal(), 30));
            if (!pr.done() && !pr.remaining().isEmpty()) {
                lore.add(EMPTY);
                lore.add(Component.text("Il reste :", Text.GRAY));
                int max = 10;
                for (int i = 0; i < Math.min(max, pr.remaining().size()); i++) {
                    List<String> group = pr.remaining().get(i);
                    Component line = Component.text("  ", Text.DARK);
                    for (int j = 0; j < group.size() && j < 3; j++) {
                        if (j > 0) line = line.append(Component.text(" ou ", Text.DARK));
                        line = line.append(CriteriaNames.of(e.key(), group.get(j)).color(Text.WHITE));
                    }
                    lore.add(line);
                }
                if (pr.remaining().size() > max) {
                    lore.add(Component.text("  et " + (pr.remaining().size() - max) + " autres", Text.DARK));
                }
            }
        }

        if (!pr.done() && e.parentKey() != null && !parentDone(viewer, e)) {
            Component parentTitle = registry.get(e.parentKey()) != null ? registry.get(e.parentKey()).title() : null;
            if (parentTitle != null) {
                lore.add(EMPTY);
                lore.add(Component.text("Débloque d'abord", Text.GRAY));
                lore.add(Component.text("  ").append(parentTitle.color(Text.WHITE)));
            }
        }

        lore.add(EMPTY);
        if (pr.done()) {
            lore.add(Component.text("Obtenu " + Text.timeAgo(pr.doneTime()), text.accent()));
        } else {
            lore.add(Component.text("Pas encore obtenu", Text.DARK));
        }

        ItemStack icon = e.icon().getType().isAir() ? new ItemStack(Material.BOOK) : e.icon().clone();
        return decorate(icon, e.title().color(titleColor), lore, pr.done());
    }

    // ───────────────────────────── Outils ─────────────────────────────

    public void open(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
        openMain(player);
    }

    public void open(Player player, View view, String category, Filter filter, int page) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
        openList(player, view, category, filter, page);
    }

    /** Le succès parent est-il obtenu ? (true s'il n'y en a pas) */
    private boolean parentDone(Player player, AdvancementRegistry.Entry e) {
        if (e.parentKey() == null) return true;
        AdvancementRegistry.Entry parent = registry.get(e.parentKey());
        org.bukkit.advancement.Advancement adv = parent != null ? parent.advancement()
                : Bukkit.getAdvancement(Objects.requireNonNull(org.bukkit.NamespacedKey.fromString(e.parentKey())));
        return adv == null || player.getAdvancementProgress(adv).isDone();
    }

    private Component title(String s) {
        return Component.text(s, Text.WHITE);
    }

    private static Component kv(String key, String value) {
        return Component.text(key + "  ", Text.GRAY).append(Component.text(value, Text.WHITE));
    }

    private static int[] centered(int rowStart, int n) {
        int[] out = new int[n];
        if (n <= 4) {
            // espacés d'une case
            int width = n * 2 - 1;
            int start = rowStart + (9 - width) / 2;
            for (int i = 0; i < n; i++) out[i] = start + i * 2;
        } else {
            int start = rowStart + (9 - n) / 2;
            for (int i = 0; i < n; i++) out[i] = start + i;
        }
        return out;
    }

    private static void frame(Inventory inv, int row) {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setHideTooltip(true);
        pane.setItemMeta(meta);
        for (int i = row * 9; i < row * 9 + 9; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, pane);
        }
    }

    private static ItemStack decorate(ItemStack item, Component name, List<Component> lore, boolean glint) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> clean = new ArrayList<>(lore.size());
        for (Component c : lore) clean.add(c.decoration(TextDecoration.ITALIC, false));
        meta.lore(clean);
        meta.setEnchantmentGlintOverride(glint);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }
}
