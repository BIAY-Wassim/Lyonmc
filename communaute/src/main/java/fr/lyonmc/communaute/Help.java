package fr.lyonmc.communaute;

import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Centre d'aide (/aide), règlement (/regles), questions fréquentes et astuces dans le chat.
 * Chaque sujet s'ouvre dans un livre, avec des commandes cliquables.
 */
public final class Help implements Listener {

    private static final int[] GRID = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    /** Longueur conseillée d'une page de livre (en caractères visibles). */
    private static final int PAGE = 210;

    private final Communaute plugin;
    private int tipIndex;
    private long lastTip = System.currentTimeMillis();

    Help(Communaute plugin) {
        this.plugin = plugin;
    }

    // ───────────────────────────── Sujets ─────────────────────────────

    /** Sujets affichés (ceux qui demandent un plugin absent sont cachés). */
    private List<String> topics() {
        List<String> out = new ArrayList<>();
        ConfigurationSection s = plugin.section("aide.sujets");
        if (s == null) return out;
        for (String id : s.getKeys(false)) {
            String needs = s.getString(id + ".plugin");
            if (needs != null && !needs.isBlank()) {
                var pl = Bukkit.getPluginManager().getPlugin(needs);
                if (pl == null || !pl.isEnabled()) continue;
            }
            out.add(id);
        }
        return out;
    }

    private ConfigurationSection topic(String id) {
        return plugin.section("aide.sujets." + id);
    }

    void command(Player p, String[] args) {
        if (args.length > 0) {
            String id = args[0].toLowerCase(Locale.ROOT);
            if (topics().contains(id)) {
                openTopic(p, id);
                return;
            }
        }
        openMenu(p);
    }

    public void openMenu(Player p) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Centre d'aide · LyonMC"));
        holder.inventory(inv);
        var accent = plugin.lyon().text().accent();
        inv.setItem(4, Items.item(Material.LECTERN, Component.text("Centre d'aide", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.line("Tout ce qu'il faut savoir pour bien"),
                Items.line("jouer sur LyonMC, expliqué simplement."),
                Items.EMPTY,
                Component.text("Clic gauche ", accent).append(Items.line("sur un sujet : le lire")),
                Component.text("Clic droit ", accent).append(Items.line("sur un sujet : essayer")),
                Items.EMPTY,
                Items.dark("Commande : /aide")), true));
        List<String> topics = topics();
        for (int i = 0; i < topics.size() && i < GRID.length; i++) {
            String id = topics.get(i);
            ConfigurationSection c = topic(id);
            if (c == null) continue;
            List<Component> lore = new ArrayList<>();
            for (String l : c.getStringList("resume")) lore.add(Items.line(l));
            lore.add(Items.EMPTY);
            String tryCmd = c.getString("essayer");
            String direct = c.getString("commande");
            if (direct != null) lore.add(Items.action("Cliquer pour ouvrir", accent));
            else {
                lore.add(Items.action("Clic gauche : lire", accent));
                if (tryCmd != null) lore.add(Items.dark("Clic droit : /" + tryCmd));
            }
            inv.setItem(GRID[i], Items.item(Items.material(c.getString("icone"), Material.BOOK),
                    Component.text(c.getString("nom", id), Text.WHITE).decorate(TextDecoration.BOLD), lore, false));
            holder.click(GRID[i], (pl, type) -> {
                if (direct != null) {
                    pl.closeInventory();
                    pl.performCommand(direct);
                } else if ((type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) && tryCmd != null) {
                    pl.closeInventory();
                    pl.performCommand(tryCmd);
                } else {
                    openTopic(pl, id);
                }
            });
        }
        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(Items.dark("/menu")), false));
        holder.action(45, pl -> pl.performCommand("menu"));
        inv.setItem(49, Items.item(Material.WRITABLE_BOOK, Component.text("Le règlement", Text.WHITE).decorate(TextDecoration.BOLD),
                List.of(Items.line("Les règles du serveur"), Items.EMPTY, Items.action("Cliquer pour lire", accent)), false));
        holder.action(49, this::openRules);
        inv.setItem(53, Items.item(Material.FILLED_MAP, Component.text("Le site lyonmc.fr", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.line("Guide complet, classements,"), Items.line("profils et carte en direct"), Items.EMPTY,
                Items.action("Cliquer pour le lien", accent)), false));
        holder.action(53, pl -> {
            pl.closeInventory();
            pl.sendMessage(plugin.parse("<#e8c872>✦</#e8c872> <gray>Le site : <white><click:open_url:'https://lyonmc.fr'><u>lyonmc.fr</u></click></white> <dark_gray>(guide, classements, carte)"));
        });
        Items.fill(inv);
        p.openInventory(inv);
    }

    public void openTopic(Player p, String id) {
        ConfigurationSection c = topic(id);
        if (c == null) {
            openMenu(p);
            return;
        }
        if (c.getString("commande") != null) {
            p.closeInventory();
            p.performCommand(c.getString("commande"));
            return;
        }
        List<Component> pages = new ArrayList<>();
        if (c.getBoolean("faq")) pages.addAll(faqPages());
        for (String page : c.getStringList("pages")) pages.add(page(page));
        if (pages.isEmpty()) pages.add(plugin.parse("<bold>" + c.getString("nom", id) + "</bold>\n\nRien à lire ici pour l'instant."));
        // Dernière page : retour au centre d'aide
        pages.add(plugin.parse("<bold>Et ensuite ?</bold>\n\n<click:run_command:'/aide'><u>Retour au centre d'aide</u></click>\n\n"
                + "<click:run_command:'/menu'><u>Ouvrir le menu</u></click>\n\n<dark_gray>Toutes les infos aussi sur lyonmc.fr"));
        p.closeInventory();
        p.openBook(Book.book(Component.text(c.getString("nom", id)), Component.text("LyonMC"), pages));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
    }

    private Component page(String mini) {
        return plugin.parse(mini.replace("\\n", "\n"));
    }

    // ───────────────────────────── Questions fréquentes ─────────────────────────────

    List<Map<?, ?>> faq() {
        return plugin.getConfig().isSet("faq") ? plugin.getConfig().getMapList("faq")
                : plugin.getConfig().getDefaults() == null ? List.of() : plugin.getConfig().getDefaults().getMapList("faq");
    }

    private List<Component> faqPages() {
        List<String> blocks = new ArrayList<>();
        for (Map<?, ?> m : faq()) {
            Object q = m.get("question"), r = m.get("reponse");
            if (q == null || r == null) continue;
            blocks.add("<bold>" + q + "</bold>\n" + r);
        }
        return pack("<bold>Questions fréquentes</bold>\n\n", blocks);
    }

    /** Regroupe des blocs de texte en pages de livre de taille raisonnable. */
    private List<Component> pack(String header, List<String> blocks) {
        List<Component> pages = new ArrayList<>();
        StringBuilder cur = new StringBuilder(header);
        int len = visible(header);
        boolean hasBlock = false;
        for (String b : blocks) {
            int bl = visible(b);
            if (hasBlock && len + bl + 2 > PAGE) {
                pages.add(plugin.parse(cur.toString()));
                cur = new StringBuilder();
                len = 0;
                hasBlock = false;
            }
            if (hasBlock) {
                cur.append("\n\n");
                len += 2;
            }
            cur.append(b);
            len += bl;
            hasBlock = true;
        }
        if (hasBlock || len > 0) pages.add(plugin.parse(cur.toString()));
        return pages;
    }

    private int visible(String mini) {
        return PlainTextComponentSerializer.plainText().serialize(plugin.parse(mini)).length();
    }

    // ───────────────────────────── Règlement ─────────────────────────────

    List<Map<?, ?>> rules() {
        return plugin.getConfig().isSet("regles.liste") ? plugin.getConfig().getMapList("regles.liste")
                : plugin.getConfig().getDefaults() == null ? List.of() : plugin.getConfig().getDefaults().getMapList("regles.liste");
    }

    public void openRules(Player p) {
        List<String> blocks = new ArrayList<>();
        int n = 1;
        for (Map<?, ?> m : rules()) {
            Object t = m.get("titre"), x = m.get("texte");
            if (t == null) continue;
            blocks.add("<bold>" + (n++) + ". " + t + "</bold>\n" + (x == null ? "" : x));
        }
        List<Component> pages = new ArrayList<>();
        pages.add(plugin.parse("<bold>Règlement de LyonMC</bold>\n\n" + plugin.getConfig().getString("regles.introduction", "")
                + "\n\n<dark_gray>Tourne la page →"));
        pages.addAll(pack("", blocks));
        pages.add(plugin.parse("<bold>Sanctions</bold>\n\n" + plugin.getConfig().getString("regles.sanctions", "")
                + "\n\n<bold>Signaler</bold>\n/report <joueur> <raison>\n\n<click:run_command:'/aide'><u>Centre d'aide</u></click>"));
        p.closeInventory();
        p.openBook(Book.book(Component.text("Règlement"), Component.text("LyonMC"), pages));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
    }

    // ───────────────────────────── /help → /aide ─────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!plugin.getConfig().getBoolean("aide.remplacer-help", true)) return;
        String msg = e.getMessage();
        String low = msg.toLowerCase(Locale.ROOT);
        if (low.equals("/help") || low.equals("/?") || low.startsWith("/help ") || low.startsWith("/? ")) {
            String rest = msg.substring(msg.indexOf(' ') < 0 ? msg.length() : msg.indexOf(' '));
            // /help 2 (numéro de page de Bukkit) : on ouvre simplement le centre d'aide
            if (rest.trim().matches("\\d+")) rest = "";
            e.setMessage("/aide" + rest);
        }
    }

    // ───────────────────────────── Astuces ─────────────────────────────

    /** Appelé toutes les 30 secondes. */
    void tick() {
        if (!plugin.getConfig().getBoolean("astuces.enabled", true)) return;
        long every = Math.max(1, plugin.getConfig().getLong("astuces.minutes", 7)) * 60_000L;
        long now = System.currentTimeMillis();
        if (now - lastTip < every) return;
        lastTip = now;
        List<String> list = plugin.getConfig().getStringList("astuces.liste");
        if (list.isEmpty() && plugin.getConfig().getDefaults() != null) list = plugin.getConfig().getDefaults().getStringList("astuces.liste");
        if (list.isEmpty()) return;
        String tip = list.get(tipIndex++ % list.size());
        Component line = plugin.parse(plugin.getConfig().getString("astuces.prefixe", "<#e8c872>Astuce</#e8c872> <dark_gray>›</dark_gray> ") + tip);
        double hours = plugin.getConfig().getDouble("astuces.nouveaux-heures", 30);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.store().get(p);
            if (!d.tips || hours > 0 && d.activeSeconds >= hours * 3600) continue;
            p.sendMessage(line);
        }
    }

    void toggleTips(Player p) {
        PlayerData d = plugin.store().get(p);
        d.tips = !d.tips;
        plugin.msg(p, d.tips ? "<gray>Astuces <green>activées</green>." : "<gray>Astuces <red>désactivées</red>. <white>/astuces</white> pour les revoir.");
    }
}
