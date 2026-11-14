package fr.lyonmc.communaute;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Boîte à idées du site (lyonmc.fr/suggestions) : les joueurs venus par le lien de /suggestion proposent des idées et votent ;
 * le staff leur donne un statut et peut répondre. Tout est enregistré dans suggestions.yml, et publié dans
 * siteweb/data/suggestions.json (la liste reste lisible sur le site quand le serveur est éteint).
 */
final class Suggestions {

    /** Statuts, dans l'ordre d'avancement. */
    static final List<String> STATUSES = List.of("nouvelle", "acceptee", "en-cours", "faite", "refusee");
    private static final Map<String, String> LABELS = Map.of("nouvelle", "Nouvelle", "acceptee", "Acceptée", "en-cours", "En cours",
            "faite", "Faite", "refusee", "Refusée");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long DAY = 86_400_000L;

    static final class Suggestion {
        int id;
        String title;
        String text;
        UUID author;
        String authorName;
        long date;
        String status = "nouvelle";
        String reply;
        String replyBy;
        long statusDate;
        final Set<UUID> votes = new LinkedHashSet<>();

        boolean open() {
            return !status.equals("faite") && !status.equals("refusee");
        }
    }

    private final Communaute plugin;
    private final File file;
    private final List<Suggestion> list = new ArrayList<>();
    /** Idées proposées par chacun ces dernières 24 h (même supprimées depuis). */
    private final Map<UUID, Deque<Long>> recent = new HashMap<>();
    private int nextId = 1;

    Suggestions(Communaute plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "suggestions.yml");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("suggestions.enabled", true);
    }

    private int perDay() {
        return Math.max(1, plugin.getConfig().getInt("suggestions.par-jour", 3));
    }

    private int titleMax() {
        return Math.max(20, plugin.getConfig().getInt("suggestions.titre-max", 80));
    }

    private int textMax() {
        return Math.max(0, plugin.getConfig().getInt("suggestions.texte-max", 700));
    }

    // ───────────────────────────── Fichiers ─────────────────────────────

    synchronized void load() {
        list.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, y.getInt("prochain-id", 1));
        ConfigurationSection c = y.getConfigurationSection("suggestions");
        if (c == null) return;
        for (String k : c.getKeys(false)) {
            ConfigurationSection s = c.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Suggestion o = new Suggestion();
                o.id = Integer.parseInt(k);
                o.title = s.getString("titre", "");
                o.text = s.getString("texte", "");
                o.author = UUID.fromString(s.getString("auteur", ""));
                o.authorName = s.getString("pseudo", "?");
                o.date = s.getLong("date");
                o.status = STATUSES.contains(s.getString("statut")) ? s.getString("statut") : "nouvelle";
                o.reply = s.getString("reponse");
                o.replyBy = s.getString("reponse-par");
                o.statusDate = s.getLong("date-statut");
                for (String v : s.getStringList("votes")) {
                    try {
                        o.votes.add(UUID.fromString(v));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                list.add(o);
                nextId = Math.max(nextId, o.id + 1);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("suggestions.yml : suggestion " + k + " illisible, ignorée.");
            }
        }
        list.sort((a, b) -> Integer.compare(a.id, b.id));
    }

    /** À appeler avec le verrou (synchronized). */
    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Suggestions des joueurs (page Suggestions du site). Statuts : " + String.join(", ", STATUSES) + "."));
        y.set("prochain-id", nextId);
        for (Suggestion s : list) {
            String k = "suggestions." + s.id;
            y.set(k + ".titre", s.title);
            if (!s.text.isEmpty()) y.set(k + ".texte", s.text);
            y.set(k + ".auteur", s.author.toString());
            y.set(k + ".pseudo", s.authorName);
            y.set(k + ".date", s.date);
            y.set(k + ".statut", s.status);
            if (s.reply != null) {
                y.set(k + ".reponse", s.reply);
                y.set(k + ".reponse-par", s.replyBy);
            }
            if (s.statusDate > 0) y.set(k + ".date-statut", s.statusDate);
            List<String> votes = new ArrayList<>();
            for (UUID u : s.votes) votes.add(u.toString());
            y.set(k + ".votes", votes);
        }
        try {
            write(file.toPath(), y.saveToString());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'enregistrer suggestions.yml", e);
        }
    }

    /** Écrit un fichier d'un coup (fichier temporaire puis remplacement) : jamais à moitié écrit. */
    static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** suggestions.json pour le site (la liste publique, sans rien sur les visiteurs). */
    void export() {
        Path dir = plugin.webExport().folder();
        if (dir == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generated", System.currentTimeMillis());
        List<Map<String, Object>> items = new ArrayList<>();
        synchronized (this) {
            for (Suggestion s : list) items.add(item(s, null));
        }
        m.put("suggestions", items);
        String json = GSON.toJson(m);
        Runnable task = () -> {
            try {
                write(dir.resolve("suggestions.json"), json);
            } catch (IOException e) {
                plugin.getLogger().fine("Export suggestions.json impossible : " + e.getMessage());
            }
        };
        if (Bukkit.isPrimaryThread()) Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        else task.run();
    }

    // ───────────────────────────── Pour le site ─────────────────────────────

    private Map<String, Object> item(Suggestion s, Site.Account me) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", s.id);
        o.put("titre", s.title);
        o.put("texte", s.text);
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("uuid", s.author.toString());
        author.put("pseudo", plugin.site() == null ? s.authorName : plugin.site().nameOf(s.author, s.authorName));
        o.put("auteur", author);
        o.put("date", s.date);
        o.put("statut", s.status);
        o.put("votes", s.votes.size());
        if (s.reply != null) {
            o.put("reponse", s.reply);
            o.put("reponsePar", s.replyBy);
        }
        if (s.statusDate > 0) o.put("dateStatut", s.statusDate);
        if (me != null) {
            o.put("aVote", s.votes.contains(me.uuid()));
            o.put("deMoi", s.author.equals(me.uuid()));
        }
        return o;
    }

    Map<String, Object> api(Site.Account me) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("moi", Site.me(me));
        m.put("ouvert", enabled());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("titre", titleMax());
        limits.put("texte", textMax());
        limits.put("parJour", perDay());
        List<Map<String, Object>> items = new ArrayList<>();
        synchronized (this) {
            for (Suggestion s : list) items.add(item(s, me));
            if (me != null) limits.put("restant", me.staff() ? perDay() : Math.max(0, perDay() - used(me.uuid(), System.currentTimeMillis())));
        }
        m.put("limites", limits);
        m.put("suggestions", items);
        return m;
    }

    /** Idées proposées ces dernières 24 h (avec le verrou). */
    private int used(UUID u, long now) {
        Deque<Long> times = recent.get(u);
        if (times != null) while (!times.isEmpty() && now - times.peekFirst() > DAY) times.pollFirst();
        int inList = 0;
        for (Suggestion s : list) if (s.author.equals(u) && now - s.date <= DAY) inList++;
        return Math.max(inList, times == null ? 0 : times.size());
    }

    private Suggestion find(int id) {
        for (Suggestion s : list) if (s.id == id) return s;
        throw new Site.ApiError(404, "Cette suggestion n'existe plus.");
    }

    /** Espaces en trop et caractères invisibles retirés ; « multiline » garde les retours à la ligne (deux au plus). */
    static String clean(String s, boolean multiline) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\p{Cc}&&[^\\n]]", " ").replaceAll("\\p{Cf}", "");
        if (!multiline) t = t.replace('\n', ' ');
        t = t.replaceAll("[ \\t\\u00A0]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        return t;
    }

    /** Titre presque tout en majuscules : remis en minuscules (première lettre en majuscule). */
    private static String calm(String s) {
        long letters = s.chars().filter(Character::isLetter).count();
        long upper = s.chars().filter(Character::isUpperCase).count();
        if (letters < 8 || upper * 100 / letters < 70) return s;
        String low = s.toLowerCase(Locale.FRANCE);
        return low.substring(0, 1).toUpperCase(Locale.FRANCE) + low.substring(1);
    }

    Map<String, Object> create(Site.Account me, String rawTitle, String rawText) {
        if (!enabled()) throw new Site.ApiError(403, "Les suggestions sont fermées pour l'instant.");
        String title = calm(clean(rawTitle, false));
        String text = clean(rawText, true);
        if (title.codePointCount(0, title.length()) < 5) throw new Site.ApiError(400, "Donne un titre à ton idée (5 caractères au moins).");
        if (title.length() > titleMax()) throw new Site.ApiError(400, "Titre trop long : " + titleMax() + " caractères au plus.");
        if (text.length() > textMax()) throw new Site.ApiError(400, "Détails trop longs : " + textMax() + " caractères au plus.");
        String refused = plugin.site().onMain(() -> {
            if (Bukkit.getOfflinePlayer(me.uuid()).isBanned()) return "Tu es banni du serveur : tu ne peux pas proposer d'idée.";
            if (plugin.moderation().isMuted(me.uuid())) return "Tu es réduit au silence pour l'instant : réessaie plus tard.";
            return plugin.moderation().refuse(title + "\n" + text);
        });
        if (refused != null) throw new Site.ApiError(400, refused);
        Suggestion s;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (!me.staff() && used(me.uuid(), now) >= perDay()) {
                throw new Site.ApiError(429, "Tu as déjà proposé " + perDay() + " idée" + (perDay() > 1 ? "s" : "") + " aujourd'hui : reviens demain !");
            }
            String key = Moderation.simplify(title);
            for (Suggestion o : list) {
                if (o.open() && Moderation.simplify(o.title).equals(key)) throw new Site.ApiError(409, "Cette idée existe déjà (n°" + o.id + ") : vote pour elle !");
            }
            s = new Suggestion();
            s.id = nextId++;
            s.title = title;
            s.text = text;
            s.author = me.uuid();
            s.authorName = me.name();
            s.date = now;
            s.votes.add(me.uuid());
            list.add(s);
            recent.computeIfAbsent(me.uuid(), k -> new ArrayDeque<>()).addLast(now);
            save();
        }
        export();
        plugin.getLogger().info("Suggestion n°" + s.id + " de " + me.name() + " : " + title);
        String link = plugin.site().siteUrl() + "/suggestions";
        Bukkit.getScheduler().runTask(plugin, () -> {
            Component msg = plugin.lyon().prefixed("<gray>Nouvelle idée de <white>" + me.name() + "</white> sur le site : ")
                    .append(Component.text(title, NamedTextColor.WHITE))
                    .append(plugin.parse(" <click:open_url:'" + link + "'><hover:show_text:'<gray>Ouvrir la page Suggestions'><dark_gray>[voir]</dark_gray></hover></click>"));
            for (Player p : Bukkit.getOnlinePlayers()) if (Moderation.staff(p) && !p.getUniqueId().equals(me.uuid())) p.sendMessage(msg);
        });
        synchronized (this) {
            return item(s, me);
        }
    }

    synchronized Map<String, Object> vote(Site.Account me, int id) {
        Suggestion s = find(id);
        if (s.author.equals(me.uuid())) throw new Site.ApiError(400, "C'est ton idée : ton vote est déjà compté.");
        if (!s.open()) throw new Site.ApiError(400, "Cette suggestion est fermée (" + LABELS.get(s.status).toLowerCase(Locale.FRANCE) + ").");
        if (!s.votes.remove(me.uuid())) s.votes.add(me.uuid());
        save();
        Map<String, Object> out = item(s, me);
        Bukkit.getScheduler().runTask(plugin, this::export);
        return out;
    }

    synchronized Map<String, Object> status(Site.Account me, int id, String status, String reply) {
        if (!me.staff()) throw new Site.ApiError(403, "Réservé au staff.");
        Suggestion s = find(id);
        boolean changed = false;
        if (status != null && !status.isBlank()) {
            String st = status.trim().toLowerCase(Locale.ROOT);
            if (!STATUSES.contains(st)) throw new Site.ApiError(400, "Statut inconnu.");
            if (!st.equals(s.status)) {
                s.status = st;
                s.statusDate = System.currentTimeMillis();
                changed = true;
            }
        }
        if (reply != null) {
            String r = clean(reply, true);
            if (r.length() > 500) throw new Site.ApiError(400, "Réponse trop longue : 500 caractères au plus.");
            s.reply = r.isEmpty() ? null : r;
            s.replyBy = r.isEmpty() ? null : me.name();
        }
        save();
        plugin.getLogger().info("Suggestion n°" + s.id + " : statut " + s.status + (s.reply != null ? ", réponse de " + s.replyBy : "") + " (par " + me.name() + ")");
        Map<String, Object> out = item(s, me);
        boolean tell = changed;
        String title = s.title, label = LABELS.get(s.status), replyText = s.reply;
        UUID author = s.author;
        Bukkit.getScheduler().runTask(plugin, () -> {
            export();
            Player p = Bukkit.getPlayer(author);
            if (!tell || p == null) return;
            Component msg = plugin.lyon().prefixed("<gray>Ta suggestion ").append(Component.text("« " + title + " »", NamedTextColor.WHITE))
                    .append(plugin.parse(" <gray>est maintenant <white>" + label + "</white>."));
            p.sendMessage(msg);
            if (replyText != null) p.sendMessage(Component.text("  " + replyText, NamedTextColor.GRAY));
        });
        return out;
    }

    synchronized Map<String, Object> delete(Site.Account me, int id) {
        Suggestion s = find(id);
        boolean own = s.author.equals(me.uuid()) && s.status.equals("nouvelle");
        if (!me.staff() && !own) throw new Site.ApiError(403, "Tu ne peux pas supprimer cette suggestion.");
        list.remove(s);
        save();
        plugin.getLogger().info("Suggestion n°" + s.id + " supprimée par " + me.name() + ".");
        Bukkit.getScheduler().runTask(plugin, this::export);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supprimee", s.id);
        return out;
    }
}
