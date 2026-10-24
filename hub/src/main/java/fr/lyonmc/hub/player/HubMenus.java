package fr.lyonmc.hub.player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Menus du hub (différents du /menu de la survie) : rejoindre la survie, profil, classements, cosmétiques... */
public final class HubMenus implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final class Holder implements InventoryHolder {
        final Map<Integer, String> actions = new HashMap<>();
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final HubPlayers players;

    HubMenus(JavaPlugin plugin, HubPlayers players) {
        this.plugin = plugin;
        this.players = players;
    }

    /** Lance l'action d'un objet ou d'un bouton du hub. */
    public void run(Player p, String action) {
        if (action == null) return;
        if (action.equals("menu")) openHub(p);
        else if (action.equals("jouer")) openPlay(p);
        else if (action.equals("survie")) goSurvival(p);
        else if (action.equals("site")) {
            p.closeInventory();
            p.sendMessage(MM.deserialize("<#e8c872>✦</#e8c872> <gray>Le site du serveur : <white><click:open_url:'https://lyonmc.fr'><u>lyonmc.fr</u></click></white> <dark_gray>(classements, profils, carte en direct)"));
        } else if (action.startsWith("commande:")) {
            p.closeInventory();
            p.performCommand(action.substring(9).trim());
        }
    }

    private void goSurvival(Player p) {
        p.closeInventory();
        if (players.positions().get(p) != null) {
            players.positions().go(p);
            return;
        }
        Location back = players.returnPoint(p);
        if (back == null) {
            // Première partie : le village de départ (ou un endroit au hasard)
            players.positions().firstStart(p);
            return;
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
        p.teleportAsync(back);
    }

    // ───────────────────────────── Menus ─────────────────────────────

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(MM.deserialize(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private void button(Holder h, int slot, ItemStack it, String action) {
        h.inventory.setItem(slot, it);
        if (action != null) h.actions.put(slot, action);
    }

    private void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
    }

    public void openPlay(Player p) {
        Holder h = new Holder();
        h.inventory = Bukkit.createInventory(h, 27, MM.deserialize("<dark_gray>Jouer"));
        Location back = players.positions().get(p) != null ? players.positions().get(p) : players.returnPoint(p);
        button(h, 10, item(Material.GRASS_BLOCK, "<green><bold>Survie", List.of(
                back == null ? "<gray>Ta première aventure : départ" : "<gray>Retourner là où tu étais",
                back == null ? (players.positions().depart() != null ? "<gray>au village de départ." : "<gray>à un endroit au hasard.") : "<dark_gray>" + back.getWorld().getName() + " " + back.getBlockX() + " " + back.getBlockY() + " " + back.getBlockZ(),
                "", "<#e8c872>➤ Cliquer pour jouer")), "survie");
        button(h, 12, item(Material.DIAMOND_PICKAXE, "<aqua><bold>Monde ressources", List.of(
                "<gray>Le monde où miner rapporte le plus :",
                "<gray>plus de minerais, minage rapide,", "<gray>pièces et trésors cachés.",
                "", "<#e8c872>➤ Cliquer pour y aller")), "commande:ressources");
        button(h, 14, item(Material.ENDER_PEARL, "<light_purple><bold>Aventure au hasard", List.of(
                "<gray>Un endroit sûr au hasard", "<gray>du monde survie.", "", "<#e8c872>➤ Cliquer pour partir")), "commande:rtp");
        button(h, 16, item(Material.RED_BED, "<red><bold>Mes homes", List.of(
                "<gray>Revenir à une de tes maisons.", "", "<#e8c872>➤ Cliquer pour choisir")), "commande:homes");
        button(h, 22, item(Material.ARROW, "<white>Retour", List.of()), "menu");
        fill(h.inventory);
        p.openInventory(h.inventory);
    }

    public void openHub(Player p) {
        Holder h = new Holder();
        h.inventory = Bukkit.createInventory(h, 45, MM.deserialize("<dark_gray>Hub · LyonMC"));
        button(h, 13, item(Material.COMPASS, "<green><bold>Jouer", List.of(
                "<gray>Survie, monde ressources,", "<gray>aventure au hasard, homes.", "", "<#e8c872>➤ Cliquer")), "jouer");
        ItemStack head = item(Material.PLAYER_HEAD, "<white><bold>Mon profil", List.of(
                "<gray>Tes succès, ton grade, tes stats.", "", "<#e8c872>➤ Cliquer"));
        if (head.getItemMeta() instanceof SkullMeta sm) {
            sm.setPlayerProfile(p.getPlayerProfile());
            head.setItemMeta(sm);
        }
        button(h, 20, head, "commande:succes");
        button(h, 21, item(Material.GOLD_INGOT, "<gold><bold>Classements", List.of(
                "<gray>Le podium des meilleurs joueurs.", "", "<#e8c872>➤ Cliquer")), "commande:classement");
        button(h, 22, item(Material.NETHER_STAR, "<yellow><bold>Saison", List.of(
                "<gray>Le classement du mois", "<gray>et ses récompenses.", "", "<#e8c872>➤ Cliquer")), "commande:saison");
        button(h, 23, item(Material.WRITABLE_BOOK, "<aqua><bold>Missions", List.of(
                "<gray>Tes missions du jour", "<gray>et de la semaine.", "", "<#e8c872>➤ Cliquer")), "commande:missions");
        button(h, 24, item(Material.CAKE, "<light_purple><bold>Récompense du jour", List.of(
                "<gray>À récupérer chaque jour.", "", "<#e8c872>➤ Cliquer")), "commande:recompense");
        button(h, 29, item(Material.BLAZE_POWDER, "<#ffb35c><bold>Cosmétiques", List.of(
                "<gray>Couleur du pseudo et traînées :", "<gray>c'est au hub qu'on les montre !", "", "<#e8c872>➤ Cliquer")), "commande:cosmetiques");
        button(h, 30, item(Material.NAME_TAG, "<white><bold>Titres et grade", List.of(
                "<gray>Le titre affiché à côté de ton pseudo.", "", "<#e8c872>➤ Cliquer")), "commande:titres");
        button(h, 31, item(Material.EMERALD, "<green><bold>Boutique", List.of(
                "<gray>Titres, cosmétiques, homes.", "", "<#e8c872>➤ Cliquer")), "commande:boutique");
        button(h, 32, item(Material.WHITE_BANNER, "<white><bold>Équipe", List.of(
                "<gray>Crée ou gère ton équipe.", "", "<#e8c872>➤ Cliquer")), "commande:equipe");
        button(h, 33, item(Material.FILLED_MAP, "<white><bold>Site et carte", List.of(
                "<gray>lyonmc.fr : classements, profils,", "<gray>carte du monde en direct.", "", "<#e8c872>➤ Cliquer")), "site");
        // Communauté (plugin COMMUNAUTE)
        int slot = 38;
        if (Bukkit.getPluginCommand("vote") != null) button(h, slot++, item(Material.GOLDEN_APPLE, "<#9be37c><bold>Voter pour LyonMC", List.of(
                "<gray>Une récompense à chaque vote,", "<gray>et le serveur monte dans les classements.", "", "<#e8c872>➤ Cliquer")), "commande:vote");
        if (Bukkit.getPluginCommand("premierspas") != null) button(h, slot++, item(Material.MAP, "<#e8c872><bold>Premiers pas", List.of(
                "<gray>Les étapes pour bien commencer,", "<gray>avec des récompenses.", "", "<#e8c872>➤ Cliquer")), "commande:premierspas");
        if (Bukkit.getPluginCommand("parrain") != null) button(h, slot++, item(Material.TOTEM_OF_UNDYING, "<#7cd4ff><bold>Parrainage", List.of(
                "<gray>Invite tes amis : vous êtes", "<gray>récompensés tous les deux.", "", "<#e8c872>➤ Cliquer")), "commande:parrain");
        if (Bukkit.getPluginCommand("objectif") != null) button(h, slot, item(Material.BEACON, "<#ffb35c><bold>Objectif de la semaine", List.of(
                "<gray>Un défi pour tout le serveur,", "<gray>une récompense pour chaque participant.", "", "<#e8c872>➤ Cliquer")), "commande:objectif");
        fill(h.inventory);
        p.openInventory(h.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        String action = h.actions.get(e.getRawSlot());
        if (action == null) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        Bukkit.getScheduler().runTask(plugin, () -> run(p, action));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
