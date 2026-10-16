package fr.lyonmc.achievements.data;

import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementRequirement;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Liste des succès "comptabilisés" : tous les advancements visibles
 * (avec affichage), hors recettes.
 */
public final class AdvancementRegistry {

    public static final String OTHER = "other";

    public record Category(String id, String name, TextColor color) {
        public Component displayName() {
            return Component.text(name, color);
        }
    }

    public record Entry(Advancement advancement, String key, String category, AdvancementDisplay.Frame frame,
                        Component title, Component description, boolean hidden, ItemStack icon,
                        String parentKey, List<List<String>> requirements) {
    }

    private final JavaPlugin plugin;
    private final LinkedHashMap<String, Category> categories = new LinkedHashMap<>();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Integer> totalPerCategory = new HashMap<>();
    private final EnumMap<AdvancementDisplay.Frame, Integer> totalPerFrame = new EnumMap<>(AdvancementDisplay.Frame.class);

    public AdvancementRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        categories.clear();
        entries.clear();
        totalPerCategory.clear();
        totalPerFrame.clear();

        ConfigurationSection cats = plugin.getConfig().getConfigurationSection("categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                String name = cats.getString(id + ".name", id);
                TextColor color = TextColor.fromHexString(cats.getString(id + ".color", "#ffffff"));
                categories.put(id, new Category(id, name, color == null ? NamedTextColor.WHITE : color));
            }
        }
        categories.putIfAbsent(OTHER, new Category(OTHER, "Autres", NamedTextColor.GRAY));

        boolean excludeRoots = plugin.getConfig().getBoolean("settings.exclude-root-advancements", false);
        boolean includeCustom = plugin.getConfig().getBoolean("settings.include-custom-advancements", true);

        List<Entry> list = new ArrayList<>();
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            String path = adv.getKey().getKey();
            String namespace = adv.getKey().getNamespace();

            if (path.startsWith("recipes/")) continue;
            AdvancementDisplay display = adv.getDisplay();
            if (display == null) continue;
            if (excludeRoots && (path.endsWith("/root") || path.equals("root"))) continue;
            if (!includeCustom && !namespace.equals("minecraft")) continue;

            String cat = OTHER;
            int slash = path.indexOf('/');
            if (namespace.equals("minecraft") && slash > 0) {
                String prefix = path.substring(0, slash);
                if (categories.containsKey(prefix)) cat = prefix;
            }

            Advancement parent = adv.getParent();
            List<List<String>> reqs = new ArrayList<>();
            for (AdvancementRequirement r : adv.getRequirements().getRequirements()) {
                reqs.add(List.copyOf(r.getRequiredCriteria()));
            }
            list.add(new Entry(adv, adv.getKey().toString(), cat, display.frame(),
                    display.title(), display.description(), display.isHidden(), display.icon().clone(),
                    parent == null ? null : parent.getKey().toString(), List.copyOf(reqs)));
        }

        // Tri : ordre des catégories du config, puis clé
        List<String> order = new ArrayList<>(categories.keySet());
        list.sort(Comparator.<Entry>comparingInt(e -> order.indexOf(e.category())).thenComparing(Entry::key));

        // Ordre de l'arbre des progrès : racine, puis ses enfants, etc.
        Set<String> keys = new HashSet<>();
        for (Entry e : list) keys.add(e.key());
        Map<String, List<Entry>> children = new HashMap<>();
        List<Entry> roots = new ArrayList<>();
        for (Entry e : list) {
            if (e.parentKey() == null || !keys.contains(e.parentKey())) roots.add(e);
            else children.computeIfAbsent(e.parentKey(), k -> new ArrayList<>()).add(e);
        }
        List<Entry> ordered = new ArrayList<>(list.size());
        Deque<Entry> stack = new ArrayDeque<>();
        for (Entry root : roots) {
            stack.push(root);
            while (!stack.isEmpty()) {
                Entry cur = stack.pop();
                ordered.add(cur);
                List<Entry> kids = children.getOrDefault(cur.key(), List.of());
                for (int i = kids.size() - 1; i >= 0; i--) stack.push(kids.get(i));
            }
        }

        for (Entry e : ordered) {
            entries.put(e.key(), e);
            totalPerCategory.merge(e.category(), 1, Integer::sum);
            totalPerFrame.merge(e.frame(), 1, Integer::sum);
        }
    }

    public boolean isCounted(String key) {
        return entries.containsKey(key);
    }

    public Entry get(String key) {
        return entries.get(key);
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    public int total() {
        return entries.size();
    }

    public int total(String category) {
        return totalPerCategory.getOrDefault(category, 0);
    }

    public int total(AdvancementDisplay.Frame frame) {
        return totalPerFrame.getOrDefault(frame, 0);
    }

    public Collection<Category> categories() {
        return categories.values();
    }

    /** Catégories qui contiennent au moins un succès */
    public List<Category> usedCategories() {
        List<Category> out = new ArrayList<>();
        for (Category c : categories.values()) {
            if (total(c.id()) > 0) out.add(c);
        }
        return out;
    }

    public Category category(String id) {
        return categories.getOrDefault(id, categories.get(OTHER));
    }

    /** Icône du succès racine d'une catégorie (ex : minecraft:nether/root) */
    public ItemStack rootIcon(String category) {
        Entry root = entries.get("minecraft:" + category + "/root");
        if (root != null) return root.icon().clone();
        for (Advancement adv : (Iterable<Advancement>) Bukkit::advancementIterator) {
            if (adv.getKey().toString().equals("minecraft:" + category + "/root") && adv.getDisplay() != null) {
                return adv.getDisplay().icon().clone();
            }
        }
        return new ItemStack(org.bukkit.Material.BOOK);
    }

    public static TextColor frameColor(AdvancementDisplay.Frame frame) {
        return switch (frame) {
            case CHALLENGE -> TextColor.color(0xD9C08C);
            case GOAL -> TextColor.color(0xE6E6E6);
            default -> TextColor.color(0xE6E6E6);
        };
    }

    public static String frameName(AdvancementDisplay.Frame frame) {
        return switch (frame) {
            case CHALLENGE -> "Défi";
            case GOAL -> "Objectif";
            default -> "Progrès";
        };
    }
}
