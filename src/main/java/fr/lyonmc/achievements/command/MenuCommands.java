package fr.lyonmc.achievements.command;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.holo.HologramService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Commandes qui ouvrent des menus, et /lmc (administration). */
public final class MenuCommands implements TabExecutor {

    private final LyonAchievements plugin;

    public MenuCommands(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("lmc")) {
            admin(sender, args);
            return true;
        }
        if (!(sender instanceof Player p)) {
            plugin.msg(sender, "<gray>Commande réservée aux joueurs.");
            return true;
        }
        switch (name) {
            case "menu" -> plugin.hub().open(p);
            case "missions" -> {
                if (!plugin.module("missions")) plugin.msg(p, "<gray>Les missions sont désactivées.");
                else plugin.missionMenu().open(p);
            }
            case "titres" -> plugin.rewardMenus().openTitles(p, 1);
            case "cosmetiques" -> plugin.rewardMenus().openCosmetics(p);
            case "boutique" -> plugin.rewardMenus().openShop(p, 1);
            case "parcours" -> plugin.rewardMenus().openPath(p, 1);
            case "recompense" -> plugin.rewardMenus().openDaily(p);
            case "saison" -> plugin.seasonMenu().open(p);
            case "ressources" -> plugin.resources().teleport(p);
            default -> {
            }
        }
        return true;
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lyonmc.admin")) {
            plugin.msg(sender, "<gray>Tu n'as pas la permission.");
            return;
        }
        if (args.length == 0) {
            help(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadEverything();
                plugin.msg(sender, "<gray>Configuration rechargée (config.yml, missions.yml, recompenses.yml, evenements.yml).");
            }
            case "holo", "hologramme" -> holo(sender, args);
            case "saison" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("fin")) {
                    plugin.seasons().endSeason(YearMonth.now(plugin.text().zone()).toString());
                    plugin.msg(sender, "<gray>Saison terminée, récompenses distribuées.");
                } else {
                    plugin.msg(sender, "<gray>" + plugin.seasons().name() + " · fin dans " + plugin.seasons().timeLeft()
                            + " · <white>/lmc saison fin</white> pour la terminer maintenant.");
                }
            }
            case "missions" -> {
                Profile p = args.length > 2 && args[1].equalsIgnoreCase("reset") ? plugin.profiles().byName(args[2]) : null;
                if (p == null) {
                    plugin.msg(sender, "<gray>Usage : <white>/lmc missions reset <joueur>");
                    return;
                }
                plugin.missions().reset(p);
                plugin.msg(sender, "<gray>Missions de <white>" + p.name + "</white> réinitialisées.");
            }
            case "titre" -> {
                Profile p = args.length > 2 ? plugin.profiles().byName(args[1]) : null;
                if (p == null || plugin.rewards().title(args[2]) == null) {
                    plugin.msg(sender, "<gray>Usage : <white>/lmc titre <joueur> <id du titre>");
                    return;
                }
                p.titles.add(args[2]);
                p.dirty();
                plugin.msg(sender, "<gray>Titre <white>" + args[2] + "</white> donné à <white>" + p.name + "</white>.");
            }
            case "pieces" -> sender.getServer().dispatchCommand(sender, "eco " + String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            case "import" -> {
                plugin.msg(sender, "<gray>Import des succès depuis les fichiers du monde...");
                plugin.stats().importFromWorld(c -> plugin.msg(sender, "<gray>Import terminé : <white>" + c + "</white> joueurs."));
            }
            default -> help(sender);
        }
    }

    private void holo(CommandSender sender, String[] args) {
        HologramService h = plugin.holograms();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "creer", "créer", "create" -> {
                if (!(sender instanceof Player p) || args.length < 3 || !HologramService.TYPES.contains(args[2].toLowerCase(Locale.ROOT))) {
                    plugin.msg(sender, "<gray>Usage : <white>/lmc holo creer <" + String.join("|", HologramService.TYPES) + "> [id]");
                    return;
                }
                String type = args[2].toLowerCase(Locale.ROOT);
                String id = (args.length > 3 ? args[3] : type).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
                h.create(id, type, p.getLocation());
                plugin.msg(sender, "<gray>Hologramme <white>" + id + "</white> (" + type + ") créé ici.");
            }
            case "suppr", "supprimer", "delete" -> {
                if (args.length < 3 || !h.remove(args[2].toLowerCase(Locale.ROOT))) plugin.msg(sender, "<gray>Hologramme introuvable.");
                else plugin.msg(sender, "<gray>Hologramme supprimé.");
            }
            default -> {
                plugin.msg(sender, "<gray>Hologrammes : " + (h.ids().isEmpty() ? "aucun" : ""));
                for (String id : h.ids()) sender.sendMessage(plugin.text().parse("  <white>" + id + "</white> <dark_gray>" + h.describe(id)));
                plugin.msg(sender, "<gray><white>/lmc holo creer <type> [id]</white> · <white>/lmc holo suppr <id>");
            }
        }
    }

    private void help(CommandSender sender) {
        String[] lines = {
                "/lmc reload", "recharger toutes les configurations",
                "/lmc holo creer <type> [id]", "hologramme de classement (succes, saison, equipes, podium, podium-saison)",
                "/lmc holo suppr <id>", "supprimer un hologramme",
                "/lmc saison fin", "terminer la saison maintenant",
                "/lmc missions reset <joueur>", "nouvelles missions pour un joueur",
                "/lmc titre <joueur> <titre>", "donner un titre",
                "/eco give|take|set <joueur> <montant>", "gérer les pièces",
                "/boss arene · lancer · stop", "boss mondial",
                "/setspawn", "définir le spawn",
                "/lmc import", "réimporter les succès du monde"};
        sender.sendMessage(plugin.text().parse("<white><bold>LyonMC · administration"));
        for (int i = 0; i < lines.length; i += 2) {
            sender.sendMessage(plugin.text().parse("<white>" + lines[i] + "</white>  <dark_gray>" + lines[i + 1]));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("lmc") || !sender.hasPermission("lyonmc.admin")) return out;
        if (args.length == 1) out.addAll(List.of("reload", "holo", "saison", "missions", "titre", "import"));
        else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "holo" -> out.addAll(List.of("creer", "suppr", "liste"));
                case "saison" -> out.add("fin");
                case "missions" -> out.add("reset");
                case "titre" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                default -> {
                }
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("holo") && args[1].equalsIgnoreCase("creer")) out.addAll(HologramService.TYPES);
            else if (args[0].equalsIgnoreCase("holo") && args[1].equalsIgnoreCase("suppr")) out.addAll(plugin.holograms().ids());
            else if (args[0].equalsIgnoreCase("missions")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            else if (args[0].equalsIgnoreCase("titre")) for (var t : plugin.rewards().titles()) out.add(t.id());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
