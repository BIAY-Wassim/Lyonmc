package fr.lyonmc.achievements.teleport;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.core.StoredLocation;
import fr.lyonmc.achievements.menu.ConfirmMenu;
import fr.lyonmc.achievements.menu.MenuHolder;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;

import java.util.*;
import java.util.regex.Pattern;

/** /sethome /home /delhome /homes /spawn /setspawn /back /tpa /tpahere /tpaccept /tpdeny /rtp */
public final class TeleportCommands implements TabExecutor, Listener {

    private static final Pattern HOME_NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    private record Request(UUID from, boolean here, long expires) {
    }

    private final LyonAchievements plugin;
    private final Map<UUID, List<Request>> requests = new HashMap<>();
    private final Map<UUID, Long> rtpCooldown = new HashMap<>();

    public TeleportCommands(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player player)) {
            plugin.msg(sender, "<gray>Commande réservée aux joueurs.");
            return true;
        }
        if (!plugin.module("teleport")) {
            plugin.msg(sender, "<gray>Les téléportations sont désactivées sur ce serveur.");
            return true;
        }
        switch (name) {
            case "sethome" -> setHome(player, args.length > 0 ? args[0] : "maison");
            case "home" -> home(player, args.length > 0 ? args[0] : null);
            case "delhome" -> {
                if (args.length == 0) plugin.msg(player, "<gray>Usage : <white>/delhome <nom>");
                else delHome(player, args[0]);
            }
            case "homes" -> openHomes(player);
            case "spawn" -> {
                Location spawn = spawn();
                plugin.teleporter().teleport(player, spawn, "le spawn");
            }
            case "setspawn" -> {
                if (!player.hasPermission("lyonmc.admin")) {
                    plugin.msg(player, "<gray>Tu n'as pas la permission.");
                    return true;
                }
                Location l = player.getLocation();
                plugin.configs().state().set("spawn", StoredLocation.of(l).serialize());
                plugin.configs().saveState();
                l.getWorld().setSpawnLocation(l);
                plugin.msg(player, "<gray>Spawn défini ici.");
            }
            case "back" -> {
                Location b = plugin.teleporter().back(player.getUniqueId());
                if (b == null) plugin.msg(player, "<gray>Aucune position précédente.");
                else plugin.teleporter().teleport(player, b, "ta position précédente");
            }
            case "tpa", "tpahere" -> {
                Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : null;
                if (target == null || target.equals(player)) {
                    plugin.msg(player, "<gray>Usage : <white>/" + name + " <joueur connecté>");
                    return true;
                }
                request(player, target, name.equals("tpahere"));
            }
            case "tpaccept" -> accept(player, args.length > 0 ? args[0] : null);
            case "tpdeny" -> deny(player);
            case "rtp" -> rtp(player);
            default -> {
            }
        }
        return true;
    }

    // ───────────────────────────── Spawn ─────────────────────────────

    public Location spawn() {
        StoredLocation s = StoredLocation.parse(plugin.configs().state().getString("spawn"));
        Location l = s == null ? null : s.toLocation();
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.module("teleport") || event.isBedSpawn() || event.isAnchorSpawn()) return;
        if (plugin.configs().state().getString("spawn") != null) event.setRespawnLocation(spawn());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFirstJoin(PlayerJoinEvent event) {
        if (!plugin.module("teleport") || event.getPlayer().hasPlayedBefore()) return;
        if (plugin.configs().state().getString("spawn") != null) {
            Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().teleportAsync(spawn()));
        }
    }

    // ───────────────────────────── Homes ─────────────────────────────

    private void setHome(Player player, String name) {
        if (!HOME_NAME.matcher(name).matches()) {
            plugin.msg(player, "<gray>Nom invalide (lettres, chiffres, _ et -, 16 caractères max).");
            return;
        }
        Profile p = plugin.profiles().get(player);
        int limit = plugin.rewards().homesLimit(p);
        String key = name.toLowerCase(Locale.ROOT);
        if (!p.homes.containsKey(key) && p.homes.size() >= limit) {
            plugin.msg(player, "<gray>Tu as atteint ta limite de <white>" + limit + "</white> homes. <dark_gray>"
                    + "(paliers /parcours ou /boutique pour en avoir plus)");
            return;
        }
        p.homes.put(key, StoredLocation.of(player.getLocation()));
        p.dirty();
        plugin.msg(player, "<gray>Home <white>" + key + "</white> défini <dark_gray>(" + p.homes.size() + "/" + limit + ")");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.4f);
    }

    private void home(Player player, String name) {
        Profile p = plugin.profiles().get(player);
        if (p.homes.isEmpty()) {
            plugin.msg(player, "<gray>Tu n'as aucun home. Utilise <white>/sethome</white> pour en créer un.");
            return;
        }
        if (name == null) {
            if (p.homes.size() == 1) name = p.homes.keySet().iterator().next();
            else {
                openHomes(player);
                return;
            }
        }
        StoredLocation l = p.homes.get(name.toLowerCase(Locale.ROOT));
        if (l == null || l.toLocation() == null) {
            plugin.msg(player, "<gray>Home introuvable.");
            return;
        }
        plugin.teleporter().teleport(player, l.toLocation(), "home " + name.toLowerCase(Locale.ROOT));
    }

    private void delHome(Player player, String name) {
        Profile p = plugin.profiles().get(player);
        if (p.homes.remove(name.toLowerCase(Locale.ROOT)) == null) {
            plugin.msg(player, "<gray>Home introuvable.");
            return;
        }
        p.dirty();
        plugin.msg(player, "<gray>Home <white>" + name.toLowerCase(Locale.ROOT) + "</white> supprimé.");
    }

    public void openHomes(Player player) {
        Profile p = plugin.profiles().get(player);
        int limit = plugin.rewards().homesLimit(p);
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Homes  ·  " + p.homes.size() + " / " + limit));
        holder.inventory(inv);
        inv.setItem(4, Items.item(Material.RED_BED, Component.text("Tes homes", Text.WHITE).decorate(TextDecoration.BOLD), List.of(
                Items.kv("Emplacements", p.homes.size() + " / " + limit),
                Items.EMPTY,
                Items.dark("/sethome <nom> pour en créer un"),
                Items.dark("Clic gauche : s'y téléporter"),
                Items.dark("Clic droit : supprimer"),
                Items.EMPTY,
                Items.dark("Plus d'emplacements avec les paliers"),
                Items.dark("(/parcours) et la boutique.")), false));
        int[] grid = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (Map.Entry<String, StoredLocation> e : p.homes.entrySet()) {
            if (i >= grid.length) break;
            String name = e.getKey();
            StoredLocation l = e.getValue();
            Material icon = switch (worldEnv(l.world())) {
                case NETHER -> Material.CRIMSON_NYLIUM;
                case THE_END -> Material.END_STONE;
                default -> Material.GRASS_BLOCK;
            };
            inv.setItem(grid[i], Items.item(icon, Component.text(name, Text.WHITE), List.of(
                    Items.line(l.pretty()),
                    Items.EMPTY,
                    Items.action("Clic gauche : se téléporter", plugin.text().accent()),
                    Items.dark("Clic droit : supprimer")), false));
            holder.click(grid[i], (pl, type) -> {
                if (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) {
                    ConfirmMenu.open(pl, "Supprimer " + name + " ?", null, List.of(Items.line("Le home " + name + " sera supprimé.")),
                            pp -> {
                                delHome(pp, name);
                                openHomes(pp);
                            }, this::openHomes);
                } else {
                    pl.closeInventory();
                    home(pl, name);
                }
            });
            i++;
        }
        for (; i < Math.min(grid.length, limit); i++) {
            inv.setItem(grid[i], Items.item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text("Emplacement libre", Text.GRAY),
                    List.of(Items.dark("/sethome <nom>")), false));
        }
        inv.setItem(45, Items.item(Material.ARROW, Component.text("Menu principal", Text.WHITE), List.of(), false));
        holder.action(45, pl -> plugin.hub().open(pl));
        inv.setItem(48, Items.item(Material.COMPASS, Component.text("Spawn", Text.WHITE), List.of(Items.dark("/spawn")), false));
        holder.action(48, pl -> {
            pl.closeInventory();
            plugin.teleporter().teleport(pl, spawn(), "le spawn");
        });
        inv.setItem(50, Items.item(Material.ENDER_PEARL, Component.text("Téléportation aléatoire", Text.WHITE),
                List.of(Items.dark("/rtp · un endroit au hasard dans le monde")), false));
        holder.action(50, pl -> {
            pl.closeInventory();
            rtp(pl);
        });
        Items.fill(inv);
        player.openInventory(inv);
    }

    private static World.Environment worldEnv(String name) {
        World w = Bukkit.getWorld(name);
        return w == null ? World.Environment.NORMAL : w.getEnvironment();
    }

    // ───────────────────────────── TPA ─────────────────────────────

    private void request(Player from, Player target, boolean here) {
        List<Request> list = requests.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>());
        list.removeIf(r -> r.from().equals(from.getUniqueId()) || r.expires() < System.currentTimeMillis());
        list.add(new Request(from.getUniqueId(), here, System.currentTimeMillis() + 60_000));
        plugin.msg(from, "<gray>Demande envoyée à <white>" + target.getName() + "</white>. <dark_gray>(expire dans 60 s)");
        String what = here ? "souhaite que tu le rejoignes" : "souhaite se téléporter à toi";
        target.sendMessage(plugin.prefixed("<white>" + from.getName() + "</white> <gray>" + what + ".  "
                + "<click:run_command:'/tpaccept " + from.getName() + "'><hover:show_text:'<gray>Accepter'><green>[Accepter]</green></hover></click> "
                + "<click:run_command:'/tpdeny'><hover:show_text:'<gray>Refuser'><red>[Refuser]</red></hover></click>"));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
    }

    private void accept(Player target, String fromName) {
        List<Request> list = requests.getOrDefault(target.getUniqueId(), new ArrayList<>());
        list.removeIf(r -> r.expires() < System.currentTimeMillis());
        Request req = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            Request r = list.get(i);
            Player p = Bukkit.getPlayer(r.from());
            if (p != null && (fromName == null || p.getName().equalsIgnoreCase(fromName))) {
                req = r;
                break;
            }
        }
        if (req == null) {
            plugin.msg(target, "<gray>Aucune demande de téléportation en attente.");
            return;
        }
        list.remove(req);
        Player from = Bukkit.getPlayer(req.from());
        if (from == null) return;
        plugin.msg(target, "<gray>Demande de <white>" + from.getName() + "</white> acceptée.");
        if (req.here()) {
            plugin.teleporter().teleport(target, from.getLocation(), from.getName());
        } else {
            plugin.msg(from, "<white>" + target.getName() + "</white> <gray>a accepté ta demande.");
            plugin.teleporter().teleport(from, target.getLocation(), target.getName());
        }
    }

    private void deny(Player target) {
        List<Request> list = requests.remove(target.getUniqueId());
        if (list == null || list.isEmpty()) {
            plugin.msg(target, "<gray>Aucune demande en attente.");
            return;
        }
        for (Request r : list) {
            Player from = Bukkit.getPlayer(r.from());
            if (from != null) plugin.msg(from, "<white>" + target.getName() + "</white> <gray>a refusé ta demande.");
        }
        plugin.msg(target, "<gray>Demande refusée.");
    }

    // ───────────────────────────── RTP ─────────────────────────────

    public void rtp(Player player) {
        rtp(player, Bukkit.getWorld(plugin.getConfig().getString("teleport.rtp.world", "world")),
                plugin.getConfig().getInt("teleport.rtp.min-radius", 500),
                plugin.getConfig().getInt("teleport.rtp.max-radius", 5000), true);
    }

    public void rtp(Player player, World world, int min, int max, boolean cooldown) {
        if (world == null) world = Bukkit.getWorlds().get(0);
        long cd = plugin.getConfig().getLong("teleport.rtp.cooldown-seconds", 600) * 1000L;
        Long last = rtpCooldown.get(player.getUniqueId());
        if (cooldown && last != null && System.currentTimeMillis() - last < cd && !player.hasPermission("lyonmc.admin")) {
            long left = (cd - (System.currentTimeMillis() - last)) / 1000;
            plugin.msg(player, "<gray>Prochaine téléportation aléatoire dans <white>" + Text.duration(Math.max(60, left)) + "</white>.");
            return;
        }
        World w = world;
        plugin.msg(player, "<gray>Recherche d'un endroit sûr...");
        RandomTeleport.find(plugin, w, min, max, loc -> {
            if (!player.isOnline()) return;
            if (loc == null) {
                plugin.msg(player, "<gray>Aucun endroit sûr trouvé, réessaie.");
                return;
            }
            if (cooldown) rtpCooldown.put(player.getUniqueId(), System.currentTimeMillis());
            plugin.teleporter().teleport(player, loc, "un endroit aléatoire");
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && sender instanceof Player p) {
            if (name.equals("home") || name.equals("delhome")) out.addAll(plugin.profiles().get(p).homes.keySet());
            else if (name.equals("tpa") || name.equals("tpahere") || name.equals("tpaccept")) {
                for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) out.add(o.getName());
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
