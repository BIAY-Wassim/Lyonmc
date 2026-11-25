package fr.lyonmc.achievements.noel;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Calendrier de l'Avent (/calendrier) : une case par jour du 1er au 24 décembre, un gros cadeau le 24,
 * les cases ratées se rattrapent jusqu'au 24, et un titre pour ceux qui ouvrent les 24.
 */
public final class AdventCalendar implements Listener {

    private static final TextColor RED = TextColor.color(0xFF6B6B), GREEN = TextColor.color(0x7BD88F), GOLD = TextColor.color(0xFFD86B);
    /** 4 rangées de 2 × 3 cases, avec une colonne vide au milieu. */
    private static final int[] SLOTS = new int[24];

    static {
        int i = 0;
        for (int r = 1; r <= 4; r++) for (int c : new int[]{1, 2, 3, 5, 6, 7}) SLOTS[i++] = r * 9 + c;
    }

    private final LyonAchievements plugin;
    private final NoelService noel;

    AdventCalendar(LyonAchievements plugin, NoelService noel) {
        this.plugin = plugin;
        this.noel = noel;
    }

    private ConfigurationSection cfg() {
        return noel.cfg().getConfigurationSection("calendrier");
    }

    private boolean catchUp() {
        return noel.cfg().getBoolean("calendrier.rattrapage", true);
    }

    /** Jour du calendrier aujourd'hui : 0 avant le 1er décembre, 1-24 en décembre, 25 après le 24. */
    public int currentDay() {
        LocalDate t = noel.today();
        if (!noel.active(t)) return 0;
        if (t.getMonth() == Month.DECEMBER) return Math.min(25, t.getDayOfMonth());
        return t.getMonth() == Month.JANUARY ? 25 : 0;
    }

    /** Année du calendrier (décembre) : en janvier, c'est celui de l'année d'avant. */
    private int year() {
        LocalDate t = noel.today();
        return t.getMonth() == Month.JANUARY ? t.getYear() - 1 : t.getYear();
    }

    public Set<Integer> opened(UUID u) {
        return new TreeSet<>(noel.data().getIntegerList("calendrier." + year() + "." + u));
    }

    private void setOpened(UUID u, Set<Integer> days) {
        noel.data().set("calendrier." + year() + "." + u, new ArrayList<>(days));
        noel.markDirty();
    }

    public boolean canOpen(UUID u, int day) {
        int today = currentDay();
        if (day < 1 || day > 24 || today < 1 || today > 24 || day > today) return false;
        if (day < today && !catchUp()) return false;
        return !opened(u).contains(day);
    }

    // ───────────────────────────── Menu ─────────────────────────────

    public void open(Player player) {
        if (!plugin.module("noel")) {
            plugin.msg(player, "<gray>Le calendrier de l'Avent n'est pas activé.");
            return;
        }
        int today = currentDay();
        Set<Integer> done = opened(player.getUniqueId());
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Calendrier de l'Avent  ·  " + done.size() + " / 24"));
        holder.inventory(inv);

        List<Component> info = new ArrayList<>();
        info.add(Items.line("Une case à ouvrir chaque jour"));
        info.add(Items.line("du 1er au 24 décembre."));
        if (catchUp()) info.add(Items.line("Une case ratée se rattrape jusqu'au 24."));
        info.add(Items.EMPTY);
        info.add(Component.text("Ouvertes  ", Text.GRAY).append(Component.text(done.size() + " / 24", Text.WHITE)));
        String title = noel.cfg().getString("calendrier.titre-complet", "");
        if (!title.isBlank() && plugin.rewards().title(title) != null) {
            info.add(Component.text("Les 24 cases : titre ", Text.GRAY).append(plugin.text().parse(plugin.rewards().title(title).display())));
        }
        if (today == 0) {
            info.add(Items.EMPTY);
            info.add(Component.text("Ouverture le 1er décembre !", GOLD));
        }
        inv.setItem(4, Items.item(Material.SPRUCE_SAPLING, Component.text("Calendrier de l'Avent", RED).decorate(TextDecoration.BOLD), info, true));

        for (int day = 1; day <= 24; day++) {
            int d = day;
            int slot = SLOTS[day - 1];
            ConfigurationSection gift = cfg() == null ? null : cfg().getConfigurationSection("jours." + day);
            String name = day == 24 ? "Jour 24 · le gros cadeau" : "Jour " + day;
            List<Component> lore = new ArrayList<>();
            ItemStack item;
            if (done.contains(day)) {
                lore.add(Component.text("✔ Ouverte", GREEN));
                lore.add(Items.dark(noel.describe(gift)));
                item = Items.item(Material.LIME_STAINED_GLASS_PANE, Component.text(name, GREEN), lore, false);
            } else if (canOpen(player.getUniqueId(), day)) {
                lore.add(Items.line(day == today ? "C'est la case d'aujourd'hui !" : "Case ratée : tu peux encore l'ouvrir."));
                lore.add(Items.EMPTY);
                lore.add(Items.action("Cliquer pour ouvrir", GOLD));
                item = Items.item(day == 24 ? Material.NETHER_STAR : Material.CHEST, Component.text(name, GOLD).decorate(TextDecoration.BOLD), lore, day == today);
                holder.action(slot, p -> openDay(p, d));
            } else if (today == 0 || day > today) {
                int wait = today == 0 ? -1 : day - today;
                lore.add(Items.dark(wait < 0 ? "S'ouvre le " + day + " décembre" : "S'ouvre dans " + wait + " jour" + (wait > 1 ? "s" : "")));
                item = Items.item(Material.RED_STAINED_GLASS_PANE, Component.text(name, RED), lore, false);
            } else {
                lore.add(Items.dark("Case ratée"));
                item = Items.item(Material.GRAY_STAINED_GLASS_PANE, Component.text(name, Text.GRAY), lore, false);
            }
            item.setAmount(day);
            inv.setItem(slot, item);
        }
        inv.setItem(49, Items.item(Material.BARRIER, Component.text("Fermer", Text.WHITE), List.of(), false));
        holder.action(49, Player::closeInventory);
        Items.fill(inv);
        player.openInventory(inv);
    }

    private void openDay(Player player, int day) {
        if (!canOpen(player.getUniqueId(), day)) {
            open(player);
            return;
        }
        Set<Integer> done = opened(player.getUniqueId());
        done.add(day);
        setOpened(player.getUniqueId(), done);
        noel.saveIfDirty();
        Bundle gift = noel.bundle("calendrier.jours." + day);
        plugin.rewards().grant(player, gift);

        player.playSound(player.getLocation(), day == 24 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
        player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0, 1.2, 0), 40, 0.5, 0.6, 0.5, 0.02);
        plugin.msg(player, "<#ff6b6b>❄</#ff6b6b> <white>Jour " + day + "</white> <gray>du calendrier de l'Avent :");
        for (Component line : plugin.rewards().describe(gift)) player.sendMessage(Component.text("  • ", Text.DARK).append(line));
        plugin.getLogger().info("Calendrier : " + player.getName() + " ouvre la case " + day + " (" + done.size() + "/24).");

        if (done.size() == 24) {
            String title = noel.cfg().getString("calendrier.titre-complet", "");
            if (!title.isBlank() && plugin.rewards().title(title) != null) {
                plugin.rewards().grantUnlocks(plugin.profiles().get(player),
                        new Bundle(0, List.of(title), null, null, 0, 0, 0, List.of(), List.of(), 0));
            }
            Bukkit.broadcast(plugin.prefixed("<white>" + player.getName() + "</white> <gray>a ouvert les <white>24 cases</white> du calendrier de l'Avent !"));
        }
        open(player);
    }

    /** À la connexion : rappel de la case du jour. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        int today = currentDay();
        if (today < 1 || today > 24 || !canOpen(p.getUniqueId(), today)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !canOpen(p.getUniqueId(), today)) return;
            plugin.msg(p, "<#ff6b6b>❄</#ff6b6b> <gray>Ta case du jour t'attend dans le calendrier de l'Avent : "
                    + "<click:run_command:'/calendrier'><hover:show_text:'<gray>Ouvrir le calendrier'><white><u>/calendrier</u></white></hover></click>");
        }, 100L);
    }

    // ───────────────────────────── Site ─────────────────────────────

    /** Les 24 cases : le cadeau d'une case n'est montré qu'à partir de son jour. */
    public List<Map<String, Object>> webData(LocalDate t) {
        int today = currentDay();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        ConfigurationSection all = noel.data().getConfigurationSection("calendrier." + year());
        if (all != null) {
            for (String k : all.getKeys(false)) for (int d : all.getIntegerList(k)) counts.merge(d, 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int day = 1; day <= 24; day++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jour", day);
            boolean shown = today >= day;
            m.put("ouvert", shown);
            if (shown) m.put("cadeau", noel.describe(cfg() == null ? null : cfg().getConfigurationSection("jours." + day)));
            m.put("joueurs", counts.getOrDefault(day, 0));
            out.add(m);
        }
        return out;
    }
}
