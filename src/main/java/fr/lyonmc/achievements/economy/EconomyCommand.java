package fr.lyonmc.achievements.economy;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /money, /pay, /baltop, /eco */
public final class EconomyCommand implements TabExecutor {

    private final LyonAchievements plugin;

    public EconomyCommand(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.module("economy")) {
            plugin.msg(sender, "<gray>L'économie est désactivée sur ce serveur.");
            return true;
        }
        EconomyService eco = plugin.economy();
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "money" -> {
                if (args.length >= 1) {
                    Profile p = plugin.profiles().byName(args[0]);
                    if (p == null) {
                        plugin.msg(sender, "<gray>Joueur introuvable.");
                        return true;
                    }
                    plugin.msg(sender, "<gray>Solde de <white>" + p.name + "</white> : <accent>" + eco.format(p.coins));
                } else if (sender instanceof Player p) {
                    Profile pr = plugin.profiles().get(p);
                    plugin.msg(sender, "<gray>Ton solde : <accent>" + eco.format(pr.coins)
                            + "</accent> <dark_gray>· gagnées au total " + Text.num(pr.earned));
                } else {
                    plugin.msg(sender, "<gray>Usage : /money <joueur>");
                }
            }
            case "pay" -> pay(sender, args, eco);
            case "baltop" -> baltop(sender, args.length > 0 ? parse(args[0], 1) : 1, eco);
            case "eco" -> admin(sender, args, eco);
            default -> {
            }
        }
        return true;
    }

    private void pay(CommandSender sender, String[] args, EconomyService eco) {
        if (!(sender instanceof Player from)) {
            plugin.msg(sender, "<gray>Commande réservée aux joueurs.");
            return;
        }
        if (args.length < 2) {
            plugin.msg(sender, "<gray>Usage : <white>/pay <joueur> <montant>");
            return;
        }
        Profile to = plugin.profiles().byName(args[0]);
        if (to == null || to.uuid().equals(from.getUniqueId())) {
            plugin.msg(sender, "<gray>Joueur introuvable.");
            return;
        }
        long amount = parseLong(args[1], -1L);
        if (amount <= 0) {
            plugin.msg(sender, "<gray>Montant invalide.");
            return;
        }
        if (!eco.withdraw(from.getUniqueId(), amount)) {
            plugin.msg(sender, "<gray>Tu n'as pas assez de " + eco.plural() + ".");
            return;
        }
        eco.deposit(to.uuid(), to.name, amount, false);
        plugin.msg(sender, "<gray>Tu as envoyé <accent>" + eco.format(amount) + "</accent> à <white>" + to.name + "</white>.");
        Player target = Bukkit.getPlayer(to.uuid());
        if (target != null) {
            plugin.msg(target, "<white>" + from.getName() + "</white> <gray>t'a envoyé <accent>" + eco.format(amount) + "</accent>.");
            target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        } else {
            to.mailbox.add("<white>" + from.getName() + "</white> <gray>t'a envoyé <accent>" + eco.format(amount) + "</accent>.");
            to.dirty();
        }
    }

    private void baltop(CommandSender sender, int page, EconomyService eco) {
        List<Profile> top = eco.top();
        int pages = Math.max(1, (top.size() + 9) / 10);
        page = Math.max(1, Math.min(pages, page));
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("Les plus riches", Text.WHITE, TextDecoration.BOLD)
                .append(Component.text("   page " + page + "/" + pages, Text.DARK).decoration(TextDecoration.BOLD, false)));
        for (int i = (page - 1) * 10; i < Math.min(top.size(), page * 10); i++) {
            Profile p = top.get(i);
            sender.sendMessage(plugin.text().parse("<dark_gray>" + String.format("%3d", i + 1) + "   <white>" + p.name
                    + "</white>   <accent>" + eco.format(p.coins)));
        }
        if (top.isEmpty()) sender.sendMessage(Component.text("Personne n'a encore de " + eco.plural() + ".", Text.GRAY));
    }

    private void admin(CommandSender sender, String[] args, EconomyService eco) {
        if (!sender.hasPermission("lyonmc.admin")) {
            plugin.msg(sender, "<gray>Tu n'as pas la permission.");
            return;
        }
        if (args.length < 3) {
            plugin.msg(sender, "<gray>Usage : <white>/eco <give|take|set> <joueur> <montant>");
            return;
        }
        Profile p = plugin.profiles().byName(args[1]);
        if (p == null) {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (op == null) {
                plugin.msg(sender, "<gray>Joueur introuvable.");
                return;
            }
            p = plugin.profiles().getOrCreate(op.getUniqueId(), op.getName());
        }
        long amount = parseLong(args[2], -1L);
        if (amount < 0) {
            plugin.msg(sender, "<gray>Montant invalide.");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> eco.deposit(p.uuid(), p.name, amount, false);
            case "take" -> {
                p.coins = Math.max(0, p.coins - amount);
                p.dirty();
            }
            case "set" -> eco.set(p.uuid(), p.name, amount);
            default -> {
                plugin.msg(sender, "<gray>Usage : <white>/eco <give|take|set> <joueur> <montant>");
                return;
            }
        }
        plugin.msg(sender, "<gray>Nouveau solde de <white>" + p.name + "</white> : <accent>" + eco.format(p.coins));
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.replace(" ", "").replace("_", ""));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("eco") && args.length == 1) {
            out.addAll(List.of("give", "take", "set"));
        } else if ((name.equals("pay") || name.equals("money")) && args.length == 1
                || name.equals("eco") && args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
