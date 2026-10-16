# LyonAchievements — lyonmc.fr

Plugin Paper **1.21.8** qui suit les succès (advancements) de chaque joueur en survie.

## Fonctionnalités

- **Scoreboard (sidebar)** personnel : rang, succès obtenus/total, barre de progression, détail par catégorie (Minecraft, Nether, End, Aventure, Agriculture), défis. Sans les nombres rouges à droite.
- **Tablist** : header/footer avec tes stats et le **top 3**, joueurs **triés par nombre de succès**, badge ♛ #1/#2/#3, nombre de succès en doré à droite du pseudo, ping et TPS.
- **Sous le pseudo** : nombre de succès visible au-dessus des joueurs.
- **Annonces** enrichies : succès survolable (description, type, catégorie, **rareté** en % des joueurs), titre + son pour les défis, paliers 25/50/75/90 %, annonce du **nouveau n°1**, message quand quelqu'un a **tout** fini.
- **Classement complet** même pour les joueurs hors-ligne : au démarrage, le plugin lit `world/advancements/*.json`, donc les joueurs qui ont joué avant l'installation sont classés aussi.
- **Commandes** (tout est cliquable) :
  - `/succes` : ton profil (progression, rang, types, catégories, dernier succès, succès le plus rare, temps de jeu)
  - `/succes <joueur>` : profil d'un autre joueur (même hors-ligne)
  - `/succes top [page]` : classement
  - `/succes manquants [catégorie]` : ce qu'il te reste à faire (les secrets restent cachés)
  - `/succes sidebar` : afficher/cacher le scoreboard (mémorisé)
  - `/succes reload`, `/succes import` (admin)
- Alias : `/succès`, `/achievements`, `/ach`, `/adv`
- Tout est configurable dans `config.yml` (MiniMessage, couleurs hex, dégradés, placeholders).

## Permissions

| Permission | Défaut | Rôle |
|---|---|---|
| `lyonmc.succes.use` | tous | `/succes` |
| `lyonmc.succes.others` | tous | voir le profil des autres |
| `lyonmc.succes.admin` | op | reload / import |

## Compiler le .jar

**Option 1 — GitHub (sans rien installer)** : mets ce dossier dans un dépôt GitHub. L'onglet *Actions* compile automatiquement ; télécharge le .jar dans l'artefact « LyonAchievements » du dernier build.

**Option 2 — sur ton PC** : installe Java 21 et Maven, puis dans ce dossier :

```
mvn package
```

Le plugin se trouve dans `target/LyonAchievements-1.0.0.jar`.

**Option 3 — IntelliJ IDEA** : *Open* → choisis le dossier → Maven → *Lifecycle* → `package`.

Ensuite : copie le .jar dans `plugins/` et redémarre le serveur.

## Notes

- Un succès compté = un advancement **visible** (les recettes sont exclues). En vanilla 1.21.8 ça fait environ 120+ succès ; les datapacks sont inclus si `include-custom-advancements: true`.
- Le plugin donne à chaque joueur son propre scoreboard. Si tu utilises déjà un plugin qui gère la tablist ou le scoreboard (TAB, FeatherBoard…), désactive la partie concernée d'un des deux (`sidebar.enabled`, `tablist.enabled`, `below-name.enabled`).
- Les données sont dans `plugins/LyonAchievements/data.yml` (sauvegarde auto toutes les 5 min et à l'arrêt).
