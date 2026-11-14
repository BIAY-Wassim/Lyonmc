package fr.lyonmc.communaute;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le site lyonmc.fr et le serveur : connexion des joueurs avec leur compte Minecraft, et API de la page Suggestions.
 * <ul>
 *   <li>En jeu, /site donne un lien (et un code) valable 10 minutes, utilisable une seule fois.</li>
 *   <li>Le site l'échange contre un cookie de session signé, valable 30 jours.</li>
 *   <li>Le plugin répond sur 127.0.0.1:8135 ; Apache relaie lyonmc.fr/api/ vers cette adresse.</li>
 * </ul>
 */
final class Site implements Listener {

    /** Compte du site : un joueur qui s'est connecté au moins une fois avec /site. */
    record Account(UUID uuid, String name, boolean staff, long validAfter) {
    }

    /** Erreur renvoyée au site : code HTTP et message affiché tel quel. */
    static final class ApiError extends RuntimeException {
        final int status;

        ApiError(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    private record Code(UUID uuid, String name, boolean staff, long expires) {
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** Sans I, O, 0 ni 1 : rien à confondre en recopiant le code. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long CODE_MS = 10 * 60_000L;
    private static final long SESSION_S = 30L * 86_400L;
    private static final String COOKIE = "lmc_session";
    private static final int MAX_BODY = 8192;
    private static final long FAIL_WINDOW = 15 * 60_000L;
    private static final Pattern ACTION = Pattern.compile("^/api/suggestions/(\\d{1,9})/(vote|statut|supprimer)$");

    private final Communaute plugin;
    private final File file;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Map<UUID, String> codeOf = new ConcurrentHashMap<>();
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCode = new ConcurrentHashMap<>();
    private volatile byte[] secret;
    private HttpServer server;
    private ExecutorService executor;

    Site(Communaute plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "site.yml");
    }

    // ───────────────────────────── Comptes (site.yml) ─────────────────────────────

    void load() {
        YamlConfiguration y = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        byte[] key = null;
        try {
            String s = y.getString("secret", "");
            if (!s.isBlank()) key = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException ignored) {
        }
        boolean fresh = key == null || key.length < 32;
        if (fresh) {
            key = new byte[32];
            random.nextBytes(key);
        }
        secret = key;
        ConfigurationSection c = y.getConfigurationSection("comptes");
        if (c != null) {
            for (String k : c.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(k);
                    accounts.put(u, new Account(u, c.getString(k + ".pseudo", "?"), c.getBoolean(k + ".staff"), c.getLong(k + ".valide-apres")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (fresh) save();
    }

    synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Comptes du site lyonmc.fr (les joueurs s'y connectent avec /site en jeu).",
                "secret : la clé des sessions du site. Ne la donne à personne ; la supprimer déconnecte tout le monde du site."));
        y.set("secret", Base64.getEncoder().encodeToString(secret));
        for (Account a : accounts.values()) {
            String k = "comptes." + a.uuid();
            y.set(k + ".pseudo", a.name());
            y.set(k + ".staff", a.staff());
            if (a.validAfter() > 0) y.set(k + ".valide-apres", a.validAfter());
        }
        try {
            Suggestions.write(file.toPath(), y.saveToString());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'enregistrer site.yml", e);
        }
    }

    /** Depuis le fil du serveur : enregistré à côté, pour ne pas le ralentir. */
    private void saveSoon() {
        if (Bukkit.isPrimaryThread()) Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
        else save();
    }

    Account account(UUID u) {
        return accounts.get(u);
    }

    /** Pseudo actuel d'un joueur (il a pu en changer depuis). */
    String nameOf(UUID u, String fallback) {
        Account a = accounts.get(u);
        return a == null ? fallback : a.name();
    }

    /** Pseudo et rôle (staff ou non) mis à jour à chaque connexion en jeu. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Account a = accounts.get(p.getUniqueId());
        if (a == null) return;
        boolean staff = Moderation.staff(p);
        if (a.name().equals(p.getName()) && a.staff() == staff) return;
        accounts.put(a.uuid(), new Account(a.uuid(), p.getName(), staff, a.validAfter()));
        saveSoon();
    }

    // ───────────────────────────── /site ─────────────────────────────

    String siteUrl() {
        String u = plugin.getConfig().getString("site.adresse", "https://lyonmc.fr").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.isEmpty() ? "https://lyonmc.fr" : u;
    }

    static String display(String url) {
        return url.replaceFirst("(?i)^https?://", "");
    }

    void command(Player p, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String url = siteUrl();
        if (sub.startsWith("deconnex") || sub.startsWith("déconnex") || sub.startsWith("deconnect") || sub.startsWith("déconnect")) {
            Account a = accounts.get(p.getUniqueId());
            if (a == null) {
                plugin.msg(p, "<gray>Tu n'es connecté au site sur aucun appareil.");
                return;
            }
            accounts.put(a.uuid(), new Account(a.uuid(), a.name(), a.staff(), System.currentTimeMillis() / 1000 + 1));
            saveSoon();
            plugin.msg(p, "<gray>C'est fait : tu es déconnecté du site sur tous tes appareils.");
            return;
        }
        if (server == null) {
            plugin.msg(p, "<gray>Le site : <click:open_url:'" + url + "'><white><u>" + display(url) + "</u></white></click> "
                    + "<dark_gray>(la connexion au site est indisponible pour l'instant)");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastCode.get(p.getUniqueId());
        if (last != null && now - last < 5000) {
            plugin.msg(p, "<gray>Attends quelques secondes avant de demander un nouveau lien.");
            return;
        }
        lastCode.put(p.getUniqueId(), now);
        String old = codeOf.remove(p.getUniqueId());
        if (old != null) codes.remove(old);
        String code = newCode();
        codes.put(code, new Code(p.getUniqueId(), p.getName(), Moderation.staff(p), now + CODE_MS));
        codeOf.put(p.getUniqueId(), code);
        String pretty = code.substring(0, 4) + "-" + code.substring(4);
        String link = url + "/suggestions#code=" + code;
        p.sendMessage(plugin.lyon().prefixed("<gray>Connecte-toi au site pour proposer tes idées et voter :"));
        p.sendMessage(plugin.parse("  <click:open_url:'" + link + "'><hover:show_text:'<gray>Ouvre " + display(url) + "/suggestions, connecté en tant que <white>"
                + p.getName() + "'><green><bold>➤ Se connecter au site</bold></green></hover></click>"));
        p.sendMessage(plugin.parse("  <gray>Ou entre ce code sur la page Suggestions : <click:copy_to_clipboard:'" + pretty
                + "'><hover:show_text:'<gray>Copier le code'><white>" + pretty + "</white></hover></click>"));
        p.sendMessage(plugin.parse("  <dark_gray>Valable 10 minutes, une seule fois. Ne le donne à personne."));
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        String c = sb.toString();
        return codes.containsKey(c) ? newCode() : c;
    }

    /** Toutes les 30 secondes : codes expirés et compteurs d'essais. */
    void cleanup() {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(e -> e.getValue().expires() < now);
        codeOf.entrySet().removeIf(e -> !codes.containsKey(e.getValue()));
        failures.entrySet().removeIf(e -> now - e.getValue()[1] > FAIL_WINDOW);
        lastCode.entrySet().removeIf(e -> now - e.getValue() > 60_000L);
    }

    // ───────────────────────────── Sessions ─────────────────────────────

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String token(UUID u, long issued) {
        String payload = u.toString().replace("-", "") + "." + Long.toString(issued, 36);
        return payload + "." + sign(payload);
    }

    /** Joueur connecté au site (cookie de session valide), ou null. */
    private Account session(HttpExchange ex) {
        String t = cookie(ex);
        if (t == null) return null;
        String[] parts = t.split("\\.");
        if (parts.length != 3 || !parts[0].matches("[0-9a-f]{32}")) return null;
        byte[] expected = sign(parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.US_ASCII))) return null;
        long issued;
        try {
            issued = Long.parseLong(parts[1], 36);
        } catch (NumberFormatException e) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000;
        if (issued > now + 60 || now - issued > SESSION_S) return null;
        String h = parts[0];
        UUID u = UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20));
        Account a = accounts.get(u);
        return a == null || issued < a.validAfter() ? null : a;
    }

    private static String cookie(HttpExchange ex) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) {
            for (String part : h.split(";")) {
                String s = part.trim();
                if (s.startsWith(COOKIE + "=")) return s.substring(COOKIE.length() + 1);
            }
        }
        return null;
    }

    private String setCookie(String value, long maxAge) {
        boolean https = siteUrl().toLowerCase(Locale.ROOT).startsWith("https://");
        return COOKIE + "=" + value + "; Path=/; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax" + (https ? "; Secure" : "");
    }

    static Map<String, Object> me(Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connecte", a != null);
        if (a != null) {
            m.put("uuid", a.uuid().toString());
            m.put("pseudo", a.name());
            m.put("staff", a.staff());
        }
        return m;
    }

    // ───────────────────────────── Serveur web interne ─────────────────────────────

    boolean running() {
        return server != null;
    }

    void start() {
        stop();
        if (!plugin.getConfig().getBoolean("site.api.enabled", true)) return;
        String host = plugin.getConfig().getString("site.api.adresse", "127.0.0.1");
        int port = plugin.getConfig().getInt("site.api.port", 8135);
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(host, port), 32);
            ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "COMMUNAUTE-site");
                t.setDaemon(true);
                return t;
            });
            s.setExecutor(pool);
            s.createContext("/api/", this::handle);
            s.start();
            server = s;
            executor = pool;
            plugin.getLogger().info("Site : connexion et suggestions prêtes sur http://" + host + ":" + port + "/api/ (Apache y relaie lyonmc.fr/api/).");
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Site : impossible d'ouvrir " + host + ":" + port + " (" + e.getMessage()
                    + "). La page Suggestions du site restera en lecture seule.");
        }
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.length() > 5 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            Account me = session(ex);
            Object out;
            if (method.equals("GET")) {
                out = switch (path) {
                    case "/api/moi" -> me(me);
                    case "/api/suggestions" -> plugin.suggestions().api(me);
                    default -> throw new ApiError(404, "Adresse inconnue.");
                };
            } else if (method.equals("POST")) {
                if (!trusted(ex)) throw new ApiError(403, "Requête refusée.");
                out = post(ex, path, me, body(ex));
            } else {
                throw new ApiError(405, "Méthode non autorisée.");
            }
            send(ex, 200, out);
        } catch (ApiError e) {
            sendQuietly(ex, e.status, Map.of("erreur", e.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Site : erreur sur " + ex.getRequestURI(), e);
            sendQuietly(ex, 500, Map.of("erreur", "Erreur du serveur. Réessaie dans un instant."));
        } finally {
            ex.close();
        }
    }

    private Object post(HttpExchange ex, String path, Account me, JsonObject body) {
        if (path.equals("/api/connexion")) return login(ex, body);
        if (path.equals("/api/deconnexion")) {
            ex.getResponseHeaders().add("Set-Cookie", setCookie("", 0));
            return me(null);
        }
        if (me == null) throw new ApiError(401, "Connecte-toi d'abord : tape /site en jeu.");
        Suggestions s = plugin.suggestions();
        if (path.equals("/api/suggestions")) return s.create(me, str(body, "titre"), str(body, "texte"));
        Matcher m = ACTION.matcher(path);
        if (!m.matches()) throw new ApiError(404, "Adresse inconnue.");
        int id = Integer.parseInt(m.group(1));
        return switch (m.group(2)) {
            case "vote" -> s.vote(me, id);
            case "statut" -> s.status(me, id, str(body, "statut"), str(body, "reponse"));
            default -> s.delete(me, id);
        };
    }

    private Object login(HttpExchange ex, JsonObject body) {
        String ip = ip(ex);
        long now = System.currentTimeMillis();
        long[] f = failures.get(ip);
        if (f != null && f[0] >= 10 && now - f[1] < FAIL_WINDOW) throw new ApiError(429, "Trop d'essais. Réessaie dans quelques minutes.");
        String raw = str(body, "code");
        String code = raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        Code c = code.length() == 8 ? codes.remove(code) : null;
        if (c == null || c.expires() < now) {
            failures.compute(ip, (k, v) -> v == null || now - v[1] > FAIL_WINDOW ? new long[]{1, now} : new long[]{v[0] + 1, v[1]});
            throw new ApiError(400, "Code invalide ou expiré. Tape /site en jeu pour en avoir un nouveau.");
        }
        codeOf.remove(c.uuid(), code);
        Account old = accounts.get(c.uuid());
        Account a = new Account(c.uuid(), c.name(), c.staff(), old == null ? 0 : old.validAfter());
        accounts.put(a.uuid(), a);
        save();
        ex.getResponseHeaders().add("Set-Cookie", setCookie(token(a.uuid(), Math.max(now / 1000, a.validAfter())), SESSION_S));
        plugin.getLogger().info("Site : " + a.name() + " s'est connecté au site.");
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(a.uuid());
            if (p != null) p.sendMessage(plugin.lyon().prefixed("<green>✔</green> <gray>Tu es connecté au site <white>" + display(siteUrl()) + "</white>."));
        });
        return me(a);
    }

    /** Requêtes qui modifient : en JSON, et venant du site lui-même (un autre site ne peut pas agir à ta place). */
    private static boolean trusted(HttpExchange ex) {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) return false;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String host = ex.getRequestHeaders().getFirst("X-Forwarded-Host");
        if (host == null) host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        host = host.split(",")[0].trim();
        if (!host.startsWith("[") && host.contains(":")) host = host.substring(0, host.indexOf(':'));
        try {
            String o = new URI(origin).getHost();
            return o != null && o.equalsIgnoreCase(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** Adresse du visiteur (Apache l'ajoute à la fin de X-Forwarded-For). */
    private static String ip(HttpExchange ex) {
        String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] p = xff.split(",");
            return p[p.length - 1].trim();
        }
        return ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new ApiError(413, "Texte trop long.");
        if (b.length == 0) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new ApiError(400, "Requête illisible.");
        }
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void sendQuietly(HttpExchange ex, int status, Object body) {
        try {
            send(ex, status, body);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** Lance une tâche sur le fil du serveur (API Bukkit) et attend son résultat. */
    <T> T onMain(Callable<T> task) {
        try {
            if (Bukkit.isPrimaryThread()) return task.call();
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ApiError(503, "Le serveur est très occupé : réessaie dans un instant.");
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            throw new ApiError(500, "Erreur du serveur. Réessaie dans un instant.");
        }
    }
}
