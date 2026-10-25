package fr.lyonmc.communaute;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** /report : signaler un joueur ou un bug au staff (en jeu et sur Discord). /signalements pour le staff. */
public final class Reports implements org.bukkit.event.Listener {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final Communaute plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    Reports(Communaute plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "signalements.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'enregistrer signalements.yml", e);
        }
    }

    void command(Player p, String[] args) {
        if (!plugin.getConfig().getBoolean("signalements.enabled", true)) {
            plugin.msg(p, "<gray>Les signalements sont désactivés.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage(plugin.parse("  <#ff8a7a>✦</#ff8a7a> <white><bold>Signaler</bold></white>"));
            p.sendMessage(plugin.parse("  <white>/report <joueur> <raison></white> <dark_gray>un tricheur, des insultes..."));
            p.sendMessage(plugin.parse("  <white>/report bug <description></white> <dark_gray>un bug du serveur"));
            p.sendMessage(plugin.parse("  <dark_gray>Le staff est prévenu tout de suite. Merci de ton aide !"));
            return;
        }
        long now = System.currentTimeMillis();
        long wait = plugin.getConfig().getLong("signalements.delai-secondes", 60) * 1000L;
        Long lastTime = cooldown.get(p.getUniqueId());
        if (lastTime != null && now - lastTime < wait && !p.hasPermission("communaute.staff")) {
            plugin.msg(p, "<gray>Attends encore <white>" + ((wait - (now - lastTime)) / 1000 + 1) + " s</white> avant un nouveau signalement.");
            return;
        }
        boolean bug = args[0].equalsIgnoreCase("bug");
        String targetName = bug ? null : args[0];
        if (!bug) {
            PlayerData known = plugin.friends().known(targetName);
            if (known == null) {
                plugin.msg(p, "<gray>Joueur inconnu : <white>" + targetName + "</white>. Pour un bug : <white>/report bug <description>");
                return;
            }
            targetName = known.name;
            if (known.uuid.equals(p.getUniqueId())) {
                plugin.msg(p, "<gray>Tu ne peux pas te signaler toi-même.");
                return;
            }
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        cooldown.put(p.getUniqueId(), now);

        int id = data.getInt("prochain", 1);
        data.set("prochain", id + 1);
        String base = "liste." + id;
        Location l = p.getLocation();
        data.set(base + ".date", now);
        data.set(base + ".par", p.getName());
        data.set(base + ".joueur", bug ? "bug" : targetName);
        data.set(base + ".raison", reason);
        data.set(base + ".lieu", l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        data.set(base + ".etat", "ouvert");
        save();

        plugin.msg(p, "<green>✔</green> <gray>Signalement <white>#" + id + "</white> envoyé au staff. Merci !");
        String what = bug ? "un bug" : "<white>" + targetName + "</white>";
        int staffOnline = 0;
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (!s.hasPermission("communaute.staff")) continue;
            staffOnline++;
            s.sendMessage(plugin.parse("<#ff8a7a>⚑ Signalement #" + id + "</#ff8a7a> <white>" + p.getName() + "</white> <gray>signale " + what
                    + "<gray> : <white>" + escape(reason) + "</white> "
                    + "<click:run_command:'/tp " + p.getName() + "'><hover:show_text:'<gray>Aller voir " + p.getName() + "'><#7cd4ff>[Y aller]</#7cd4ff></hover></click> "
                    + "<click:run_command:'/signalements traiter " + id + "'><hover:show_text:'<gray>Marquer comme traité'><green>[Traité]</green></hover></click>"));
            s.playSound(s.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.8f);
        }
        if (plugin.getConfig().getBoolean("signalements.discord", true)) {
            plugin.lyon().discord().send("Signalement #" + id, p.getName() + " signale " + (bug ? "un bug" : targetName) + " : " + reason
                    + "\n(" + data.getString(base + ".lieu") + ", " + staffOnline + " membre(s) du staff en ligne)", 0xFF8A7A, p.getUniqueId());
        }
        plugin.getLogger().info("[Signalement #" + id + "] " + p.getName() + " -> " + (bug ? "bug" : targetName) + " : " + reason);
    }

    /** Pour éviter qu'une raison contenant des balises ne change l'affichage. */
    private static String escape(String s) {
        return s.replace("<", "‹").replace(">", "›");
    }

    /** /signalements [traiter <id>] */
    void staffCommand(CommandSender s, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("traiter")) {
            String base = "liste." + args[1];
            if (!data.isSet(base)) {
                plugin.msg(s, "<gray>Signalement introuvable : #" + args[1]);
                return;
            }
            data.set(base + ".etat", "traité par " + s.getName());
            save();
            plugin.msg(s, "<gray>Signalement <white>#" + args[1] + "</white> marqué comme traité.");
            return;
        }
        ConfigurationSection list = data.getConfigurationSection("liste");
        List<String> open = new ArrayList<>();
        if (list != null) for (String k : list.getKeys(false)) if ("ouvert".equals(list.getString(k + ".etat"))) open.add(k);
        if (open.isEmpty()) {
            plugin.msg(s, "<gray>Aucun signalement en attente.");
            return;
        }
        s.sendMessage(plugin.parse("  <#ff8a7a>⚑</#ff8a7a> <white><bold>Signalements en attente</bold></white> <dark_gray>(" + open.size() + ")"));
        for (String k : open.subList(Math.max(0, open.size() - 15), open.size())) {
            String b = k + ".";
            String date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(list.getLong(b + "date")), plugin.zone()).format(DATE);
            s.sendMessage(plugin.parse("  <white>#" + k + "</white> <dark_gray>" + date + "</dark_gray> <white>" + list.getString(b + "par")
                    + "</white> <gray>→ <white>" + list.getString(b + "joueur") + "</white> <gray>: " + escape(list.getString(b + "raison", ""))
                    + " <dark_gray>(" + list.getString(b + "lieu") + ")</dark_gray> "
                    + "<click:run_command:'/signalements traiter " + k + "'><green>[Traité]</green></click>"));
        }
    }

    /** Le staff qui se connecte voit les signalements en attente. */
    @org.bukkit.event.EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPermission("communaute.staff")) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int n = openCount();
            if (p.isOnline() && n > 0) {
                p.sendMessage(plugin.parse("<#ff8a7a>⚑</#ff8a7a> <white>" + n + "</white> <gray>signalement(s) en attente : "
                        + "<click:run_command:'/signalements'><u>/signalements</u></click>"));
            }
        }, 80L);
    }

    int openCount() {
        ConfigurationSection list = data.getConfigurationSection("liste");
        int n = 0;
        if (list != null) for (String k : list.getKeys(false)) if ("ouvert".equals(list.getString(k + ".etat"))) n++;
        return n;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
