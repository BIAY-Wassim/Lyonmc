# LyonAchievements · lyonmc.fr

Plugin Paper 1.21.8 qui suit les succès (advancements) des joueurs en survie.

## Fonctionnalités

- **Scoreboard** sobre : rang, succès, barre de progression, détail par catégorie, défis.
- **Tablist** : stats du joueur, podium, joueurs triés par nombre de succès.
- **Au-dessus des joueurs** : rang devant le pseudo et, en dessous, la vie et le nombre de succès.
- **Chat** : badge de rang, carte de stats au survol du pseudo, messages de connexion discrets.
- **/missions** : menu graphique complet.
  - Accueil : profil, catégories avec progression, raccourcis.
  - « Presque terminés » : les succès à ta portée, du plus avancé au moins avancé.
  - Chaque succès : description, rareté, progression, **liste des critères restants** (biomes, mobs, nourritures, animaux… traduits dans la langue du joueur), succès à débloquer avant.
  - Filtres manquants / tous / obtenus, pagination.
- **Podium** `/classement` : têtes des 3 premiers sur des blocs or / fer / cuivre, puis le top 10.
- **MOTD** de la liste des serveurs : 2e ligne qui change (stats du serveur, leader, dernier exploit), et texte au survol du nombre de joueurs (podium, joueurs connectés).
- **Message de bienvenue** à la connexion avec les stats du joueur.
- Annonces de succès, paliers, nouveau n°1.
- Classement complet, y compris les joueurs hors-ligne.

## Commandes

| Commande | Rôle |
|---|---|
| `/missions [catégorie]` | menu graphique |
| `/succes` | ton profil |
| `/succes <joueur>` | profil d'un joueur |
| `/classement` | podium avec les têtes |
| `/succes top texte` | classement dans le chat |
| `/succes sidebar` | afficher ou masquer le scoreboard |
| `/succes reload` / `import` | admin |

## Mise à jour

À chaque nouvelle version de config, l'ancien `config.yml` est renommé automatiquement en `config-ancienne.yml` et remplacé par la nouvelle configuration.

## Compiler

Le workflow GitHub Actions compile le .jar à chaque envoi (onglet Actions → Artifacts). En local : `mvn package`.
