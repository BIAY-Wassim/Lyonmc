package fr.lyonmc.achievements.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bonus temporaires (heures bonus, vote party...) : multiplient les pièces gagnées en jouant
 * et les points de saison. Plusieurs bonus peuvent coexister : le plus fort compte.
 */
public final class Boosts {

    public record Boost(String reason, double coins, double season, long until) {
        public boolean active() {
            return System.currentTimeMillis() < until;
        }
    }

    private final Map<String, Boost> boosts = new ConcurrentHashMap<>();

    /** Active (ou prolonge) un bonus jusqu'à until (millisecondes). */
    public void set(String reason, double coins, double season, long until) {
        boosts.put(reason, new Boost(reason, Math.max(1, coins), Math.max(1, season), until));
    }

    public void clear(String reason) {
        boosts.remove(reason);
    }

    private Boost best(boolean coins) {
        Boost best = null;
        for (Boost b : boosts.values()) {
            if (!b.active()) continue;
            if (best == null || (coins ? b.coins() > best.coins() : b.season() > best.season())) best = b;
        }
        return best;
    }

    /** Multiplicateur des pièces gagnées en jouant (1 = aucun bonus). */
    public double coins() {
        Boost b = best(true);
        return b == null ? 1 : b.coins();
    }

    /** Multiplicateur des points de saison (1 = aucun bonus). */
    public double season() {
        Boost b = best(false);
        return b == null ? 1 : b.season();
    }

    /** Bonus actif le plus fort (ou null). */
    public Boost current() {
        return best(true);
    }
}
