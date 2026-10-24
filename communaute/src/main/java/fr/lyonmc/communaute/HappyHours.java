package fr.lyonmc.communaute;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Heures bonus à horaires fixes (pièces et points de saison multipliés) :
 * des rendez-vous réguliers pour que les joueurs se retrouvent aux mêmes moments.
 */
public final class HappyHours {

    private record Slot(DayOfWeek day, LocalTime start, LocalTime end) {
    }

    private final Communaute plugin;
    private final List<Slot> slots = new ArrayList<>();
    private final Set<String> announced = new HashSet<>();

    HappyHours(Communaute plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        slots.clear();
        for (String s : plugin.getConfig().getStringList("heures-bonus.horaires")) {
            try {
                String[] parts = s.trim().split("\\s+");
                String[] hours = parts[1].split("-");
                slots.add(new Slot(DayOfWeek.valueOf(parts[0].toUpperCase(Locale.ROOT)), LocalTime.parse(hours[0]), LocalTime.parse(hours[1])));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Horaire d'heure bonus mal écrit : « " + s + " » (exemple : FRIDAY 20:00-22:00)");
            }
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("heures-bonus.enabled", true);
    }

    private double coins() {
        return plugin.getConfig().getDouble("heures-bonus.pieces", 2.0);
    }

    private double season() {
        return plugin.getConfig().getDouble("heures-bonus.saison", 2.0);
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Toutes les 30 secondes : début d'une heure bonus, ou rappel 10 minutes avant. */
    void tick() {
        if (!enabled()) return;
        ZonedDateTime now = ZonedDateTime.now(plugin.zone());
        for (Slot s : slots) {
            for (LocalDate day : List.of(now.toLocalDate(), now.toLocalDate().minusDays(1))) {
                if (day.getDayOfWeek() != s.day()) continue;
                ZonedDateTime start = day.atTime(s.start()).atZone(plugin.zone());
                ZonedDateTime end = day.atTime(s.end()).atZone(plugin.zone());
                if (!end.isAfter(start)) end = end.plusDays(1);
                String key = day + " " + s;
                if (!now.isBefore(start) && now.isBefore(end) && announced.add(key + " début")) {
                    plugin.lyon().boosts().set("heure-bonus", coins(), season(), end.toInstant().toEpochMilli());
                    announce(end);
                } else if (now.isBefore(start) && now.isAfter(start.minusMinutes(10)) && announced.add(key + " rappel")) {
                    Bukkit.broadcast(plugin.parse("<#ffd86b>✦</#ffd86b> <gray>Heure bonus dans <white>10 minutes</white> : pièces x" + fmt(coins())
                            + " et points de saison x" + fmt(season()) + " !"));
                }
            }
        }
    }

    /** Bonus lancé à la main (/communaute bonus) ou par une vote party. */
    void start(String reason, long durationMs) {
        long until = System.currentTimeMillis() + durationMs;
        plugin.lyon().boosts().set(reason, coins(), season(), until);
        announce(ZonedDateTime.now(plugin.zone()).plus(Duration.ofMillis(durationMs)));
    }

    private void announce(ZonedDateTime end) {
        String until = end.format(DateTimeFormatter.ofPattern("HH'h'mm"));
        Bukkit.broadcast(plugin.parse("<#ffd86b><bold>✦ HEURE BONUS !</bold></#ffd86b> <gray>Pièces x" + fmt(coins()) + " et points de saison x"
                + fmt(season()) + " jusqu'à <white>" + until + "</white>."));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(plugin.parse("<#ffd86b><bold>Heure bonus !"),
                    plugin.parse("<gray>Pièces x" + fmt(coins()) + " · saison x" + fmt(season()) + " jusqu'à " + until),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
        }
        if (plugin.getConfig().getBoolean("heures-bonus.discord", true)) {
            plugin.lyon().discord().send("Heure bonus !", "Pièces x" + fmt(coins()) + " et points de saison x" + fmt(season())
                    + " jusqu'à " + until + " sur lyonmc.fr", 0xFFD86B, null);
        }
    }

    /** Prochain début d'heure bonus (ou null). */
    public ZonedDateTime next() {
        ZonedDateTime now = ZonedDateTime.now(plugin.zone());
        ZonedDateTime best = null;
        for (Slot s : slots) {
            for (int i = 0; i <= 7; i++) {
                LocalDate day = now.toLocalDate().plusDays(i);
                if (day.getDayOfWeek() != s.day()) continue;
                ZonedDateTime start = day.atTime(s.start()).atZone(plugin.zone());
                if (start.isAfter(now) && (best == null || start.isBefore(best))) best = start;
            }
        }
        return best;
    }

    /** « vendredi 20h00 » */
    public String nextText() {
        ZonedDateTime n = next();
        if (n == null) return "aucune prévue";
        return n.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE) + " " + n.format(DateTimeFormatter.ofPattern("HH'h'mm"));
    }

    public List<String> schedule() {
        List<String> out = new ArrayList<>();
        for (Slot s : slots) {
            out.add(s.day().getDisplayName(TextStyle.FULL, Locale.FRANCE) + " " + s.start().format(DateTimeFormatter.ofPattern("HH'h'mm"))
                    + " – " + s.end().format(DateTimeFormatter.ofPattern("HH'h'mm")));
        }
        return out;
    }

    public double coinMultiplier() {
        return coins();
    }
}
