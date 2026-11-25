package fr.lyonmc.achievements.noel;

import fr.lyonmc.achievements.LyonAchievements;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /calendrier (calendrier de l'Avent) et /tresor (chasse au trésor). */
public final class NoelCommand implements TabExecutor {

    private final LyonAchievements plugin;

    public NoelCommand(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.msg(sender, "<gray>Commande réservée aux joueurs.");
            return true;
        }
        NoelService noel = plugin.noel();
        if (command.getName().equalsIgnoreCase("calendrier")) {
            noel.calendar().open(p);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("admin") && p.hasPermission("lyonmc.admin")) {
            noel.treasure().admin(p, args);
            return true;
        }
        noel.treasure().show(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("tresor") || !sender.hasPermission("lyonmc.admin")) return out;
        if (args.length == 1) out.add("admin");
        else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) out.addAll(List.of("nouveau", "ou", "fin"));
        return out;
    }
}
