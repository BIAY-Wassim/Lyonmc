package fr.lyonmc.achievements.hdv;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/** Hôtel des ventes : mise en vente, achat, expiration, boîte de récupération. */
public final class AuctionService {

    public static final class Listing {
        public final String id;
        public final UUID seller;
        public final String sellerName;
        public final ItemStack item;
        public final long price;
        public final long created;
        public final long expires;

        Listing(String id, UUID seller, String sellerName, ItemStack item, long price, long created, long expires) {
            this.id = id;
            this.seller = seller;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
            this.created = created;
            this.expires = expires;
        }
    }

    private final LyonAchievements plugin;
    private final File file;
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> mailbox = new HashMap<>();
    private boolean dirty;

    public AuctionService(LyonAchievements plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "hdv.yml");
    }

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("hdv");
    }

    public int maxListings() {
        return cfg() == null ? 5 : cfg().getInt("max-listings", 5);
    }

    public double tax() {
        return cfg() == null ? 5 : cfg().getDouble("tax-percent", 5);
    }

    public long durationMs() {
        return (cfg() == null ? 48 : cfg().getLong("duration-hours", 48)) * 3600_000L;
    }

    // ───────────────────────────── Données ─────────────────────────────

    public void load() {
        listings.clear();
        mailbox.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ls = y.getConfigurationSection("listings");
        if (ls != null) {
            for (String id : ls.getKeys(false)) {
                ConfigurationSection c = ls.getConfigurationSection(id);
                if (c == null) continue;
                try {
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(c.getString("item", "")));
                    listings.put(id, new Listing(id, UUID.fromString(Objects.requireNonNull(c.getString("seller"))),
                            c.getString("seller-name", "?"), item, c.getLong("price"), c.getLong("created"), c.getLong("expires")));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Annonce HDV " + id + " illisible", e);
                }
            }
        }
        ConfigurationSection mb = y.getConfigurationSection("mailbox");
        if (mb != null) {
            for (String u : mb.getKeys(false)) {
                List<ItemStack> items = new ArrayList<>();
                for (String s : mb.getStringList(u)) {
                    try {
                        items.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(s)));
                    } catch (Exception ignored) {
                    }
                }
                try {
                    if (!items.isEmpty()) mailbox.put(UUID.fromString(u), items);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String base = "listings." + l.id;
            y.set(base + ".seller", l.seller.toString());
            y.set(base + ".seller-name", l.sellerName);
            y.set(base + ".item", Base64.getEncoder().encodeToString(l.item.serializeAsBytes()));
            y.set(base + ".price", l.price);
            y.set(base + ".created", l.created);
            y.set(base + ".expires", l.expires);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : mailbox.entrySet()) {
            List<String> items = new ArrayList<>();
            for (ItemStack it : e.getValue()) items.add(Base64.getEncoder().encodeToString(it.serializeAsBytes()));
            y.set("mailbox." + e.getKey(), items);
        }
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible d'enregistrer hdv.yml", e);
        }
    }

    // ───────────────────────────── Accès ─────────────────────────────

    public List<Listing> active() {
        List<Listing> out = new ArrayList<>(listings.values());
        out.sort(Comparator.comparingLong((Listing l) -> l.created).reversed());
        return out;
    }

    public List<Listing> of(UUID seller) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : active()) if (l.seller.equals(seller)) out.add(l);
        return out;
    }

    public Listing get(String id) {
        return listings.get(id);
    }

    public List<ItemStack> mailbox(UUID uuid) {
        return mailbox.getOrDefault(uuid, List.of());
    }

    // ───────────────────────────── Actions ─────────────────────────────

    public void sell(Player player, long price, int amount) {
        if (plugin.spawnHub().inHubWorld(player.getWorld())) {
            plugin.msg(player, "<gray>Au hub, ton inventaire de survie est rangé : mets en vente depuis le monde survie <dark_gray>(/survie)</dark_gray>.");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            plugin.msg(player, "<gray>Prends en main l'objet à vendre.");
            return;
        }
        long min = cfg() == null ? 1 : cfg().getLong("min-price", 1);
        long max = cfg() == null ? 1_000_000 : cfg().getLong("max-price", 1_000_000);
        if (price < min || price > max) {
            plugin.msg(player, "<gray>Le prix doit être entre <white>" + plugin.economy().format(min) + "</white> et <white>"
                    + plugin.economy().format(max) + "</white>.");
            return;
        }
        if (of(player.getUniqueId()).size() >= maxListings() && !player.hasPermission("lyonmc.admin")) {
            plugin.msg(player, "<gray>Tu as déjà <white>" + maxListings() + "</white> objets en vente.");
            return;
        }
        int qty = amount <= 0 ? hand.getAmount() : Math.min(amount, hand.getAmount());
        ItemStack sold = hand.clone();
        sold.setAmount(qty);
        hand.setAmount(hand.getAmount() - qty);
        player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);

        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(1296), 36);
        long now = System.currentTimeMillis();
        listings.put(id, new Listing(id, player.getUniqueId(), player.getName(), sold, price, now, now + durationMs()));
        dirty = true;
        save();
        plugin.msg(player, "<gray>Mis en vente pour <accent>" + plugin.economy().format(price) + "</accent> <dark_gray>(taxe "
                + (int) tax() + " % à la vente)");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.2f);
        if (cfg() == null || cfg().getBoolean("announce", true)) {
            Bukkit.broadcast(plugin.text().parse("<dark_gray>[HDV]</dark_gray> <white>" + player.getName() + "</white> <gray>vend <white>"
                    + qty + "× </white>").append(net.kyori.adventure.text.Component.translatable(sold.getType().translationKey())
                    .color(fr.lyonmc.achievements.util.Text.WHITE)).append(plugin.text().parse(" <gray>pour <accent>"
                    + plugin.economy().format(price) + "</accent> <dark_gray>(/hdv)")));
        }
    }

    public boolean buy(Player buyer, String id) {
        Listing l = listings.get(id);
        if (l == null) {
            plugin.msg(buyer, "<gray>Cet objet n'est plus en vente.");
            return false;
        }
        if (l.seller.equals(buyer.getUniqueId())) {
            plugin.msg(buyer, "<gray>Tu ne peux pas acheter ton propre objet.");
            return false;
        }
        if (!plugin.economy().withdraw(buyer.getUniqueId(), l.price)) {
            plugin.msg(buyer, "<gray>Tu n'as pas assez de " + plugin.economy().plural() + ".");
            return false;
        }
        listings.remove(id);
        dirty = true;
        give(buyer, l.item.clone());
        long gain = Math.max(0, Math.round(l.price * (1 - tax() / 100.0)));
        Profile sellerProfile = plugin.profiles().getOrCreate(l.seller, l.sellerName);
        plugin.economy().deposit(l.seller, l.sellerName, gain, true);
        sellerProfile.hdvSales++;
        sellerProfile.dirty();
        plugin.custom().grantLater(sellerProfile, "commercant");
        save();

        plugin.msg(buyer, "<gray>Achat effectué pour <accent>" + plugin.economy().format(l.price) + "</accent>.");
        buyer.playSound(buyer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        String msg = "<dark_gray>[HDV]</dark_gray> <white>" + buyer.getName() + "</white> <gray>a acheté ton objet : <accent>+"
                + plugin.economy().format(gain) + "</accent>";
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            seller.sendMessage(plugin.text().parse(msg));
            seller.playSound(seller.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        } else {
            sellerProfile.mailbox.add(msg);
            sellerProfile.dirty();
        }
        return true;
    }

    public void cancel(Player player, String id) {
        Listing l = listings.get(id);
        if (l == null || (!l.seller.equals(player.getUniqueId()) && !player.hasPermission("lyonmc.admin"))) return;
        listings.remove(id);
        mailbox.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item.clone());
        dirty = true;
        save();
        plugin.msg(player, "<gray>Vente annulée. L'objet t'attend dans <white>/hdv</white> > Récupérer.");
    }

    public void collect(Player player) {
        List<ItemStack> items = mailbox.remove(player.getUniqueId());
        if (items == null || items.isEmpty()) {
            plugin.msg(player, "<gray>Rien à récupérer.");
            return;
        }
        dirty = true;
        int kept = giveOrStore(player, items);
        save();
        if (kept < items.size()) plugin.msg(player, "<gray>" + (items.size() - kept) + " objet(s) récupéré(s).");
    }

    private void give(Player p, ItemStack item) {
        giveOrStore(p, List.of(item));
    }

    /**
     * Donne des objets à un joueur. Ce qui ne rentre pas dans l'inventaire n'est jamais jeté par terre :
     * il attend dans /hdv > Récupérer (même au hub, où l'on ne peut rien ramasser).
     *
     * @return le nombre d'objets mis de côté dans la boîte de récupération
     */
    public int giveOrStore(Player p, Collection<ItemStack> items) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            left.addAll(p.getInventory().addItem(item.clone()).values());
        }
        if (left.isEmpty()) return 0;
        mailbox.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).addAll(left);
        dirty = true;
        save();
        plugin.msg(p, "<gray>Inventaire plein : <white>" + left.size() + " objet(s)</white> t'attendent dans "
                + "<white><click:run_command:'/hdv'><u>/hdv</u></click></white> > Récupérer.");
        return left.size();
    }

    /** Annonces expirées -> boîte de récupération (appelé chaque minute). */
    public void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Listing> it = listings.values().iterator();
        while (it.hasNext()) {
            Listing l = it.next();
            if (l.expires > now) continue;
            it.remove();
            mailbox.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item);
            Player seller = Bukkit.getPlayer(l.seller);
            if (seller != null) plugin.msg(seller, "<gray>Une de tes ventes a expiré : récupère-la dans <white>/hdv</white>.");
            changed = true;
        }
        if (changed) save();
    }

    public void notifyJoin(Player player) {
        int n = mailbox(player.getUniqueId()).size();
        if (n > 0) plugin.msg(player, "<gray>Tu as <white>" + n + "</white> objet(s) à récupérer à l'hôtel des ventes. <dark_gray>(/hdv)");
    }
}
