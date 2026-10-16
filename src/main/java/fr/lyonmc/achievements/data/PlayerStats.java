package fr.lyonmc.achievements.data;

import io.papermc.paper.advancement.AdvancementDisplay;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Données d'un joueur. La source de vérité est {@link #done} (clé -> date d'obtention),
 * les compteurs sont recalculés à partir du registre.
 */
public final class PlayerStats {

    private final UUID uuid;
    private String name;
    private final Map<String, Long> done = new HashMap<>();
    private boolean sidebar = true;

    // Valeurs dérivées
    private int count;
    private final Map<String, Integer> perCategory = new HashMap<>();
    private final EnumMap<AdvancementDisplay.Frame, Integer> perFrame = new EnumMap<>(AdvancementDisplay.Frame.class);
    private String lastKey;
    private long lastTime;

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public void recompute(AdvancementRegistry registry) {
        count = 0;
        perCategory.clear();
        perFrame.clear();
        lastKey = null;
        lastTime = 0L;
        for (Map.Entry<String, Long> e : done.entrySet()) {
            AdvancementRegistry.Entry entry = registry.get(e.getKey());
            if (entry == null) continue;
            count++;
            perCategory.merge(entry.category(), 1, Integer::sum);
            perFrame.merge(entry.frame(), 1, Integer::sum);
            long t = e.getValue() == null ? 0L : e.getValue();
            if (lastKey == null || t > lastTime) {
                lastKey = e.getKey();
                lastTime = t;
            }
        }
    }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public void name(String name) { if (name != null) this.name = name; }
    public Map<String, Long> done() { return done; }
    public boolean hasDone(String key) { return done.containsKey(key); }
    public boolean sidebar() { return sidebar; }
    public void sidebar(boolean v) { this.sidebar = v; }

    public int count() { return count; }
    public int count(String category) { return perCategory.getOrDefault(category, 0); }
    public int count(AdvancementDisplay.Frame frame) { return perFrame.getOrDefault(frame, 0); }
    public int challenges() { return count(AdvancementDisplay.Frame.CHALLENGE); }
    public String lastKey() { return lastKey; }
    public long lastTime() { return lastTime; }
}
