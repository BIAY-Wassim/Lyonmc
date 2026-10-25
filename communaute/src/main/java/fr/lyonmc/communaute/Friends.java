package fr.lyonmc.communaute;

import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.menu.ConfirmMenu;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /ami : liste d'amis, demandes, qui est connecté et où, alertes de connexion. */
public final class Friends implements Listener {

    private static final long REQUEST_MS = 5 * 60_000L;

    private final Communaute plugin;
    /** Demandes reçues : joueur visé → (demandeur → expiration). */
    private final Map<UUID, Map<UUID, Long>> requests = new ConcurrentHashMap<>();

    Friends(Communaute plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("amis.enabled", true);
    }

    private int max() {
        return plugin.getConfig().getInt("amis.max", 50);
    }

    public boolean areFriends(UUID a, UUID b) {
        PlayerData d = plugin.store().get(a);
        return d != null && d.friends.contains(b);
    }

    /** Joueur connu par son pseudo (connecté ou non). */
    PlayerData known(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return plugin.store().get(online);
        for (PlayerData d : plugin.store().all()) if (d.name != null && d.name.equalsIgnoreCase(name)) return d;
        return null;
    }

    // ───────────────────────────── Commandes ─────────────────────────────

    void command(Player p, String[] args) {
        if (!enabled()) {
            plugin.msg(p, "<gray>Les amis sont désactivés sur ce serveur.");
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String target = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "" -> openMenu(p);
            case "ajouter", "add", "inviter" -> {
                if (target == null) help(p);
                else add(p, target);
            }
            case "accepter", "accept", "oui" -> {
                if (target == null) acceptLatest(p);
                else accept(p, target);
            }
            case "refuser", "deny", "non" -> {
                if (target != null) deny(p, target);
                else help(p);
            }
            case "retirer", "supprimer", "remove", "enlever" -> {
                if (target == null) help(p);
                else remove(p, target);
            }
            case "liste", "list" -> list(p);
            case "aide", "help" -> help(p);
            default -> add(p, args[0]); // « /ami Pseudo » : demande d'ami
        }
    }

    private void help(Player p) {
        p.sendMessage(Component.empty());
        p.sendMessage(plugin.parse("  <#7cd4ff>✦</#7cd4ff> <white><bold>Amis</bold></white>"));
        for (String l : List.of(
                "<white>/ami</white> <dark_gray>ta liste : qui est connecté, où, depuis quand",
                "<white>/ami ajouter <joueur></white> <dark_gray>envoyer une demande d'ami",
                "<white>/ami accepter <joueur></white> <dark_gray>accepter une demande",
                "<white>/ami refuser <joueur></white> <dark_gray>refuser une demande",
                "<white>/ami retirer <joueur></white> <dark_gray>retirer un ami")) {
            p.sendMessage(plugin.parse("  " + l));
        }
        p.sendMessage(Component.empty());
    }

    void add(Player p, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) {
            plugin.msg(p, "<gray>" + name + " n'est pas connecté : il doit être en ligne pour accepter ta demande.");
            return;
        }
        if (t.equals(p)) {
            plugin.msg(p, "<gray>Tu ne peux pas être ton propre ami... mais c'est une belle pensée.");
            return;
        }
        PlayerData me = plugin.store().get(p), other = plugin.store().get(t);
        if (me.friends.contains(t.getUniqueId())) {
            plugin.msg(p, "<gray>" + t.getName() + " est déjà ton ami.");
            return;
        }
        if (me.friends.size() >= max()) {
            plugin.msg(p, "<gray>Tu as déjà " + max() + " amis (le maximum).");
            return;
        }
        if (other.ignored.contains(p.getUniqueId())) {
            plugin.msg(p, "<gray>" + t.getName() + " ne reçoit pas de demandes de ta part.");
            return;
        }
        // Il m'avait déjà demandé : on devient amis tout de suite
        Map<UUID, Long> mine = requests.get(p.getUniqueId());
        if (mine != null && mine.getOrDefault(t.getUniqueId(), 0L) > System.currentTimeMillis()) {
            accept(p, t.getName());
            return;
        }
        requests.computeIfAbsent(t.getUniqueId(), k -> new ConcurrentHashMap<>()).put(p.getUniqueId(), System.currentTimeMillis() + REQUEST_MS);
        plugin.msg(p, "<gray>Demande d'ami envoyée à <white>" + t.getName() + "</white>.");
        t.sendMessage(plugin.lyon().prefixed("<#7cd4ff>✦</#7cd4ff> <white>" + p.getName() + "</white> <gray>veut être ton ami. "
                + "<click:run_command:'/ami accepter " + p.getName() + "'><hover:show_text:'<gray>Devenir amis'><green>[Accepter]</green></hover></click> "
                + "<click:run_command:'/ami refuser " + p.getName() + "'><hover:show_text:'<gray>Refuser la demande'><red>[Refuser]</red></hover></click>"));
        t.playSound(t.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
    }

    private void acceptLatest(Player p) {
        Map<UUID, Long> mine = requests.get(p.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            plugin.msg(p, "<gray>Aucune demande d'ami en attente.");
            return;
        }
        UUID best = null;
        long bestExp = 0;
        for (Map.Entry<UUID, Long> e : mine.entrySet()) {
            if (e.getValue() > bestExp) {
                best = e.getKey();
                bestExp = e.getValue();
            }
        }
        PlayerData d = best == null ? null : plugin.store().get(best);
        if (d != null && d.name != null) accept(p, d.name);
    }

    void accept(Player p, String name) {
        PlayerData other = known(name);
        Map<UUID, Long> mine = requests.get(p.getUniqueId());
        if (other == null || mine == null || mine.getOrDefault(other.uuid, 0L) < System.currentTimeMillis()) {
            plugin.msg(p, "<gray>Pas de demande d'ami de <white>" + name + "</white> (elle a peut-être expiré).");
            return;
        }
        mine.remove(other.uuid);
        PlayerData me = plugin.store().get(p);
        if (me.friends.size() >= max() || other.friends.size() >= max()) {
            plugin.msg(p, "<gray>Une des deux listes d'amis est pleine (" + max() + " amis maximum).");
            return;
        }
        me.friends.add(other.uuid);
        other.friends.add(p.getUniqueId());
        plugin.msg(p, "<#7cd4ff>✦</#7cd4ff> <gray>Tu es maintenant ami avec <white>" + other.name + "</white> !");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        Player o = Bukkit.getPlayer(other.uuid);
        if (o != null) {
            plugin.msg(o, "<#7cd4ff>✦</#7cd4ff> <white>" + p.getName() + "</white> <gray>a accepté ta demande d'ami !");
            o.playSound(o.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        }
    }

    void deny(Player p, String name) {
        PlayerData other = known(name);
        Map<UUID, Long> mine = requests.get(p.getUniqueId());
        if (other != null && mine != null && mine.remove(other.uuid) != null) {
            plugin.msg(p, "<gray>Demande de <white>" + other.name + "</white> refusée.");
        } else {
            plugin.msg(p, "<gray>Pas de demande d'ami de <white>" + name + "</white>.");
        }
    }

    void remove(Player p, String name) {
        PlayerData other = known(name);
        PlayerData me = plugin.store().get(p);
        if (other == null || !me.friends.remove(other.uuid)) {
            plugin.msg(p, "<gray>" + name + " n'est pas dans tes amis.");
            return;
        }
        other.friends.remove(p.getUniqueId());
        plugin.msg(p, "<gray><white>" + other.name + "</white> a été retiré de tes amis.");
    }

    private void list(Player p) {
        PlayerData me = plugin.store().get(p);
        if (me.friends.isEmpty()) {
            plugin.msg(p, "<gray>Tu n'as pas encore d'amis ici. <white>/ami ajouter <joueur></white> pour commencer !");
            return;
        }
        p.sendMessage(plugin.parse("  <#7cd4ff>✦</#7cd4ff> <white><bold>Tes amis</bold></white> <dark_gray>(" + me.friends.size() + ")"));
        for (PlayerData d : sorted(me)) {
            Player o = Bukkit.getPlayer(d.uuid);
            p.sendMessage(plugin.parse("  " + (o != null ? "<green>●</green> <white>" + d.name + "</white> <dark_gray>" + where(o)
                    : "<dark_gray>● " + d.name + " · " + Text.timeAgo(d.lastSeen))));
        }
    }

    private List<PlayerData> sorted(PlayerData me) {
        List<PlayerData> out = new ArrayList<>();
        for (UUID u : me.friends) {
            PlayerData d = plugin.store().get(u);
            if (d != null && d.name != null) out.add(d);
        }
        out.sort(Comparator.comparing((PlayerData d) -> Bukkit.getPlayer(d.uuid) == null)
                .thenComparing(Comparator.comparingLong((PlayerData d) -> d.lastSeen).reversed()));
        return out;
    }

    /** « en survie », « à la mine », « au hub »... */
    String where(Player p) {
        if (plugin.inHub(p)) return "au hub";
        if (plugin.isMine(p.getWorld())) return "à la mine";
        World.Environment env = p.getWorld().getEnvironment();
        if (env == World.Environment.NETHER) return "dans le Nether";
        if (env == World.Environment.THE_END) return "dans l'End";
        return "en survie";
    }

    // ───────────────────────────── Menu ─────────────────────────────

    public void openMenu(Player p) {
        PlayerData me = plugin.store().get(p);
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Amis · " + me.friends.size() + " / " + max()));
        holder.inventory(inv);
        var accent = plugin.lyon().text().accent();
        List<PlayerData> friends = sorted(me);
        for (int i = 0; i < friends.size() && i < 36; i++) {
            PlayerData d = friends.get(i);
            Player o = Bukkit.getPlayer(d.uuid);
            List<Component> lore = new ArrayList<>();
            lore.add(o != null ? Component.text("● En ligne " + where(o), net.kyori.adventure.text.format.NamedTextColor.GREEN)
                    : Items.dark("● Hors ligne · vu " + Text.timeAgo(d.lastSeen)));
            lore.add(Component.text("Grade : ", Text.GRAY).append(plugin.lyon().grades().display(d.uuid)).decoration(TextDecoration.ITALIC, false));
            lore.add(Items.EMPTY);
            if (o != null) {
                lore.add(Items.action("Clic gauche : le rejoindre (/tpa)", accent));
                lore.add(Items.dark("Clic droit : lui écrire (/msg)"));
            }
            lore.add(Items.dark("Maj + clic droit : retirer de tes amis"));
            ItemStack head = plugin.lyon().heads().head(d.uuid, d.name);
            int slot = i;
            inv.setItem(slot, Items.decorate(head, plugin.lyon().rewards().coloredName(d.uuid, d.name, Text.WHITE).decorate(TextDecoration.BOLD), lore, o != null));
            String name = d.name;
            holder.click(slot, (pl, type) -> {
                if (type == ClickType.SHIFT_RIGHT) {
                    ConfirmMenu.open(pl, "Retirer " + name + " ?", null, List.of(Items.line("Il ne sera plus dans tes amis.")),
                            x -> remove(x, name), this::openMenu);
                    return;
                }
                if (Bukkit.getPlayer(d.uuid) == null) return;
                pl.closeInventory();
                if (type.isRightClick()) pl.sendMessage(plugin.parse("<gray>Écris ton message : <white><click:suggest_command:'/msg " + name + " '><u>/msg " + name + " ...</u></click>"));
                else pl.performCommand("tpa " + name);
            });
        }
        if (friends.isEmpty()) {
            inv.setItem(13, Items.item(Material.GRAY_DYE, Component.text("Pas encore d'amis", Text.GRAY), List.of(
                    Items.line("Ajoute un joueur avec"), Items.line("/ami ajouter <pseudo>")), false));
        }
        // Demandes en attente
        Map<UUID, Long> pending = requests.getOrDefault(p.getUniqueId(), Map.of());
        long now = System.currentTimeMillis();
        List<Component> req = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : pending.entrySet()) {
            if (e.getValue() < now) continue;
            PlayerData d = plugin.store().get(e.getKey());
            if (d != null) req.add(Component.text("• " + d.name, Text.WHITE));
        }
        if (req.isEmpty()) req.add(Items.dark("Aucune pour l'instant"));
        else {
            req.add(Items.EMPTY);
            req.add(Items.action("Cliquer : accepter la plus récente", accent));
        }
        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(45, pl -> pl.performCommand("menu"));
        inv.setItem(47, Items.item(Material.PAPER, Component.text("Demandes reçues", Text.WHITE).decorate(TextDecoration.BOLD), req, req.size() > 1));
        holder.action(47, pl -> {
            pl.closeInventory();
            acceptLatest(pl);
        });
        inv.setItem(49, Items.item(Material.PLAYER_HEAD, Component.text("Ajouter un ami", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.line("Il doit être connecté pour accepter."), Items.EMPTY, Items.action("Cliquer pour écrire la commande", accent)), false));
        holder.action(49, pl -> {
            pl.closeInventory();
            pl.sendMessage(plugin.parse("<gray>Écris le pseudo : <white><click:suggest_command:'/ami ajouter '><u>/ami ajouter ...</u></click>"));
        });
        String mode = me.pmMode;
        inv.setItem(51, Items.item(Material.WRITABLE_BOOK, Component.text("Messages privés : " + modeName(mode), Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.line("Qui peut t'envoyer des messages privés"),
                Items.EMPTY,
                Items.action("Cliquer pour changer", accent)), false));
        holder.action(51, pl -> {
            PlayerData d = plugin.store().get(pl);
            d.pmMode = switch (d.pmMode) {
                case "tous" -> "amis";
                case "amis" -> "personne";
                default -> "tous";
            };
            openMenu(pl);
        });
        Items.fill(inv);
        p.openInventory(inv);
    }

    static String modeName(String mode) {
        return switch (mode) {
            case "amis" -> "amis seulement";
            case "personne" -> "personne";
            default -> "tout le monde";
        };
    }

    // ───────────────────────────── Alertes ─────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled()) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            PlayerData me = plugin.store().get(p);
            int online = 0;
            for (UUID u : me.friends) {
                Player o = Bukkit.getPlayer(u);
                if (o == null) continue;
                online++;
                if (plugin.getConfig().getBoolean("amis.alertes", true)) {
                    o.sendMessage(plugin.parse("<#7cd4ff>✦</#7cd4ff> <gray>Ton ami <white>" + p.getName() + "</white> vient de se connecter. "
                            + "<click:run_command:'/tpa " + p.getName() + "'><hover:show_text:'<gray>Lui demander de le rejoindre'><#7cd4ff>[Rejoindre]</#7cd4ff></hover></click>"));
                    o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.4f);
                }
            }
            if (online > 0) {
                p.sendMessage(plugin.parse("<#7cd4ff>✦</#7cd4ff> <gray><white>" + online + "</white> ami" + (online > 1 ? "s sont connectés" : " est connecté")
                        + ". <click:run_command:'/ami'><u>/ami</u></click>"));
            }
        }, 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        requests.remove(p.getUniqueId());
        if (!enabled() || !plugin.getConfig().getBoolean("amis.alertes", true)) return;
        PlayerData me = plugin.store().get(p.getUniqueId());
        if (me == null) return;
        for (UUID u : me.friends) {
            Player o = Bukkit.getPlayer(u);
            if (o != null) o.sendMessage(plugin.parse("<dark_gray>✦ Ton ami " + p.getName() + " s'est déconnecté."));
        }
    }

    /** Toutes les 30 secondes : les demandes expirées disparaissent. */
    void tick() {
        long now = System.currentTimeMillis();
        for (Map<UUID, Long> m : requests.values()) m.values().removeIf(t -> t < now);
        requests.values().removeIf(Map::isEmpty);
    }
}
