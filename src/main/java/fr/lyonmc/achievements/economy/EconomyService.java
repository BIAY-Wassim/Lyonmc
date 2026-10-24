package fr.lyonmc.achievements.economy;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Monnaie du serveur (pièces). Compatible Vault via {@link VaultBridge}. */
public final class EconomyService {

    private final LyonAchievements plugin;

    public EconomyService(LyonAchievements plugin) {
        this.plugin = plugin;
        plugin.text().addProvider((b, uuid) -> {
            Profile p = uuid == null ? null : plugin.profiles().get(uuid);
            b.resolver(Placeholder.unparsed("coins", Text.num(p == null ? 0 : p.coins)));
            b.resolver(Placeholder.unparsed("currency", plural()));
            b.resolver(Placeholder.unparsed("currency_symbol", symbol()));
        });
    }

    public String singular() {
        return plugin.configs().rewards().getString("currency.singular", "pièce");
    }

    public String plural() {
        return plugin.configs().rewards().getString("currency.plural", "pièces");
    }

    public String symbol() {
        return plugin.configs().rewards().getString("currency.symbol", "⛁");
    }

    public long startingBalance() {
        return plugin.configs().rewards().getLong("currency.starting-balance", 100);
    }

    /** "1 250 ⛁" */
    public String format(long amount) {
        return Text.num(amount) + " " + symbol();
    }

    public long balance(UUID uuid) {
        Profile p = plugin.profiles().get(uuid);
        return p == null ? 0 : p.coins;
    }

    public boolean has(UUID uuid, long amount) {
        return balance(uuid) >= amount;
    }

    /** Ajoute des pièces (comptées comme "gagnées" si earned = true). */
    public long deposit(UUID uuid, String name, long amount, boolean earned) {
        if (amount <= 0) return balance(uuid);
        Profile p = plugin.profiles().getOrCreate(uuid, name);
        p.coins += amount;
        if (earned) p.earned += amount;
        p.dirty();
        Player online = plugin.getServer().getPlayer(uuid);
        if (online != null && earned) plugin.custom().checkEconomy(online, p);
        return p.coins;
    }

    /** Pièces gagnées en jouant (succès, missions, minerais, lots...) : les bonus temporaires s'appliquent. */
    public long deposit(Player player, long amount) {
        if (amount > 0) amount = Math.round(amount * plugin.boosts().coins());
        return deposit(player.getUniqueId(), player.getName(), amount, true);
    }

    /** Retire des pièces si le solde est suffisant. */
    public boolean withdraw(UUID uuid, long amount) {
        if (amount < 0) return false;
        Profile p = plugin.profiles().get(uuid);
        if (p == null || p.coins < amount) return false;
        p.coins -= amount;
        p.dirty();
        return true;
    }

    public void set(UUID uuid, String name, long amount) {
        Profile p = plugin.profiles().getOrCreate(uuid, name);
        p.coins = Math.max(0, amount);
        p.dirty();
    }

    public List<Profile> top() {
        List<Profile> list = new ArrayList<>();
        for (Profile p : plugin.profiles().all()) if (p.name != null && p.coins > 0) list.add(p);
        list.sort(Comparator.comparingLong((Profile p) -> p.coins).reversed());
        return list;
    }

    /** Premier passage d'un joueur : solde de départ. */
    public void ensureAccount(Player player) {
        Profile p = plugin.profiles().get(player);
        if (!p.firstJoinDone) {
            p.coins += startingBalance();
            p.firstJoinDone = true;
            p.dirty();
        }
    }
}
