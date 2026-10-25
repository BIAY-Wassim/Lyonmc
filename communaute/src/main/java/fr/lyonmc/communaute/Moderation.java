package fr.lyonmc.communaute;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Modération : filtre du chat (spam, majuscules, publicité, insultes), /mute, /avertir, /tempban et l'historique
 * des sanctions (/sanctions). Tout est noté dans plugins/COMMUNAUTE/sanctions.yml.
 */
public final class Moderation implements Listener {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(?:[.,]\\d{1,3}){3}\\b");
    private static final Pattern DOMAIN = Pattern.compile("(?i)\\b[a-z0-9-]{2,}\\s?(?:\\.|\\(\\.\\)|\\[\\.\\]|\\spoint\\s)\\s?(?:fr|com|net|org|eu|gg|io|me|tk|xyz|be|ch|ca|co)\\b");

    private final Communaute plugin;
    private final File file;
    private final YamlConfiguration data;
    /** Joueurs réduits au silence : fin (ms) et raison. Lu par le chat (asynchrone). */
    private final Map<UUID, long[]> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, String> muteReasons = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessage = new ConcurrentHashMap<>();

    Moderation(Communaute plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "sanctions.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection m = data.getConfigurationSection("mutes");
        if (m != null) {
            for (String k : m.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(k);
                    long until = m.getLong(k + ".fin");
                    if (until > System.currentTimeMillis()) {
                        mutes.put(u, new long[]{until});
                        muteReasons.put(u, m.getString(k + ".raison", ""));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private ConfigurationSection cfg() {
        return plugin.section("moderation");
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'enregistrer sanctions.yml", e);
        }
    }

    static boolean staff(CommandSender s) {
        return s.hasPermission("communaute.staff");
    }

    // ───────────────────────────── Durées ─────────────────────────────

    /** « 30m », « 2h », « 1j », « 1s » (semaine) → millisecondes ; 0 si illisible. */
    static long parseDuration(String s) {
        if (s == null) return 0;
        var m = Pattern.compile("(?i)^(\\d+)\\s*(s|sec|min|m|h|j|d|sem|w)?$").matcher(s.trim());
        if (!m.matches()) return 0;
        long n = Long.parseLong(m.group(1));
        String u = m.group(2) == null ? "m" : m.group(2).toLowerCase(Locale.ROOT);
        return switch (u) {
            case "s", "sec" -> n * 1000L;
            case "h" -> n * 3_600_000L;
            case "j", "d" -> n * 86_400_000L;
            case "sem", "w" -> n * 7 * 86_400_000L;
            default -> n * 60_000L;
        };
    }

    static String duration(long ms) {
        long m = Math.max(1, ms / 60000);
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 48) return h + " h" + (m % 60 > 0 ? " " + m % 60 + " min" : "");
        return (h / 24) + " j";
    }

    private String now() {
        return ZonedDateTime.now(plugin.zone()).format(DATE);
    }

    private void history(UUID u, String name, String type, String reason, String by, String length) {
        String base = "historique." + u;
        List<String> list = new ArrayList<>(data.getStringList(base + ".liste"));
        list.add(now() + " | " + type + (length == null ? "" : " " + length) + " | " + reason + " | par " + by);
        data.set(base + ".pseudo", name);
        data.set(base + ".liste", list);
        save();
        plugin.getLogger().info("[Sanction] " + type + " " + name + (length == null ? "" : " (" + length + ")") + " : " + reason + " (par " + by + ")");
    }

    private void notifyStaff(String mini) {
        for (Player s : Bukkit.getOnlinePlayers()) if (staff(s)) s.sendMessage(plugin.parse(mini));
    }

    private OfflinePlayer target(CommandSender s, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        PlayerData d = plugin.friends().known(name);
        if (d != null) return Bukkit.getOfflinePlayer(d.uuid);
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null) plugin.msg(s, "<gray>Joueur inconnu : <white>" + name);
        return cached;
    }

    // ───────────────────────────── /mute, /unmute ─────────────────────────────

    public boolean isMuted(UUID u) {
        long[] m = mutes.get(u);
        if (m == null) return false;
        if (m[0] <= System.currentTimeMillis()) {
            mutes.remove(u);
            return false;
        }
        return true;
    }

    /** Message affiché à un joueur réduit au silence. */
    public Component muteMessage(UUID u) {
        long[] m = mutes.get(u);
        long left = m == null ? 0 : m[0] - System.currentTimeMillis();
        String reason = muteReasons.getOrDefault(u, "");
        return plugin.lyon().prefixed("<red>Tu ne peux pas parler</red> <gray>encore " + duration(left)
                + (reason.isEmpty() ? "" : " (" + escape(reason) + ")") + ". Règlement : <white>/regles");
    }

    void mute(CommandSender s, String[] args) {
        if (args.length < 1) {
            plugin.msg(s, "<gray>Usage : <white>/mute <joueur> [durée] [raison]</white> <dark_gray>(durée : 10m, 2h, 1j... 30 min par défaut)");
            return;
        }
        OfflinePlayer t = target(s, args[0]);
        if (t == null) return;
        long ms = args.length > 1 ? parseDuration(args[1]) : 0;
        int reasonFrom = ms > 0 ? 2 : 1;
        if (ms <= 0) ms = 30 * 60_000L;
        String reason = args.length > reasonFrom ? String.join(" ", Arrays.copyOfRange(args, reasonFrom, args.length)) : "Non-respect du règlement";
        long until = System.currentTimeMillis() + ms;
        mutes.put(t.getUniqueId(), new long[]{until});
        muteReasons.put(t.getUniqueId(), reason);
        data.set("mutes." + t.getUniqueId() + ".fin", until);
        data.set("mutes." + t.getUniqueId() + ".raison", reason);
        history(t.getUniqueId(), t.getName(), "Mute", reason, s.getName(), duration(ms));
        notifyStaff("<#ff8a7a>⚑</#ff8a7a> <white>" + t.getName() + "</white> <gray>réduit au silence " + duration(ms) + " par " + s.getName() + " : " + escape(reason));
        if (t instanceof Player p) {
            p.sendMessage(muteMessage(p.getUniqueId()));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        }
    }

    void unmute(CommandSender s, String[] args) {
        if (args.length < 1) {
            plugin.msg(s, "<gray>Usage : <white>/unmute <joueur>");
            return;
        }
        OfflinePlayer t = target(s, args[0]);
        if (t == null) return;
        if (mutes.remove(t.getUniqueId()) == null) {
            plugin.msg(s, "<gray>" + t.getName() + " n'est pas réduit au silence.");
            return;
        }
        muteReasons.remove(t.getUniqueId());
        data.set("mutes." + t.getUniqueId(), null);
        save();
        plugin.msg(s, "<gray><white>" + t.getName() + "</white> peut de nouveau parler.");
        if (t instanceof Player p) plugin.msg(p, "<gray>Tu peux de nouveau parler dans le chat.");
    }

    // ───────────────────────────── /avertir ─────────────────────────────

    void warn(CommandSender s, String[] args) {
        if (args.length < 2) {
            plugin.msg(s, "<gray>Usage : <white>/avertir <joueur> <raison>");
            return;
        }
        OfflinePlayer t = target(s, args[0]);
        if (t == null) return;
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        history(t.getUniqueId(), t.getName(), "Avertissement", reason, s.getName(), null);
        int count = (int) data.getStringList("historique." + t.getUniqueId() + ".liste").stream().filter(x -> x.contains("| Avertissement")).count();
        notifyStaff("<#ff8a7a>⚑</#ff8a7a> <white>" + t.getName() + "</white> <gray>averti par " + s.getName() + " (" + count + "e avertissement) : " + escape(reason));
        if (t instanceof Player p) {
            p.showTitle(net.kyori.adventure.title.Title.title(plugin.parse("<red><bold>Avertissement"), plugin.parse("<white>" + escape(reason))));
            p.sendMessage(plugin.lyon().prefixed("<red>Avertissement</red> <gray>du staff : <white>" + escape(reason) + "</white>. Règlement : <white>/regles"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        }
    }

    // ───────────────────────────── /tempban ─────────────────────────────

    void tempban(CommandSender s, String[] args) {
        if (args.length < 2 || parseDuration(args[1]) <= 0) {
            plugin.msg(s, "<gray>Usage : <white>/tempban <joueur> <durée> [raison]</white> <dark_gray>(durée : 2h, 1j, 1sem...)");
            return;
        }
        OfflinePlayer t = target(s, args[0]);
        if (t == null || t.getName() == null) return;
        long ms = parseDuration(args[1]);
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Non-respect du règlement";
        t.ban(reason, java.time.Duration.ofMillis(ms), s.getName());
        history(t.getUniqueId(), t.getName(), "Exclusion", reason, s.getName(), duration(ms));
        if (t instanceof Player p) {
            p.kick(plugin.parse("<red><bold>Exclu de LyonMC</bold></red>\n\n<white>" + escape(reason) + "</white>\n<gray>Retour possible dans " + duration(ms)
                    + "\n\n<dark_gray>Règlement : lyonmc.fr/regles"));
        }
        notifyStaff("<#ff8a7a>⚑</#ff8a7a> <white>" + t.getName() + "</white> <gray>exclu " + duration(ms) + " par " + s.getName() + " : " + escape(reason));
    }

    // ───────────────────────────── /sanctions ─────────────────────────────

    void list(CommandSender s, String[] args) {
        if (args.length < 1) {
            plugin.msg(s, "<gray>Usage : <white>/sanctions <joueur>");
            return;
        }
        OfflinePlayer t = target(s, args[0]);
        if (t == null) return;
        List<String> list = data.getStringList("historique." + t.getUniqueId() + ".liste");
        s.sendMessage(plugin.parse("  <#ff8a7a>⚑</#ff8a7a> <white><bold>Sanctions de " + t.getName() + "</bold></white> <dark_gray>(" + list.size() + ")"
                + (isMuted(t.getUniqueId()) ? " <red>· réduit au silence" : "")));
        if (list.isEmpty()) s.sendMessage(plugin.parse("  <dark_gray>Aucune sanction."));
        for (String line : list.subList(Math.max(0, list.size() - 15), list.size())) {
            s.sendMessage(plugin.parse("  <gray>" + escape(line)));
        }
    }

    // ───────────────────────────── Filtre du chat ─────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (isMuted(u)) {
            e.setCancelled(true);
            p.sendMessage(muteMessage(u));
            return;
        }
        ConfigurationSection c = cfg();
        if (c == null || !c.getBoolean("filtre.enabled", true) || staff(p)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        long now = System.currentTimeMillis();

        // Flood : trop de messages d'affilée
        Deque<Long> times = recent.computeIfAbsent(u, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 5000) times.pollFirst();
            if (times.size() >= c.getInt("filtre.messages-par-5-secondes", 4)) {
                e.setCancelled(true);
                p.sendMessage(plugin.lyon().prefixed("<gray>Doucement ! Attends quelques secondes avant d'écrire."));
                return;
            }
            times.addLast(now);
        }
        // Le même message répété
        String norm = simplify(text);
        String prev = lastMessage.put(u, norm + "|" + now);
        if (prev != null && c.getBoolean("filtre.anti-repetition", true)) {
            int bar = prev.lastIndexOf('|');
            if (prev.substring(0, bar).equals(norm) && now - Long.parseLong(prev.substring(bar + 1)) < 30_000) {
                e.setCancelled(true);
                p.sendMessage(plugin.lyon().prefixed("<gray>Tu viens déjà d'envoyer ce message."));
                return;
            }
        }
        // Publicité : adresses IP et sites
        if (c.getBoolean("filtre.anti-pub", true) && advertises(text, c)) {
            e.setCancelled(true);
            p.sendMessage(plugin.lyon().prefixed("<red>La publicité est interdite</red> <gray>(adresses, sites). Règlement : <white>/regles"));
            notifyStaff("<#ff8a7a>⚑</#ff8a7a> <gray>Pub bloquée de <white>" + p.getName() + "</white> : " + escape(text));
            return;
        }
        // Mots interdits
        for (String w : c.getStringList("filtre.mots-interdits")) {
            if (w.isBlank()) continue;
            String sw = simplify(w);
            if (Pattern.compile("(?<![a-z0-9])" + Pattern.quote(sw) + "(?![a-z0-9])").matcher(norm).find()) {
                e.setCancelled(true);
                p.sendMessage(plugin.lyon().prefixed("<red>Ton message n'a pas été envoyé</red> <gray>: langage interdit sur LyonMC. Règlement : <white>/regles"));
                notifyStaff("<#ff8a7a>⚑</#ff8a7a> <gray>Message bloqué de <white>" + p.getName() + "</white> : " + escape(text));
                return;
            }
        }
        // Majuscules : le message passe, en minuscules
        if (c.getBoolean("filtre.anti-majuscules", true)) {
            long letters = text.chars().filter(Character::isLetter).count();
            long upper = text.chars().filter(Character::isUpperCase).count();
            if (letters >= 8 && upper * 100 / letters >= c.getInt("filtre.majuscules-pourcent", 70)) {
                e.message(e.message().replaceText(b -> b.match(Pattern.compile("(?s).+")).replacement((m, tb) -> tb.content(tb.content().toLowerCase(Locale.FRANCE)))));
            }
        }
    }

    private boolean advertises(String text, ConfigurationSection c) {
        String low = text.toLowerCase(Locale.ROOT);
        for (String ok : c.getStringList("filtre.sites-autorises")) low = low.replace(ok.toLowerCase(Locale.ROOT), "");
        return IP.matcher(low).find() || DOMAIN.matcher(low).find();
    }

    /** Minuscules, sans accents, sans répétitions de lettres (« saluuuut » → « salut »), chiffres-lettres courants. */
    static String simplify(String s) {
        String t = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('@', 'a').replace('$', 's');
        return t.replaceAll("(.)\\1{2,}", "$1").replaceAll("\\s+", " ").trim();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("<", "‹").replace(">", "›");
    }

    private static final java.util.Set<String> CHAT_COMMANDS = java.util.Set.of("me", "tell", "msg", "w", "whisper", "teammsg", "tm",
            "mp", "message", "pm", "r", "reply", "repondre", "répondre");

    /** Réduit au silence : pas non plus de /me, /msg, /r... */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        if (!isMuted(e.getPlayer().getUniqueId())) return;
        String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (CHAT_COMMANDS.contains(label)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(muteMessage(e.getPlayer().getUniqueId()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        recent.remove(e.getPlayer().getUniqueId());
        lastMessage.remove(e.getPlayer().getUniqueId());
    }

    static String date(long ms, java.time.ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(DATE);
    }
}
