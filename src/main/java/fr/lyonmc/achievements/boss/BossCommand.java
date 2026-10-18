package fr.lyonmc.achievements.boss;

import fr.lyonmc.achievements.LyonAchievements;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /boss : infos, rejoindre l'arène ; admin : arene, lancer, stop. */
public final class BossCommand implements TabExecutor {

    private final LyonAchievements plugin;

    public BossCommand(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        BossService boss = plugin.boss();
        if (args.length > 0 && sender.hasPermission("lyonmc.admin")) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "arene", "arena" -> {
                    if (sender instanceof Player p) {
                        boss.setArena(p.getLocation());
                        plugin.msg(p, "<gray>Arène du boss définie ici.");
                    }
                    return true;
                }
                case "lancer", "start" -> {
                    Location at = boss.arena();
                    if (at == null && sender instanceof Player p) at = p.getLocation();
                    if (!boss.start(at)) plugin.msg(sender, "<gray>Impossible : boss déjà actif ou arène non définie.");
                    return true;
                }
                case "stop" -> {
                    boss.end(false, "<gray>Le combat a été arrêté.");
                    return true;
                }
                default -> {
                }
            }
        }
        if (!(sender instanceof Player player)) {
            plugin.msg(sender, "<gray>Prochaine apparition : <white>" + boss.nextText());
            return true;
        }
        if (!plugin.module("boss")) {
            plugin.msg(sender, "<gray>Le boss mondial est désactivé sur ce serveur.");
            return true;
        }
        Location arena = boss.arena();
        ZonedDateTime next = boss.next();
        boolean soon = next != null && ChronoUnit.MINUTES.between(ZonedDateTime.now(plugin.text().zone()), next) <= 15;
        if ((boss.active() || soon) && arena != null) {
            plugin.teleporter().teleport(player, arena, "l'arène du boss");
            return true;
        }
        plugin.msg(player, "<gray>Prochaine apparition du boss : <white>" + boss.nextText() + "</white>.");
        plugin.msg(player, "<dark_gray>L'arène s'ouvre 15 minutes avant le combat avec /boss.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("lyonmc.admin")) out.addAll(List.of("arene", "lancer", "stop"));
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
