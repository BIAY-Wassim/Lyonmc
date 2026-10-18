package fr.lyonmc.achievements.team;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /equipe : commandes, menu et protection entre coéquipiers. */
public final class TeamCommand implements TabExecutor, Listener {

    private final LyonAchievements plugin;

    public TeamCommand(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private TeamService teams() {
        return plugin.teams();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.msg(sender, "<gray>Commande réservée aux joueurs.");
            return true;
        }
        if (!plugin.module("teams")) {
            plugin.msg(sender, "<gray>Les équipes sont désactivées sur ce serveur.");
            return true;
        }
        if (args.length == 0) {
            openMenu(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "creer", "créer", "create" -> {
                if (args.length < 3) plugin.msg(player, "<gray>Usage : <white>/equipe creer <nom> <tag></white> <dark_gray>(coût "
                        + plugin.economy().format(teams().createCost()) + ")");
                else teams().create(player, args[1], args[2]);
            }
            case "inviter", "invite" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) plugin.msg(player, "<gray>Usage : <white>/equipe inviter <joueur connecté>");
                else teams().invite(player, target);
            }
            case "rejoindre", "join" -> {
                if (args.length < 2) plugin.msg(player, "<gray>Usage : <white>/equipe rejoindre <équipe>");
                else teams().join(player, args[1]);
            }
            case "quitter", "leave" -> teams().leave(player);
            case "exclure", "kick" -> {
                if (args.length < 2) plugin.msg(player, "<gray>Usage : <white>/equipe exclure <joueur>");
                else teams().kick(player, args[1]);
            }
            case "dissoudre", "disband" -> teams().disband(player);
            case "chef", "transferer" -> {
                if (args.length < 2) plugin.msg(player, "<gray>Usage : <white>/equipe chef <joueur>");
                else teams().transfer(player, args[1]);
            }
            case "chat", "c" -> {
                if (args.length > 1) {
                    Team t = teams().teamOf(player.getUniqueId());
                    if (t == null) plugin.msg(player, "<gray>Tu n'es dans aucune équipe.");
                    else teams().broadcastTeam(t, "<white>" + player.getName() + "</white> <dark_gray>›</dark_gray> <#bfe3ff>"
                            + Text.MM.escapeTags(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))));
                } else {
                    teams().toggleChat(player);
                }
            }
            case "home" -> teams().home(player);
            case "sethome" -> teams().setHome(player);
            case "pvp" -> teams().togglePvp(player);
            case "couleur", "color" -> {
                if (args.length < 2) plugin.msg(player, "<gray>Usage : <white>/equipe couleur <#hex>");
                else teams().setColor(player, args[1]);
            }
            case "info" -> {
                Team t = args.length > 1 ? teams().byName(args[1]) : teams().teamOf(player.getUniqueId());
                if (t == null) plugin.msg(player, "<gray>Équipe introuvable.");
                else info(player, t);
            }
            case "top", "liste", "classement" -> top(player);
            default -> help(player);
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(Component.text("Équipes · aide", Text.WHITE, TextDecoration.BOLD));
        String[][] lines = {
                {"/equipe", "menu de ton équipe"},
                {"/equipe creer <nom> <tag>", "fonder une équipe (" + plugin.economy().format(teams().createCost()) + ")"},
                {"/equipe inviter <joueur>", "inviter un joueur"},
                {"/equipe rejoindre <équipe>", "accepter une invitation"},
                {"/equipe chat", "activer le chat d'équipe"},
                {"/equipe home  ·  /equipe sethome", "home d'équipe"},
                {"/equipe pvp", "combat entre membres"},
                {"/equipe couleur <#hex>", "couleur du tag"},
                {"/equipe chef <joueur>", "donner la direction"},
                {"/equipe exclure <joueur>  ·  quitter  ·  dissoudre", ""},
                {"/equipe top", "classement des équipes"}};
        for (String[] l : lines) {
            p.sendMessage(Component.text(l[0], Text.WHITE).append(Component.text(l[1].isEmpty() ? "" : "  " + l[1], Text.DARK)));
        }
    }

    private void info(Player viewer, Team t) {
        viewer.sendMessage(Component.empty());
        viewer.sendMessage(teams().tagComponent(t).append(Component.text(t.name, Text.WHITE, TextDecoration.BOLD)));
        viewer.sendMessage(Items.kv("Chef", teams().memberName(t.owner)));
        viewer.sendMessage(Items.kv("Membres", t.members.size() + " / " + teams().maxMembers()));
        viewer.sendMessage(Items.kv("Succès cumulés", Text.num(teams().achievements(t))));
        viewer.sendMessage(Items.kv("Points de saison", Text.num(teams().seasonPoints(t))));
        List<String> names = new ArrayList<>();
        for (UUID u : t.members) names.add((Bukkit.getPlayer(u) != null ? "● " : "○ ") + teams().memberName(u));
        viewer.sendMessage(Component.text(String.join("   ", names), Text.GRAY));
    }

    private void top(Player p) {
        List<Team> top = teams().top();
        p.sendMessage(Component.empty());
        p.sendMessage(Component.text("Classement des équipes", Text.WHITE, TextDecoration.BOLD));
        if (top.isEmpty()) p.sendMessage(Component.text("Aucune équipe pour l'instant.", Text.GRAY));
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            Team t = top.get(i);
            p.sendMessage(Component.text(String.format("%3d   ", i + 1), i == 0 ? plugin.text().accent() : Text.DARK)
                    .append(teams().tagComponent(t)).append(Component.text(t.name + "   ", Text.WHITE))
                    .append(Component.text("★ " + Text.num(teams().achievements(t)) + "   ✦ " + Text.num(teams().seasonPoints(t))
                            + "   " + t.members.size() + " membres", Text.DARK)));
        }
    }

    // ───────────────────────────── Menu ─────────────────────────────

    public void openMenu(Player player) {
        Team t = teams().teamOf(player.getUniqueId());
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(t == null ? "Équipes" : "Équipe " + t.name));
        holder.inventory(inv);
        TextColor accent = plugin.text().accent();

        if (t == null) {
            inv.setItem(4, Items.item(Material.WHITE_BANNER, Component.text("Équipes", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                    Items.line("Jouez à plusieurs : chat d'équipe,"),
                    Items.line("home commun, tag devant le pseudo"),
                    Items.line("et classement des équipes."),
                    Items.EMPTY,
                    Items.kv("Créer une équipe", plugin.economy().format(teams().createCost())),
                    Items.dark("/equipe creer <nom> <tag>")), false));
            List<Team> inv2 = teams().invitesFor(player.getUniqueId());
            int slot = 19;
            for (Team team : inv2) {
                if (slot > 25) break;
                inv.setItem(slot, Items.item(Material.PAPER, teams().tagComponent(team).append(Component.text(team.name, Text.WHITE)), List.of(
                        Items.kv("Membres", team.members.size() + " / " + teams().maxMembers()),
                        Items.EMPTY,
                        Items.action("Cliquer pour rejoindre", accent)), true));
                String name = team.name;
                holder.action(slot, pl -> {
                    teams().join(pl, name);
                    openMenu(pl);
                });
                slot++;
            }
            if (inv2.isEmpty()) {
                inv.setItem(22, Items.item(Material.GRAY_DYE, Component.text("Aucune invitation", Text.GRAY),
                        List.of(Items.dark("Demande à un chef d'équipe de t'inviter.")), false));
            }
        } else {
            List<Component> info = new ArrayList<>();
            info.add(Items.kv("Chef", teams().memberName(t.owner)));
            info.add(Items.kv("Membres", t.members.size() + " / " + teams().maxMembers()));
            info.add(Items.kv("Succès cumulés", Text.num(teams().achievements(t))));
            info.add(Items.kv("Points de saison", Text.num(teams().seasonPoints(t))));
            info.add(Items.kv("Combat entre membres", t.pvp ? "activé" : "désactivé"));
            inv.setItem(4, Items.item(Material.WHITE_BANNER, teams().tagComponent(t).append(Component.text(t.name, Text.WHITE)
                    .decorate(TextDecoration.BOLD)), info, false));
            int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
            int i = 0;
            for (UUID u : t.members) {
                if (i >= slots.length) break;
                ItemStack head = plugin.heads().head(u, teams().memberName(u));
                PlayerStats s = plugin.stats().get(u);
                boolean online = Bukkit.getPlayer(u) != null;
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(online ? "● En ligne" : "○ Hors ligne", online ? TextColor.color(0x7BD88F) : Text.DARK));
                if (t.isOwner(u)) lore.add(Component.text("♛ Chef d'équipe", accent));
                lore.add(Items.kv("Succès", String.valueOf(s == null ? 0 : s.count())));
                inv.setItem(slots[i++], Items.decorate(head, Component.text(teams().memberName(u), Text.WHITE), lore, false));
            }
            inv.setItem(46, Items.item(Material.RED_BED, Component.text("Home d'équipe", Text.WHITE), List.of(
                    Items.line(t.home == null ? "Pas encore défini" : t.home.pretty()),
                    Items.dark("Le chef le définit avec /equipe sethome"),
                    Items.EMPTY, Items.action("Cliquer pour s'y téléporter", accent)), false));
            holder.action(46, pl -> {
                pl.closeInventory();
                teams().home(pl);
            });
            boolean chat = teams().inTeamChat(player.getUniqueId());
            inv.setItem(48, Items.item(chat ? Material.LIME_DYE : Material.GRAY_DYE,
                    Component.text("Chat d'équipe : " + (chat ? "activé" : "désactivé"), Text.WHITE),
                    List.of(Items.dark("Avec le chat d'équipe, commence un"), Items.dark("message par ! pour parler à tous.")), false));
            holder.action(48, pl -> {
                teams().toggleChat(pl);
                openMenu(pl);
            });
            if (t.isOwner(player.getUniqueId())) {
                inv.setItem(50, Items.item(t.pvp ? Material.IRON_SWORD : Material.SHIELD,
                        Component.text("Combat entre membres : " + (t.pvp ? "activé" : "désactivé"), Text.WHITE), List.of(), false));
                holder.action(50, pl -> {
                    teams().togglePvp(pl);
                    openMenu(pl);
                });
            }
            inv.setItem(52, Items.item(Material.BARRIER, Component.text("Quitter l'équipe", Text.WHITE), List.of(), false));
            holder.action(52, pl -> fr.lyonmc.achievements.menu.ConfirmMenu.open(pl, "Quitter " + t.name + " ?", null,
                    List.of(Items.line("Tu quitteras l'équipe " + t.name)), pp -> teams().leave(pp), this::openMenu));
        }

        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(45, pl -> plugin.hub().open(pl));
        List<Component> topLore = new ArrayList<>();
        List<Team> top = teams().top();
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            topLore.add(Component.text((i + 1) + ". ", Text.DARK).append(teams().tagComponent(top.get(i)))
                    .append(Component.text(top.get(i).name + "  ", Text.WHITE))
                    .append(Component.text("★ " + Text.num(teams().achievements(top.get(i))), Text.DARK)));
        }
        if (topLore.isEmpty()) topLore.add(Items.dark("Aucune équipe pour l'instant."));
        inv.setItem(49, Items.item(Material.GOLDEN_HELMET, Component.text("Classement des équipes", Text.WHITE), topLore, false));
        Items.fill(inv);
        player.openInventory(inv);
    }

    // ───────────────────────────── Protection ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Entity damager = event.getDamager();
        Player attacker = null;
        if (damager instanceof Player p) attacker = p;
        else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;
        if (!teams().sameTeam(attacker.getUniqueId(), victim.getUniqueId())) return;
        Team t = teams().teamOf(victim.getUniqueId());
        if (t != null && !t.pvp) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        teams().quit(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("creer", "inviter", "rejoindre", "quitter", "exclure", "dissoudre", "chef", "chat", "home",
                    "sethome", "pvp", "couleur", "info", "top"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("inviter") || sub.equals("exclure") || sub.equals("chef")) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            } else if (sub.equals("rejoindre") || sub.equals("info")) {
                for (Team t : teams().all()) out.add(t.name);
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
