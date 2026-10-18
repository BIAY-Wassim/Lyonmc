# LyonAchievements · lyonmc.fr

Plugin Paper / Purpur **1.21.8** pour la survie LyonMC, par **wassdead**.

## Fonctionnalités

| Module | Ce que ça fait | Commandes |
|---|---|---|
| Menu central | Tout le serveur en un clic | `/menu` |
| Succès | Scoreboard, tablist, nametags, chat, classement, menu des succès restants | `/succes`, `/classement` |
| Succès LyonMC | 22 vrais progrès en plus (onglet « LyonMC » dans l'écran des progrès) | — |
| Missions | 3 missions par jour (facile, moyenne, difficile) + 3 par semaine, série de jours avec bonus | `/missions` |
| Économie | Pièces gagnées avec les succès, missions, boss… Compatible Vault | `/money`, `/pay`, `/baltop` |
| Parcours | 24 paliers de récompenses débloqués automatiquement | `/parcours` |
| Titres | Titre devant le pseudo dans le chat | `/titres` |
| Cosmétiques | Couleur du pseudo, traînées de particules | `/cosmetiques` |
| Boutique | Titres, cosmétiques, emplacements de home | `/boutique` |
| Récompense quotidienne | Cycle de 7 jours avec série | `/recompense` |
| Saisons | Classement mensuel, points x2 le week-end, récompenses du podium | `/saison` |
| Équipes | Tag, chat d'équipe, home d'équipe, classement | `/equipe` |
| Homes & TP | Homes, spawn, tpa, back, téléportation aléatoire | `/sethome`, `/home`, `/homes`, `/spawn`, `/tpa`, `/back`, `/rtp` |
| Hôtel des ventes | Achat/vente entre joueurs, taxe de 5 % | `/hdv`, `/hdv vendre <prix>` |
| Boss mondial | Le Colosse, 2 fois par semaine, récompenses selon les dégâts | `/boss` |
| Monde ressources | Réinitialisé chaque mois | `/ressources` |
| Hologrammes | Classements et podium avec les vraies têtes au spawn | `/lmc holo creer <type>` |
| Discord | Annonces automatiques (défis, n°1, boss, saison) | webhook dans `config.yml` |

## Administration

`/lmc` affiche toutes les commandes admin : `reload`, `holo`, `saison fin`, `missions reset`, `titre`, `import`.
Autres : `/eco give|take|set`, `/setspawn`, `/boss arene|lancer|stop`.

## Configuration

- `config.yml` : affichage (scoreboard, tablist, chat, MOTD), modules, homes, équipes, HDV, monde ressources, Discord
- `missions.yml` : missions quotidiennes et hebdomadaires
- `recompenses.yml` : monnaie, titres, cosmétiques, paliers, récompense quotidienne, boutique
- `evenements.yml` : saisons et boss mondial

Après une modification : `/lmc reload`.

## Compiler

Le workflow GitHub Actions compile le .jar à chaque envoi (onglet Actions → Artifacts). En local : `mvn package`.
