package fr.lyonmc.achievements.jobs;

import fr.lyonmc.achievements.LyonAchievements;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /metiers, /metiers top, /metiers <métier> */
public final class JobCommand implements TabExecutor {

    private final LyonAchievements plugin;

    public JobCommand(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        if (!plugin.jobs().enabled()) {
            plugin.msg(p, "<gray>Les métiers sont désactivés sur ce serveur.");
            return true;
        }
        if (args.length == 0) {
            plugin.jobMenu().open(p);
            return true;
        }
        String a = args[0].toLowerCase(Locale.ROOT);
        if (a.equals("top") || a.equals("classement")) {
            plugin.jobMenu().openTop(p, args.length > 1 ? plugin.jobs().get(args[1]) : null);
            return true;
        }
        JobService.Job job = find(a);
        if (job != null) plugin.jobMenu().openJob(p, job);
        else plugin.jobMenu().open(p);
        return true;
    }

    /** « mineur », « Mineur », « bûcheron » ou « bucheron »... */
    private JobService.Job find(String s) {
        JobService.Job j = plugin.jobs().get(s);
        if (j != null) return j;
        String plain = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        for (JobService.Job job : plugin.jobs().all()) {
            String n = java.text.Normalizer.normalize(job.name(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            if (n.equalsIgnoreCase(plain) || job.id().equalsIgnoreCase(plain)) return job;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("top");
            for (JobService.Job j : plugin.jobs().all()) out.add(j.id());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            for (JobService.Job j : plugin.jobs().all()) out.add(j.id());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
