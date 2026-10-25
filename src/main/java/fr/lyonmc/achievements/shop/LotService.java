package fr.lyonmc.achievements.shop;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Configs;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.rewards.Bundle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lots de recompenses.yml (section « lots ») donnés par /lmc donner : achats sur une boutique en ligne (Tebex...),
 * gagnants d'un concours, cadeaux d'événement. Le joueur peut être hors ligne : il recevra le lot à sa connexion.
 * Chaque lot donné est écrit dans plugins/LyonAchievements/lots-donnes.log (historique des achats).
 */
public final class LotService {

    public static final String PREFIX = "lot:";

    private final LyonAchievements plugin;
    private final File log;

    public LotService(LyonAchievements plugin) {
        this.plugin = plugin;
        this.log = new File(plugin.getDataFolder(), "lots-donnes.log");
    }

    private ConfigurationSection lots() {
        return Configs.section(plugin.configs().rewards(), "lots");
    }

    public List<String> ids() {
        ConfigurationSection c = lots();
        return c == null ? List.of() : new ArrayList<>(c.getKeys(false));
    }

    public ConfigurationSection lot(String id) {
        ConfigurationSection c = lots();
        return c == null ? null : c.getConfigurationSection(id);
    }

    /** Lot en attente (« lot:id ») ou chemin de evenements.yml. */
    public Bundle resolve(String path) {
        if (path.startsWith(PREFIX)) return Bundle.of(lot(path.substring(PREFIX.length())));
        return Bundle.of(plugin.configs().events().getConfigurationSection(path));
    }

    /** /lmc donner <joueur> <lot> [note...] */
    public void giveCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.msg(sender, "<gray>Usage : <white>/lmc donner <joueur> <lot> [note]</white> <dark_gray>(lots : "
                    + String.join(", ", ids()) + ")");
            return;
        }
        String id = args[2];
        ConfigurationSection c = lot(id);
        if (c == null) {
            plugin.msg(sender, "<gray>Lot inconnu : <white>" + id + "</white>. Lots de recompenses.yml : <white>" + String.join(", ", ids()));
            return;
        }
        Profile p = plugin.profiles().byName(args[1]);
        if (p == null) {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (off == null || off.getName() == null) {
                plugin.msg(sender, "<gray>Joueur inconnu : <white>" + args[1] + "</white> (il doit s'être connecté au moins une fois).");
                return;
            }
            p = plugin.profiles().getOrCreate(off.getUniqueId(), off.getName());
        }
        String note = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : sender.getName();
        give(p, id, note);
        plugin.msg(sender, "<gray>Lot <white>" + id + "</white> donné à <white>" + p.name + "</white>"
                + (Bukkit.getPlayer(p.uuid()) == null ? " <dark_gray>(hors ligne : il le recevra à sa connexion)" : "") + "<gray>.");
    }

    public void give(Profile p, String id, String note) {
        ConfigurationSection c = lot(id);
        if (c == null) return;
        Player online = Bukkit.getPlayer(p.uuid());
        if (online != null) deliver(online, id);
        else {
            p.pendingBundles.add(PREFIX + id);
            p.dirty();
        }
        write(p, id, note, online != null);
    }

    /** Donne le lot à un joueur connecté, avec son message et son annonce. */
    public void deliver(Player player, String id) {
        ConfigurationSection c = lot(id);
        if (c == null) return;
        Bundle b = Bundle.of(c);
        plugin.rewards().grant(player, b);
        plugin.rewards().check(player);
        String name = c.getString("nom", id);
        player.sendMessage(plugin.prefixed(c.getString("message", "<accent>✦</accent> <gray>Tu as reçu <white><lot></white> !"),
                Placeholder.unparsed("lot", name), Placeholder.unparsed("player", player.getName())));
        for (Component line : plugin.rewards().describe(b)) player.sendMessage(Component.text("   ").append(line));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        String announce = c.getString("annonce", "");
        if (!announce.isEmpty()) {
            Bukkit.broadcast(plugin.text().parse(announce, Placeholder.unparsed("lot", name), Placeholder.unparsed("player", player.getName())));
        }
    }

    private void write(Profile p, String id, String note, boolean online) {
        String line = ZonedDateTime.now(plugin.text().zone()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " | " + p.name + " | " + p.uuid() + " | " + id + " | " + (online ? "donné" : "en attente (hors ligne)") + " | " + note
                + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (this) {
                try {
                    if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
                    Files.writeString(log.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    plugin.getLogger().warning("Impossible d'écrire lots-donnes.log : " + e.getMessage());
                }
            }
        });
    }
}
