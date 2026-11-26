package fr.lyonmc.achievements.noel;

import fr.lyonmc.achievements.LyonAchievements;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Les joueurs savent quoi faire pendant Noël : annonce le 1er décembre, message d'accueil cliquable une fois
 * par jour (calendrier, trésor en cours, arène, boss) et un titre à la première connexion de la saison.
 * Avant le 1er décembre : un petit message « l'événement arrive ».
 */
public final class NoelWelcome implements Listener {

    private final LyonAchievements plugin;
    private final NoelService noel;

    NoelWelcome(LyonAchievements plugin, NoelService noel) {
        this.plugin = plugin;
        this.noel = noel;
    }

    private int season() {
        LocalDate t = noel.today();
        return t.getMonth() == Month.JANUARY ? t.getYear() - 1 : t.getYear();
    }

    private static String cmd(String command, String hover) {
        return "<click:run_command:'" + command + "'><hover:show_text:\"<gray>" + hover + "\"><white><u>" + command + "</u></white></hover></click>";
    }

    private static boolean has(String command) {
        return Bukkit.getPluginCommand(command) != null;
    }

    /** Chaque minute : le jour du début de la période, une annonce pour tout le monde (une seule fois). */
    public void tick() {
        if (!noel.active()) return;
        String key = "annonce." + season();
        if (noel.data().getBoolean(key)) return;
        noel.data().set(key, true);
        noel.markDirty();
        Bukkit.broadcast(plugin.text().parse(" "));
        Bukkit.broadcast(plugin.text().parse("  <gradient:#ff6b6b:#ffffff:#ff6b6b><bold>❄ L'événement de Noël commence sur LyonMC ! ❄</bold></gradient>"));
        for (String line : lines(null)) Bukkit.broadcast(plugin.text().parse(line));
        Bukkit.broadcast(plugin.text().parse(" "));
        for (Player p : Bukkit.getOnlinePlayers()) {
            title(p);
            noel.data().set("titre." + season() + "." + p.getUniqueId(), true);
            noel.data().set("accueil." + p.getUniqueId(), noel.today().toString());
        }
        noel.markDirty();
        plugin.discord().send("Noël sur LyonMC", "L'événement de Noël commence ! Calendrier de l'Avent (/calendrier), chasse au trésor (/tresor)"
                + (has("noel") ? ", arène de Noël (/noel)" : "") + " et boss de Noël le " + noel.bossDayText() + ".", 0xFF6B6B, null);
        plugin.getLogger().info("Noël : annonce du début de l'événement envoyée.");
    }

    private void title(Player p) {
        p.showTitle(Title.title(plugin.text().parse("<gradient:#ff6b6b:#ffffff:#ff6b6b><bold>❄ Noël sur LyonMC ❄</bold></gradient>"),
                plugin.text().parse("<gray>Ta première case t'attend : <white>/calendrier"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(700))));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
    }

    /** Les lignes du programme de Noël, cliquables (p = le joueur, pour sa case du jour). */
    private List<String> lines(Player p) {
        List<String> out = new ArrayList<>();
        int day = noel.calendar().currentDay();
        if (day >= 1 && day <= 24) {
            boolean todo = p == null || noel.calendar().canOpen(p.getUniqueId(), day);
            out.add("   <#ff6b6b>❄</#ff6b6b> <white>Calendrier de l'Avent</white> <gray>: " + (todo ? "ta case n°" + day + " t'attend " : "case du jour ouverte, reviens demain ")
                    + cmd("/calendrier", "Ouvrir le calendrier de l'Avent"));
        }
        TreasureHunt t = noel.treasure();
        if (t.running()) {
            out.add("   <#ffd86b>✦</#ffd86b> <white>Chasse au trésor</white> <gray>: un coffre est caché, les quêtes disent où " + cmd("/tresor", "Voir les quêtes"));
        } else if (plugin.configs().noel().getBoolean("tresor.enabled", true)) {
            out.add("   <#ffd86b>✦</#ffd86b> <white>Chasse au trésor</white> <gray>: un coffre caché tous les 5 jours " + cmd("/tresor", "La chasse au trésor"));
        }
        if (has("noel")) out.add("   <#9fd3ff>☃</#9fd3ff> <white>Arène de Noël</white> <gray>: bataille de boules de neige " + cmd("/noel", "Aller à l'arène de Noël"));
        if (plugin.configs().noel().getBoolean("boss.enabled", true)) {
            out.add("   <#ff9f9f>☠</#ff9f9f> <white>Boss de Noël</white> <gray>: le " + noel.bossDayText() + " " + cmd("/boss", "Le boss mondial"));
        }
        out.add("   <dark_gray>Tout est aussi dans</dark_gray> " + cmd("/menu", "Le menu de LyonMC"));
        return out;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!plugin.module("noel")) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            UUID u = p.getUniqueId();
            String today = noel.today().toString();
            boolean firstToday = !today.equals(noel.data().getString("accueil." + u));
            if (noel.active()) {
                if (firstToday) {
                    noel.data().set("accueil." + u, today);
                    long left = noel.daysToChristmas();
                    p.sendMessage(Component.empty());
                    p.sendMessage(plugin.text().parse("  <gradient:#ff6b6b:#ffffff:#ff6b6b><bold>❄ Noël sur LyonMC ❄</bold></gradient> <dark_gray>·</dark_gray> <gray>"
                            + (left == 0 ? "Joyeux Noël !" : "Noël dans <white>" + noel.daysToChristmasText() + "</white>")));
                    for (String line : lines(p)) p.sendMessage(plugin.text().parse(line));
                    p.sendMessage(Component.empty());
                    String titleKey = "titre." + season() + "." + u;
                    if (!noel.data().getBoolean(titleKey)) {
                        noel.data().set(titleKey, true);
                        title(p);
                    }
                    noel.markDirty();
                } else {
                    // Déjà accueilli aujourd'hui : juste la case du jour si elle n'est pas ouverte
                    int day = noel.calendar().currentDay();
                    if (day >= 1 && day <= 24 && noel.calendar().canOpen(u, day)) {
                        plugin.msg(p, "<#ff6b6b>❄</#ff6b6b> <gray>Ta case du jour t'attend dans le calendrier de l'Avent : " + cmd("/calendrier", "Ouvrir le calendrier"));
                    }
                }
            } else if (noel.visualActive() && firstToday) {
                // Avant le 1er décembre : l'événement arrive
                noel.data().set("accueil." + u, today);
                noel.markDirty();
                long until = noel.daysUntilStart();
                plugin.msg(p, "<#ff6b6b>❄</#ff6b6b> <gray>L'événement de Noël arrive le <white>1er décembre</white>"
                        + (until > 0 ? " (dans " + until + " jour" + (until > 1 ? "s" : "") + ")" : "")
                        + " : calendrier de l'Avent, chasse au trésor et boss de Noël."
                        + (has("noel") ? " En attendant, l'arène de Noël est ouverte : " + cmd("/noel", "Aller à l'arène de Noël") : ""));
            }
        }, 100L);
    }
}
