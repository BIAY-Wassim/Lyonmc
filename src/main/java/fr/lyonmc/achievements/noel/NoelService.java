package fr.lyonmc.achievements.noel;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.rewards.Bundle;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.CachedServerIcon;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Noël sur LyonMC : la période (1er décembre - 6 janvier par défaut), le MOTD et l'icône de Noël,
 * le fichier des données (calendrier, chasse au trésor) et ce que la page Noël du site affiche.
 */
public final class NoelService {

    private final LyonAchievements plugin;
    private final File dataFile;
    private YamlConfiguration data;
    private boolean dirty;
    private final AdventCalendar calendar;
    private final TreasureHunt treasure;
    /** Icône de Noël (bonnet sur l'icône du serveur), préparée une fois. */
    private CachedServerIcon icon;
    private boolean iconTried;

    public NoelService(LyonAchievements plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "noel-donnees.yml");
        this.data = dataFile.exists() ? YamlConfiguration.loadConfiguration(dataFile) : new YamlConfiguration();
        this.calendar = new AdventCalendar(plugin, this);
        this.treasure = new TreasureHunt(plugin, this);
    }

    public AdventCalendar calendar() {
        return calendar;
    }

    public TreasureHunt treasure() {
        return treasure;
    }

    public ConfigurationSection cfg() {
        return plugin.configs().noel();
    }

    /** Après /lmc reload : on recalcule l'icône. */
    public void reload() {
        icon = null;
        iconTried = false;
    }

    // ───────────────────────────── Dates ─────────────────────────────

    /** Aujourd'hui (fuseau du serveur), ou la date de test de noel.yml. */
    public LocalDate today() {
        String test = cfg().getString("periode.date-test", "");
        if (test != null && !test.isBlank()) {
            try {
                return LocalDate.parse(test.trim());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("noel.yml : date-test invalide (" + test + "), format attendu 2026-12-05");
            }
        }
        return LocalDate.now(plugin.text().zone());
    }

    /** Heure actuelle en millisecondes, décalée si une date de test est réglée (même heure, autre jour). */
    public long now() {
        long real = System.currentTimeMillis();
        LocalDate realDay = LocalDate.now(plugin.text().zone());
        return real + ChronoUnit.DAYS.between(realDay, today()) * 86_400_000L;
    }

    private MonthDay monthDay(String path, String def) {
        try {
            return MonthDay.parse("--" + cfg().getString(path, def).trim());
        } catch (RuntimeException e) {
            return MonthDay.parse("--" + def);
        }
    }

    /** Premier jour de la période qui contient (ou précède) cette date. */
    public LocalDate periodStart(LocalDate day) {
        MonthDay start = monthDay("periode.debut", "12-01");
        LocalDate s = start.atYear(day.getYear());
        return s.isAfter(day) ? start.atYear(day.getYear() - 1) : s;
    }

    public LocalDate periodEnd(LocalDate start) {
        MonthDay end = monthDay("periode.fin", "01-06");
        LocalDate e = end.atYear(start.getYear());
        return e.isBefore(start) ? end.atYear(start.getYear() + 1) : e;
    }

    public boolean active() {
        return active(today());
    }

    public boolean active(LocalDate day) {
        if (!plugin.module("noel")) return false;
        LocalDate s = periodStart(day);
        return !day.isAfter(periodEnd(s));
    }

    /** Jours restants avant le 25 décembre (0 le jour de Noël). */
    public long daysToChristmas() {
        LocalDate t = today();
        LocalDate xmas = LocalDate.of(t.getYear(), 12, 25);
        if (t.isAfter(xmas)) xmas = xmas.plusYears(1);
        return ChronoUnit.DAYS.between(t, xmas);
    }

    public String daysToChristmasText() {
        long d = daysToChristmas();
        return d == 0 ? "0 jour" : d + " jour" + (d > 1 ? "s" : "");
    }

    // ───────────────────────────── MOTD et icône ─────────────────────────────

    public String motdLine1() {
        return cfg().getString("motd.line1", "");
    }

    public List<String> motdLine2() {
        return cfg().getStringList("motd.line2");
    }

    /** Icône de Noël : noel-icone.png si elle existe, sinon un bonnet ajouté à server-icon.png. */
    public CachedServerIcon icon() {
        if (!cfg().getBoolean("icone", true)) return null;
        if (iconTried) return icon;
        iconTried = true;
        try {
            File custom = new File(plugin.getDataFolder(), "noel-icone.png");
            BufferedImage img;
            if (custom.isFile()) {
                img = ImageIO.read(custom);
            } else {
                File base = new File(Bukkit.getWorldContainer(), "server-icon.png");
                if (!base.isFile()) return null;
                img = withHat(ImageIO.read(base));
            }
            if (img == null || img.getWidth() != 64 || img.getHeight() != 64) {
                plugin.getLogger().warning("Icône de Noël : il faut une image de 64 × 64 pixels.");
                return null;
            }
            icon = Bukkit.loadServerIcon(img);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Icône de Noël impossible à préparer", e);
        }
        return icon;
    }

    /** Un bonnet de Noël en pixel art (16 × 16, agrandi 4 fois) posé sur l'icône. */
    static BufferedImage withHat(BufferedImage base) {
        if (base == null) return null;
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(base, 0, 0, 64, 64, null);
        String[] hat = {
                "...........WW...",
                "..........WWWW..",
                ".........RWWWW..",
                "........RRRWW...",
                ".......RRRR.....",
                "......RRRRDR....",
                ".....RRRRRRDR...",
                "....RRRRRRRRDR..",
                "..WWWWWWWWWWWWW.",
                "..WSWWWWSWWWWSW.",
        };
        Color red = new Color(0xD7, 0x2B, 0x2B), dark = new Color(0x9E, 0x17, 0x1C), white = new Color(0xF4, 0xF6, 0xF8),
                shade = new Color(0xC9, 0xD3, 0xDC);
        for (int y = 0; y < hat.length; y++) {
            for (int x = 0; x < hat[y].length(); x++) {
                Color c = switch (hat[y].charAt(x)) {
                    case 'R' -> red;
                    case 'D' -> dark;
                    case 'W' -> white;
                    case 'S' -> shade;
                    default -> null;
                };
                if (c == null) continue;
                g.setColor(c);
                g.fillRect(x * 4, y * 4, 4, 4);
            }
        }
        g.dispose();
        return out;
    }

    // ───────────────────────────── Données (noel-donnees.yml) ─────────────────────────────

    public YamlConfiguration data() {
        return data;
    }

    public void markDirty() {
        dirty = true;
    }

    /** Écrit le fichier s'il a changé (appelé souvent : l'écriture se fait à côté). */
    public void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        String content = data.saveToString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(content));
    }

    public void saveSync() {
        if (!dirty) return;
        dirty = false;
        write(data.saveToString());
    }

    private synchronized void write(String content) {
        try {
            Path target = dataFile.toPath();
            Path tmp = target.resolveSibling(dataFile.getName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'enregistrer noel-donnees.yml", e);
        }
    }

    // ───────────────────────────── Récompenses ─────────────────────────────

    public Bundle bundle(String path) {
        return Bundle.of(cfg().getConfigurationSection(path));
    }

    /** Texte d'un lot pour le site : le « texte » de noel.yml, sinon une description simple. */
    public String describe(ConfigurationSection c) {
        if (c == null) return "";
        String t = c.getString("texte");
        if (t != null && !t.isBlank()) return t;
        List<String> parts = new ArrayList<>();
        if (c.getLong("coins") > 0) parts.add(c.getLong("coins") + " pièces");
        for (String it : c.getStringList("items")) {
            String[] p = it.split(":");
            parts.add((p.length > 1 ? p[1] + " × " : "") + fr.lyonmc.achievements.core.Items.pretty(p[0]));
        }
        return String.join(", ", parts);
    }

    // ───────────────────────────── Site (noel.json) ─────────────────────────────

    public Map<String, Object> webData() {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate t = today();
        LocalDate start = periodStart(t);
        m.put("actif", active(t));
        m.put("debut", start.toString());
        m.put("fin", periodEnd(start).toString());
        m.put("aujourdhui", t.toString());
        m.put("joursAvantNoel", daysToChristmas());
        m.put("calendrier", calendar.webData(t));
        m.put("tresor", treasure.webData());
        Map<String, Object> boss = new LinkedHashMap<>();
        boss.put("actif", cfg().getBoolean("boss.enabled", true));
        boss.put("date", cfg().getString("boss.date", "12-24"));
        boss.put("heure", cfg().getString("boss.heure", "21:00"));
        boss.put("nom", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(plugin.text().parse(cfg().getString("boss.nom", "Le Père Fouettard"))));
        boss.put("cadeau", describe(cfg().getConfigurationSection("boss.cadeau")));
        m.put("boss", boss);
        return m;
    }
}
