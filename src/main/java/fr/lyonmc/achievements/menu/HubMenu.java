package fr.lyonmc.achievements.menu;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.team.Team;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** /menu : point d'entrée vers toutes les fonctionnalités. */
public final class HubMenu {

    private final LyonAchievements plugin;

    public HubMenu(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Profile p = plugin.profiles().get(player);
        plugin.missions().ensure(p);
        PlayerStats s = plugin.stats().refreshOnline(player);
        TextColor accent = plugin.text().accent();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("LyonMC"));
        holder.inventory(inv);

        // Profil
        int rank = plugin.stats().rank(player.getUniqueId());
        int seasonRank = plugin.seasons().rank(player.getUniqueId());
        Team team = plugin.teams().teamOf(player.getUniqueId());
        List<Component> lore = new ArrayList<>();
        Component title = plugin.rewards().titleRaw(p);
        if (!title.equals(Component.empty())) lore.add(Component.text("Titre  ", Text.GRAY).append(title));
        lore.add(Items.kv("Rang", rank == 0 ? "non classé" : "#" + rank + " sur " + plugin.stats().ranking().size()));
        lore.add(Component.text("★ ", accent).append(Items.kv("Succès", s.count() + " / " + plugin.registry().total())));
        lore.add(plugin.text().bar(s.count(), plugin.registry().total(), 30));
        lore.add(Component.text("⛁ ", accent).append(Items.kv("Pièces", Text.num(p.coins))));
        lore.add(Component.text("✦ ", accent).append(Items.kv("Saison", Text.num(plugin.seasons().points(p)) + " pts"
                + (seasonRank > 0 ? "  (#" + seasonRank + ")" : ""))));
        lore.add(Component.text("✔ ", accent).append(Items.kv("Série de missions", plugin.missions().activeStreak(p) + " j")));
        if (team != null) lore.add(Component.text("Équipe  ", Text.GRAY).append(plugin.teams().tagComponent(team))
                .append(Component.text(team.name, Text.WHITE)));
        ItemStack head = plugin.heads().head(player.getUniqueId(), player.getName());
        inv.setItem(4, Items.decorate(head, plugin.rewards().coloredName(player.getUniqueId(), player.getName(), Text.WHITE)
                .decorate(TextDecoration.BOLD), lore, false));

        int done = 0;
        for (Profile.MissionSlot m : p.daily) if (m.done) done++;
        boolean daily = plugin.daily().canClaim(p);

        button(holder, inv, 19, Material.WRITABLE_BOOK, "Missions", List.of(
                Items.line("Missions du jour et de la semaine"),
                Items.kv("Aujourd'hui", done + " / " + p.daily.size()),
                Items.kv("Série", plugin.missions().activeStreak(p) + " jour(s)")), done < p.daily.size(),
                pl -> plugin.missionMenu().open(pl));
        button(holder, inv, 20, Material.KNOWLEDGE_BOOK, "Succès", List.of(
                Items.line("Ta progression et ce qu'il te reste"),
                Items.kv("Obtenus", s.count() + " / " + plugin.registry().total())), false,
                pl -> plugin.achievementsMenu().open(pl));
        button(holder, inv, 21, Material.GOLD_INGOT, "Parcours de récompenses", List.of(
                Items.line("Les paliers à débloquer"),
                Items.kv("Débloqués", p.claimed.size() + " / " + plugin.rewards().paliers().size())), false,
                pl -> plugin.rewardMenus().openPath(pl, 1));
        button(holder, inv, 22, daily ? Material.CHEST : Material.BARREL, "Récompense du jour", List.of(
                Items.line(daily ? "Disponible !" : "Déjà récupérée aujourd'hui"),
                Items.kv("Série", plugin.daily().currentStreak(p) + " jour(s)")), daily,
                pl -> plugin.rewardMenus().openDaily(pl));
        button(holder, inv, 23, Material.NETHER_STAR, plugin.seasons().name(), List.of(
                Items.line("Classement du mois"),
                Items.kv("Fin dans", plugin.seasons().timeLeft()),
                plugin.seasons().isWeekend() ? Component.text("✦ Week-end : points bonus !", accent) : Items.EMPTY),
                plugin.seasons().isWeekend(), pl -> plugin.seasonMenu().open(pl));
        button(holder, inv, 24, Material.DIAMOND, "Classement des succès", List.of(
                Items.line("Le podium avec les têtes des joueurs")), false,
                pl -> plugin.achievementsMenu().openTop(pl));
        button(holder, inv, 25, Material.WHITE_BANNER, "Équipe", List.of(
                Items.line(team == null ? "Crée ou rejoins une équipe" : "Ton équipe : " + team.name)), false,
                pl -> plugin.teamCommand().openMenu(pl));

        button(holder, inv, 28, Material.NAME_TAG, "Titres", List.of(
                Items.line("Choisis le titre devant ton pseudo"),
                Component.text("Grade : ", Text.GRAY).append(plugin.grades().display(p.getUniqueId())).append(Component.text(" (automatique)", Text.DARK)).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Items.kv("Débloqués", plugin.rewards().unlockedTitles(p) + " / " + plugin.rewards().titles().size())), false,
                pl -> plugin.rewardMenus().openTitles(pl, 1));
        button(holder, inv, 29, Material.BLAZE_POWDER, "Cosmétiques", List.of(
                Items.line("Couleur du pseudo et traînées")), false,
                pl -> plugin.rewardMenus().openCosmetics(pl));
        button(holder, inv, 30, Material.EMERALD, "Boutique", List.of(
                Items.line("Titres, cosmétiques, homes"),
                Items.kv("Solde", plugin.economy().format(p.coins))), false,
                pl -> plugin.rewardMenus().openShop(pl, 1));
        button(holder, inv, 31, Material.GOLD_BLOCK, "Hôtel des ventes", List.of(
                Items.line("Achète et vends entre joueurs"),
                Items.kv("Objets en vente", String.valueOf(plugin.auctions().active().size()))), false,
                pl -> plugin.auctionCommand().open(pl, 1, false));
        button(holder, inv, 32, Material.RED_BED, "Homes", List.of(
                Items.line("Tes points de téléportation"),
                Items.kv("Emplacements", p.homes.size() + " / " + plugin.rewards().homesLimit(p))), false,
                pl -> plugin.teleportCommands().openHomes(pl));
        button(holder, inv, 33, Material.WITHER_SKELETON_SKULL, "Boss mondial", List.of(
                Items.line(plugin.boss().active() ? "Le boss est en cours de combat !" : "Prochaine apparition :"),
                Items.line(plugin.boss().active() ? "Clique pour rejoindre l'arène" : plugin.boss().nextText())),
                plugin.boss().active(), pl -> {
                    pl.closeInventory();
                    pl.performCommand("boss");
                });
        button(holder, inv, 34, Material.IRON_PICKAXE, "Monde ressources", List.of(
                Items.line("Un monde à miner, réinitialisé"),
                Items.line("chaque mois"),
                Items.dark("/ressources")), false, pl -> {
                    pl.closeInventory();
                    plugin.resources().teleport(pl);
                });

        button(holder, inv, 47, Material.FILLED_MAP, "Dynmap", List.of(Items.line("La carte du serveur en direct"),
                Items.line("sur lyonmc.fr")), false, pl -> {
                    pl.closeInventory();
                    pl.performCommand("carte");
                });
        button(holder, inv, 49, Material.BARRIER, "Fermer", List.of(), false, Player::closeInventory);
        PlayerStats stats = plugin.stats().get(player.getUniqueId());
        boolean sidebar = stats == null || stats.sidebar();
        button(holder, inv, 51, Material.COMPARATOR, "Scoreboard : " + (sidebar ? "affiché" : "masqué"), List.of(
                Items.line("Afficher ou masquer le scoreboard")), false, pl -> {
                    pl.performCommand("succes sidebar");
                    open(pl);
                });
        Items.fill(inv);
        player.openInventory(inv);
    }

    private void button(MenuHolder holder, Inventory inv, int slot, Material icon, String name, List<Component> lore,
                        boolean glint, Consumer<Player> action) {
        List<Component> l = new ArrayList<>(lore);
        l.add(Items.EMPTY);
        l.add(Items.action("Cliquer pour ouvrir", plugin.text().accent()));
        inv.setItem(slot, Items.item(icon, Component.text(name, Text.WHITE).decorate(TextDecoration.BOLD), l, glint));
        holder.action(slot, action);
    }
}
