package fr.lyonmc.achievements.discord;

import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;

/** Annonces sur un salon Discord via un webhook (optionnel). */
public final class DiscordService {

    private final JavaPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public DiscordService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        return url != null && url.startsWith("https://");
    }

    /**
     * @param color couleur de la bande (RGB)
     * @param face  joueur dont la tête est affichée (ou null)
     */
    public void send(String title, String description, int color, UUID face) {
        if (!enabled()) return;
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        String name = plugin.getConfig().getString("discord.name", "LyonMC");
        StringBuilder json = new StringBuilder();
        json.append("{\"username\":\"").append(esc(name)).append("\",\"embeds\":[{");
        json.append("\"title\":\"").append(esc(title)).append("\",");
        json.append("\"description\":\"").append(esc(description)).append("\",");
        json.append("\"color\":").append(color);
        if (face != null) {
            json.append(",\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/").append(face).append("/64\"}");
        }
        json.append(",\"footer\":{\"text\":\"lyonmc.fr\"}}]}");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(t -> {
            plugin.getLogger().log(Level.FINE, "Webhook Discord en erreur", t);
            return null;
        });
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> {
                }
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
