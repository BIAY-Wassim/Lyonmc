package fr.lyonmc.hub.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Classements lus dans classements.json (écrit par LyonAchievements toutes les minutes). */
public final class Rankings {

    /** Un classement du podium : clé dans le JSON, champ du score, nom affiché, unité. */
    public record Board(String id, String key, String field, String title, String unit) {
    }

    public record Entry(String name, UUID uuid, long value) {
    }

    public static final List<Board> BOARDS = List.of(
            new Board("succes", "achievements", "count", "Succès", "succès"),
            new Board("saison", "season", "points", "Saison", "pts"),
            new Board("fortune", "richest", "coins", "Fortune", "pièces"),
            new Board("missions", "missions", "missions", "Missions", "missions"));

    private volatile Map<String, List<Entry>> data = Map.of();

    public static Board board(String id) {
        for (Board b : BOARDS) if (b.id().equalsIgnoreCase(id)) return b;
        return null;
    }

    /** Lecture du fichier (à appeler hors du thread principal). */
    public boolean read(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            Map<String, List<Entry>> out = new HashMap<>();
            for (Board b : BOARDS) {
                List<Entry> list = new ArrayList<>();
                JsonElement arr = root.get(b.key());
                if (arr instanceof JsonArray a) {
                    for (JsonElement e : a) {
                        if (!(e instanceof JsonObject o) || !o.has("name")) continue;
                        UUID uuid = null;
                        try {
                            if (o.has("uuid")) uuid = UUID.fromString(o.get("uuid").getAsString());
                        } catch (IllegalArgumentException ignored) {
                        }
                        long v = o.has(b.field()) ? o.get(b.field()).getAsLong() : 0;
                        list.add(new Entry(o.get("name").getAsString(), uuid, v));
                    }
                }
                out.put(b.id(), list);
            }
            data = out;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Joueur à la place donnée (1, 2, 3...) ou null. */
    public Entry at(String board, int rank) {
        List<Entry> l = data.getOrDefault(board, List.of());
        return rank >= 1 && rank <= l.size() ? l.get(rank - 1) : null;
    }
}
