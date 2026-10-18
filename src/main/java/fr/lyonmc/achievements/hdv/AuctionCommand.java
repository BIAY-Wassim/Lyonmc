package fr.lyonmc.achievements.hdv;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.menu.ConfirmMenu;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /hdv : parcourir, vendre, mes ventes, récupérer. */
public final class AuctionCommand implements TabExecutor {

    private static final int PAGE = 45;

    private final LyonAchievements plugin;

    public AuctionCommand(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private AuctionService hdv() {
        return plugin.auctions();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.msg(sender, "<gray>Commande réservée aux joueurs.");
            return true;
        }
        if (!plugin.module("hdv")) {
            plugin.msg(sender, "<gray>L'hôtel des ventes est désactivé sur ce serveur.");
            return true;
        }
        if (args.length == 0) {
            open(player, 1, false);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "vendre", "sell" -> {
                if (args.length < 2) {
                    plugin.msg(player, "<gray>Usage : <white>/hdv vendre <prix> [quantité]</white> <dark_gray>(objet en main)");
                    return true;
                }
                long price;
                try {
                    price = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    plugin.msg(player, "<gray>Prix invalide.");
                    return true;
                }
                int qty = 0;
                if (args.length > 2) {
                    try {
                        qty = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                hdv().sell(player, price, qty);
            }
            case "recuperer", "récupérer", "collect" -> hdv().collect(player);
            case "ventes", "mesventes" -> open(player, 1, true);
            default -> plugin.msg(player, "<gray>Usage : <white>/hdv</white>, <white>/hdv vendre <prix> [quantité]</white>, "
                    + "<white>/hdv ventes</white>, <white>/hdv recuperer");
        }
        return true;
    }

    public void open(Player player, int page, boolean mine) {
        List<AuctionService.Listing> list = mine ? hdv().of(player.getUniqueId()) : hdv().active();
        int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
        int current = Math.max(1, Math.min(pages, page));
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text((mine ? "Mes ventes" : "Hôtel des ventes")
                + "  ·  " + list.size() + (pages > 1 ? "  ·  " + current + "/" + pages : "")));
        holder.inventory(inv);

        int start = (current - 1) * PAGE;
        for (int i = start; i < Math.min(list.size(), start + PAGE); i++) {
            AuctionService.Listing l = list.get(i);
            int slot = i - start;
            inv.setItem(slot, display(player, l));
            holder.click(slot, (pl, type) -> {
                boolean own = l.seller.equals(pl.getUniqueId());
                if (own || (type == ClickType.SHIFT_RIGHT && pl.hasPermission("lyonmc.admin"))) {
                    ConfirmMenu.open(pl, "Retirer de la vente ?", l.item.clone(), List.of(Items.line("L'objet ira dans ta boîte de récupération.")),
                            pp -> {
                                hdv().cancel(pp, l.id);
                                open(pp, current, mine);
                            }, pp -> open(pp, current, mine));
                    return;
                }
                ConfirmMenu.open(pl, "Acheter pour " + plugin.economy().format(l.price), l.item.clone(), List.of(
                        Items.kv("Prix", plugin.economy().format(l.price)),
                        Items.kv("Vendeur", l.sellerName),
                        Items.kv("Ton solde", plugin.economy().format(plugin.economy().balance(pl.getUniqueId())))),
                        pp -> {
                            hdv().buy(pp, l.id);
                            open(pp, current, mine);
                        }, pp -> open(pp, current, mine));
            });
        }

        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(45, pl -> plugin.hub().open(pl));
        if (current > 1) {
            inv.setItem(46, Items.item(Material.ARROW, Component.text("Page précédente", Text.WHITE), List.of(), false));
            holder.action(46, pl -> open(pl, current - 1, mine));
        }
        inv.setItem(48, Items.item(mine ? Material.CHEST : Material.WRITABLE_BOOK,
                Component.text(mine ? "Toutes les ventes" : "Mes ventes", Text.WHITE),
                List.of(Items.dark(hdv().of(player.getUniqueId()).size() + " / " + hdv().maxListings() + " objets en vente")), false));
        holder.action(48, pl -> open(pl, 1, !mine));
        inv.setItem(49, Items.item(Material.GOLD_BLOCK, Component.text("Hôtel des ventes", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.kv("Ton solde", plugin.economy().format(plugin.economy().balance(player.getUniqueId()))),
                Items.EMPTY,
                Items.line("Vendre : prends l'objet en main"),
                Items.line("et tape /hdv vendre <prix>"),
                Items.EMPTY,
                Items.dark("Taxe de " + (int) hdv().tax() + " % sur chaque vente."),
                Items.dark("Les objets non vendus reviennent dans"),
                Items.dark("ta boîte de récupération.")), false));
        int box = hdv().mailbox(player.getUniqueId()).size();
        inv.setItem(50, Items.item(box > 0 ? Material.ENDER_CHEST : Material.BARREL, Component.text("Récupérer (" + box + ")", Text.WHITE),
                List.of(Items.dark("Objets expirés ou retirés de la vente")), box > 0));
        holder.action(50, pl -> {
            hdv().collect(pl);
            open(pl, current, mine);
        });
        if (current < pages) {
            inv.setItem(52, Items.item(Material.ARROW, Component.text("Page suivante", Text.WHITE), List.of(), false));
            holder.action(52, pl -> open(pl, current + 1, mine));
        }
        Items.frame(inv, 5);
        player.openInventory(inv);
    }

    private ItemStack display(Player viewer, AuctionService.Listing l) {
        ItemStack item = l.item.clone();
        List<Component> lore = new ArrayList<>();
        List<Component> existing = item.lore();
        if (existing != null) lore.addAll(existing);
        lore.add(Component.text("─────────────", Text.DARK));
        lore.add(Items.kv("Prix", plugin.economy().format(l.price)));
        lore.add(Items.kv("Vendeur", l.sellerName));
        long left = Math.max(0, (l.expires - System.currentTimeMillis()) / 1000);
        lore.add(Items.dark("Expire dans " + Text.duration(left)));
        lore.add(Items.EMPTY);
        boolean own = l.seller.equals(viewer.getUniqueId());
        lore.add(own ? Items.action("Cliquer pour retirer de la vente", plugin.text().accent())
                : Items.action("Cliquer pour acheter", plugin.text().accent()));
        item.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        return item;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("vendre", "ventes", "recuperer"));
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
