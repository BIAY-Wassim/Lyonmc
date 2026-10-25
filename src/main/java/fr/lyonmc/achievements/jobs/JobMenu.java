package fr.lyonmc.achievements.jobs;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /metiers : niveaux, progression, ce qui rapporte de l'XP, paliers et classements. */
public final class JobMenu {

    private static final int[] JOB_SLOTS = {20, 21, 22, 23, 24};
    private static final int[] GRID = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    private final LyonAchievements plugin;

    public JobMenu(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private JobService jobs() {
        return plugin.jobs();
    }

    private TextColor accent() {
        return plugin.text().accent();
    }

    // ───────────────────────────── Menu principal ─────────────────────────────

    public void open(Player player) {
        Profile p = plugin.profiles().get(player);
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Métiers  ·  " + jobs().totalLevels(p) + " niveaux"));
        holder.inventory(inv);

        inv.setItem(4, Items.item(Material.CRAFTING_TABLE, Component.text("Métiers", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.line("Tu progresses dans 5 métiers en jouant"),
                Items.line("normalement : miner, couper du bois,"),
                Items.line("récolter, chasser et pêcher."),
                Items.EMPTY,
                Component.text("✦ ", accent()).append(Items.kv("Chaque niveau", "des pièces (" + jobs().coinsPerLevel() + " × le niveau)")),
                Component.text("✦ ", accent()).append(Items.kv("Récolte double", "+" + JobService.percent(jobs().bonusPerLevel()) + " par niveau")),
                Component.text("✦ ", accent()).append(Items.kv("Niveaux 10, 25, 50", "titres et cosmétiques")),
                Component.text("✦ ", accent()).append(Items.kv("Monde ressources", "XP × " + fmt(jobs().resourceMultiplier()))),
                Items.EMPTY,
                Items.dark("Les blocs posés par des joueurs ne comptent pas."),
                Items.dark("Rien ne compte au hub ni quand tu es AFK.")), false));

        int i = 0;
        for (JobService.Job job : jobs().all()) {
            if (i >= JOB_SLOTS.length) break;
            int slot = JOB_SLOTS[i++];
            inv.setItem(slot, jobItem(p, job, true));
            holder.action(slot, pl -> openJob(pl, job));
        }

        inv.setItem(38, Items.item(Material.GOLDEN_HELMET, Component.text("Classement des métiers", Text.WHITE).decorate(TextDecoration.BOLD),
                List.of(Items.line("Les meilleurs joueurs de chaque métier"), Items.EMPTY, Items.action("Cliquer pour voir", accent())), false));
        holder.action(38, pl -> openTop(pl, null));
        inv.setItem(40, Items.item(Material.BOOK, Component.text("Comment gagner de l'XP ?", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.line("Clique sur un métier pour voir tout"),
                Items.line("ce qui rapporte de l'XP, et combien."),
                Items.EMPTY,
                Items.line("Une barre en haut de l'écran montre"),
                Items.line("ta progression quand tu gagnes de l'XP."),
                Items.EMPTY,
                Items.dark("Astuce : le monde ressources (/mine)"),
                Items.dark("rapporte plus d'XP au Mineur.")), false));
        inv.setItem(42, Items.item(Material.EXPERIENCE_BOTTLE, Component.text("Ta progression", Text.WHITE).decorate(TextDecoration.BOLD),
                summary(p), false));
        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(45, pl -> plugin.hub().open(pl));
        Items.fill(inv);
        player.openInventory(inv);
    }

    private List<Component> summary(Profile p) {
        List<Component> lore = new ArrayList<>();
        for (JobService.Job job : jobs().all()) {
            int lvl = jobs().level(p, job.id());
            lore.add(job.label().append(Component.text("  niveau " + lvl, Text.WHITE)));
        }
        lore.add(Items.EMPTY);
        lore.add(Items.kv("Total", jobs().totalLevels(p) + " niveaux"));
        return lore;
    }

    private ItemStack jobItem(Profile p, JobService.Job job, boolean clickable) {
        long xp = jobs().xp(p, job.id());
        int lvl = jobs().level(xp);
        long span = jobs().span(xp), into = jobs().into(xp);
        List<Component> lore = new ArrayList<>();
        for (String d : job.description()) lore.add(Items.line(d));
        lore.add(Items.EMPTY);
        lore.add(Items.kv("Niveau", lvl + " / " + jobs().maxLevel()));
        if (span > 0) {
            lore.add(Component.text().append(plugin.text().bar((int) Math.min(Integer.MAX_VALUE, into), (int) Math.min(Integer.MAX_VALUE, span), 20))
                    .append(Component.text("  " + Text.num(into) + " / " + Text.num(span) + " XP", Text.GRAY)).build());
            lore.add(Items.kv("Niveau suivant", "+" + plugin.economy().format(jobs().coinsPerLevel() * (lvl + 1))));
        } else {
            lore.add(Component.text("✔ Niveau maximum atteint", accent()));
        }
        lore.add(Items.kv("Récolte double", JobService.percent(jobs().doubleChance(lvl))));
        Map.Entry<Integer, Bundle> next = jobs().nextMilestone(job, lvl);
        if (next != null) {
            lore.add(Items.EMPTY);
            lore.add(Items.line("Au niveau " + next.getKey() + " :"));
            for (Component c : plugin.rewards().describe(next.getValue())) lore.add(Component.text(" ").append(c));
        }
        if (clickable) {
            lore.add(Items.EMPTY);
            lore.add(Items.action("Cliquer : ce qui rapporte de l'XP", accent()));
        }
        ItemStack it = Items.item(job.icon(), job.label().decorate(TextDecoration.BOLD), lore, lvl >= jobs().maxLevel());
        it.setAmount(Math.max(1, Math.min(64, lvl)));
        return it;
    }

    // ───────────────────────────── Détail d'un métier ─────────────────────────────

    private record Source(ItemStack icon, Component name, int xp, String note) {
    }

    public void openJob(Player player, JobService.Job job) {
        Profile p = plugin.profiles().get(player);
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Métier : " + job.name()));
        holder.inventory(inv);
        inv.setItem(4, jobItem(p, job, false));

        List<Source> sources = sources(job);
        for (int i = 0; i < sources.size() && i < GRID.length; i++) {
            Source s = sources.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("+" + s.xp() + " XP", accent()));
            if (jobs().resourceMultiplier() != 1 && job.id().equals("mineur") && s.note() == null) {
                lore.add(Items.dark("× " + fmt(jobs().resourceMultiplier()) + " dans le monde ressources"));
            }
            if (s.note() != null) lore.add(Items.dark(s.note()));
            inv.setItem(GRID[i], Items.decorate(s.icon(), s.name().color(Text.WHITE), lore, false));
        }

        // Paliers du métier
        int lvl = jobs().level(p, job.id());
        int slot = 47;
        for (Map.Entry<Integer, Bundle> e : job.milestones().entrySet()) {
            if (slot > 51) break;
            boolean done = lvl >= e.getKey();
            List<Component> lore = new ArrayList<>();
            for (Component c : plugin.rewards().describe(e.getValue())) lore.add(Component.text(" ").append(c));
            lore.add(Items.EMPTY);
            lore.add(done ? Component.text("✔ Obtenu", accent()) : Items.dark("Il te manque " + (e.getKey() - lvl) + " niveau(x)"));
            inv.setItem(slot, Items.item(done ? Material.LIME_DYE : Material.GRAY_DYE,
                    Component.text("Niveau " + e.getKey(), done ? accent() : Text.WHITE).decorate(TextDecoration.BOLD), lore, done));
            slot += 2;
        }
        inv.setItem(45, Items.item(Material.ARROW, Component.text("Tous les métiers", Text.WHITE), List.of(), false));
        holder.action(45, this::open);
        inv.setItem(53, Items.item(Material.GOLDEN_HELMET, Component.text("Classement : " + job.name(), Text.WHITE), List.of(
                Items.action("Cliquer pour voir", accent())), false));
        holder.action(53, pl -> openTop(pl, job));
        Items.fill(inv);
        player.openInventory(inv);
    }

    private List<Source> sources(JobService.Job job) {
        List<Source> out = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : job.blocks().entrySet()) {
            Material m = e.getKey();
            boolean crop = JobListener.isCrop(m);
            out.add(new Source(new ItemStack(icon(m)), Component.translatable(m.translationKey()), e.getValue(), crop ? "Seulement mûr" : null));
        }
        for (Map.Entry<Material, Integer> e : job.harvests().entrySet()) {
            Material m = e.getKey();
            out.add(new Source(new ItemStack(icon(m)), Component.translatable(m.translationKey()), e.getValue(), "Récolte au clic droit"));
        }
        if (job.breed() > 0) {
            out.add(new Source(new ItemStack(Material.WHEAT), Component.text("Faire naître un animal"), job.breed(), "Nourris deux animaux"));
        }
        for (Map.Entry<String, Integer> e : job.mobs().entrySet()) {
            String k = e.getKey();
            if (k.equals("HOSTILE")) {
                out.add(new Source(new ItemStack(Material.ZOMBIE_HEAD), Component.text("Autres monstres"), e.getValue(), null));
            } else if (k.equals("PASSIVE")) {
                out.add(new Source(new ItemStack(Material.LEATHER), Component.text("Animaux"), e.getValue(), null));
            } else {
                EntityType type;
                try {
                    type = EntityType.valueOf(k);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                Material egg = Material.matchMaterial(k + "_SPAWN_EGG");
                if (egg == null) egg = switch (type) {
                    case WITHER -> Material.WITHER_SKELETON_SKULL;
                    case ENDER_DRAGON -> Material.DRAGON_HEAD;
                    default -> Material.BONE;
                };
                out.add(new Source(new ItemStack(egg), Component.translatable(type.translationKey()), e.getValue(), null));
            }
        }
        for (Map.Entry<String, Integer> e : job.fish().entrySet()) {
            switch (e.getKey()) {
                case "POISSON" -> out.add(new Source(new ItemStack(Material.COD), Component.text("Poisson"), e.getValue(),
                        "Morue, saumon, poisson-globe..."));
                case "TRESOR" -> out.add(new Source(new ItemStack(Material.ENCHANTED_BOOK), Component.text("Trésor"), e.getValue(),
                        "Livre enchanté, selle, arc..."));
                case "DECHET" -> out.add(new Source(new ItemStack(Material.STICK), Component.text("Déchet"), e.getValue(),
                        "Bâton, cuir, bol..."));
                default -> {
                }
            }
        }
        out.sort(Comparator.comparingInt(Source::xp).reversed());
        return out;
    }

    /** Icône d'un bloc qui n'est pas un objet (cultures...). */
    private static Material icon(Material m) {
        return switch (m) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case COCOA -> Material.COCOA_BEANS;
            case PITCHER_CROP -> Material.PITCHER_PLANT;
            case SWEET_BERRY_BUSH -> Material.SWEET_BERRIES;
            case CAVE_VINES, CAVE_VINES_PLANT -> Material.GLOW_BERRIES;
            default -> m.isItem() ? m : Material.PAPER;
        };
    }

    // ───────────────────────────── Classements ─────────────────────────────

    public void openTop(Player player, JobService.Job job) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(job == null ? "Classement des métiers" : "Classement : " + job.name()));
        holder.inventory(inv);
        List<Profile> top = jobs().top(job);
        int[] slots = {13, 21, 22, 23, 29, 30, 31, 32, 33, 37, 38, 39, 40, 41, 42, 43};
        for (int i = 0; i < top.size() && i < slots.length; i++) {
            Profile p = top.get(i);
            List<Component> lore = new ArrayList<>();
            if (job == null) {
                for (JobService.Job j : jobs().all()) lore.add(j.label().append(Component.text("  niveau " + jobs().level(p, j.id()), Text.WHITE)));
                lore.add(Items.EMPTY);
                lore.add(Items.kv("Total", jobs().totalLevels(p) + " niveaux"));
            } else {
                lore.add(Items.kv("Niveau", String.valueOf(jobs().level(p, job.id()))));
                lore.add(Items.kv("XP totale", Text.num(jobs().xp(p, job.id()))));
            }
            ItemStack head = plugin.heads().head(p.uuid(), p.name);
            inv.setItem(slots[i], Items.decorate(head, Component.text("#" + (i + 1) + "  ", accent())
                    .append(plugin.rewards().coloredName(p.uuid(), p.name, Text.WHITE)).decorate(TextDecoration.BOLD), lore, i == 0));
        }
        if (top.isEmpty()) {
            inv.setItem(22, Items.item(Material.GRAY_DYE, Component.text("Personne n'a encore d'XP ici", Text.GRAY), List.of(), false));
        }
        // Choix du classement
        int s = 0;
        inv.setItem(s, Items.item(Material.CRAFTING_TABLE, Component.text("Total des niveaux", job == null ? accent() : Text.WHITE), List.of(), job == null));
        holder.action(s, pl -> openTop(pl, null));
        for (JobService.Job j : jobs().all()) {
            if (++s > 8) break;
            inv.setItem(s, Items.item(j.icon(), j.label(), List.of(), j == job));
            holder.action(s, pl -> openTop(pl, j));
        }
        inv.setItem(45, Items.item(Material.ARROW, Component.text("Tous les métiers", Text.WHITE), List.of(), false));
        holder.action(45, this::open);
        Items.fill(inv);
        player.openInventory(inv);
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d).replace('.', ',');
    }

    static String id(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
