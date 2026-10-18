package fr.lyonmc.achievements.economy;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Enregistre les pièces du serveur comme économie Vault, sans dépendance de compilation :
 * l'interface Economy de Vault est implémentée dynamiquement (Proxy).
 * Ainsi les plugins de boutique (QuickShop, ChestShop...) utilisent la même monnaie.
 */
public final class VaultBridge {

    private VaultBridge() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register(LyonAchievements plugin) {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null) return;
        try {
            ClassLoader cl = vault.getClass().getClassLoader();
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy", true, cl);
            Class<?> responseClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, cl);
            Class<?> typeClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse$ResponseType", true, cl);
            Constructor<?> responseCtor = responseClass.getConstructor(double.class, double.class, typeClass, String.class);
            Object success = Enum.valueOf((Class<Enum>) typeClass, "SUCCESS");
            Object failure = Enum.valueOf((Class<Enum>) typeClass, "FAILURE");
            Object notImpl = Enum.valueOf((Class<Enum>) typeClass, "NOT_IMPLEMENTED");

            Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{economyClass}, (self, method, args) -> {
                String name = method.getName();
                switch (name) {
                    case "toString":
                        return "LyonAchievements Economy";
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "equals":
                        return self == args[0];
                    default:
                        break;
                }
                EconomyService eco = plugin.economy();
                switch (name) {
                    case "isEnabled":
                        return plugin.isEnabled() && eco != null;
                    case "getName":
                        return "LyonMC";
                    case "hasBankSupport":
                        return false;
                    case "fractionalDigits":
                        return 0;
                    case "format":
                        return eco == null ? String.valueOf(args[0]) : eco.format(Math.round((Double) args[0]));
                    case "currencyNamePlural":
                        return eco == null ? "pièces" : eco.plural();
                    case "currencyNameSingular":
                        return eco == null ? "pièce" : eco.singular();
                    case "getBanks":
                        return List.of();
                    default:
                        break;
                }
                Class<?> ret = method.getReturnType();
                if (name.startsWith("bank") || name.contains("Bank")) {
                    if (ret == responseClass) return responseCtor.newInstance(0d, 0d, notImpl, "Banques non gérées");
                    return false;
                }
                if (eco == null || args == null || args.length == 0) {
                    if (ret == responseClass) return responseCtor.newInstance(0d, 0d, failure, "Économie indisponible");
                    if (ret == boolean.class) return false;
                    if (ret == double.class) return 0d;
                    return null;
                }
                UUID uuid = resolve(plugin, args[0]);
                String playerName = args[0] instanceof OfflinePlayer op ? op.getName() : String.valueOf(args[0]);
                double amount = args.length >= 2 && args[args.length - 1] instanceof Double d ? d : 0d;
                long whole = (long) Math.floor(amount + 1e-9);
                switch (name) {
                    case "hasAccount":
                        return uuid != null;
                    case "createPlayerAccount":
                        if (uuid == null) return false;
                        plugin.profiles().getOrCreate(uuid, playerName);
                        return true;
                    case "getBalance":
                        return uuid == null ? 0d : (double) eco.balance(uuid);
                    case "has":
                        return uuid != null && eco.has(uuid, (long) Math.ceil(amount - 1e-9));
                    case "withdrawPlayer": {
                        if (uuid == null) return responseCtor.newInstance(amount, 0d, failure, "Compte introuvable");
                        long cost = (long) Math.ceil(amount - 1e-9);
                        if (amount < 0) return responseCtor.newInstance(amount, (double) eco.balance(uuid), failure, "Montant négatif");
                        if (!eco.withdraw(uuid, cost)) {
                            return responseCtor.newInstance(amount, (double) eco.balance(uuid), failure, "Fonds insuffisants");
                        }
                        return responseCtor.newInstance(amount, (double) eco.balance(uuid), success, null);
                    }
                    case "depositPlayer": {
                        if (uuid == null) return responseCtor.newInstance(amount, 0d, failure, "Compte introuvable");
                        if (amount < 0) return responseCtor.newInstance(amount, (double) eco.balance(uuid), failure, "Montant négatif");
                        // argent venant d'un autre joueur via une boutique : pas compté comme "gagné"
                        eco.deposit(uuid, playerName, whole, false);
                        return responseCtor.newInstance(amount, (double) eco.balance(uuid), success, null);
                    }
                    default:
                        if (ret == responseClass) return responseCtor.newInstance(0d, 0d, notImpl, "Non géré");
                        if (ret == boolean.class) return false;
                        if (ret == double.class) return 0d;
                        if (ret == int.class) return 0;
                        return null;
                }
            });

            Bukkit.getServicesManager().register((Class) economyClass, proxy, plugin, ServicePriority.High);
            plugin.getLogger().info("Économie enregistrée auprès de Vault : les plugins de boutique utiliseront les pièces LyonMC.");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Impossible de s'enregistrer auprès de Vault", t);
        }
    }

    private static UUID resolve(LyonAchievements plugin, Object who) {
        if (who instanceof OfflinePlayer op) {
            if (op.getUniqueId() != null) {
                plugin.profiles().getOrCreate(op.getUniqueId(), op.getName());
                return op.getUniqueId();
            }
            return null;
        }
        if (who instanceof String name) {
            Profile p = plugin.profiles().byName(name);
            if (p != null) return p.uuid();
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
            if (op != null) {
                plugin.profiles().getOrCreate(op.getUniqueId(), op.getName());
                return op.getUniqueId();
            }
        }
        return null;
    }
}
