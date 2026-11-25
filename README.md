Plugins du serveur LyonMC (par wassdead), pour Purpur / Paper 1.21.8.

- **LyonAchievements-7.3.2.jar** : succès, grades, missions, métiers, saisons, économie, hologrammes, menus, chat, site.
  Nouveau, **Noël** (du 1er décembre au 6 janvier, réglages dans `plugins/LyonAchievements/noel.yml`) :
  **calendrier de l'Avent** (`/calendrier`, une case par jour, gros cadeau le 24, cases ratées rattrapables jusqu'au 24,
  titre Lutin 2026 pour les 24 cases), **chasse au trésor** (`/tresor`, un coffre caché tous les 5 jours pas trop loin du spawn,
  des quêtes dévoilent où il est, un cadeau pour chaque joueur qui le trouve et un bonus pour le premier, des faux coffres piégés),
  **boss de Noël le 24 à 21 h** qui lâche des cadeaux, **MOTD de Noël** et **icône du serveur avec un bonnet**.
- **HUB-1.9.2.jar** : le monde du hub, son inventaire, les podiums de PNJ, le hub médiéval.
  Nouveau : l'**arène de Noël** dans **son propre monde** (`noel`, à part du hub) : la map `happychristmas.schem` et, juste à côté
  avec la vue sur la map, la **bataille de boules de neige** comme sur les consoles : des étages de neige en relief, on casse
  la neige sous les pieds des autres, le dernier debout gagne. Au hub, un **PNJ Père Noël** emmène jouer en un clic
  (la partie démarre dès 2 joueurs), avec les chiffres en direct au-dessus (`/noel`, `/hub`, `/bataille`, `/bataille top`).
- **COMMUNAUTE-1.4.1.jar** : premiers pas, centre d'aide, règlement, astuces, votes, parrainage, fidélité, amis, messages privés,
  signalements, modération, page Suggestions du site. (Pas de changement.)
- **lyonmc-site.zip** : le site lyonmc.fr en mode Noël (nouveau logo, neige, page **/noel**), page Joueurs corrigée.

Mise à jour : télécharger les nouveaux .jar dans `plugins/`, supprimer les anciens, puis `stop` (le serveur redémarre tout seul).
Les données des joueurs sont conservées, les nouveaux réglages s'ajoutent tout seuls aux fichiers de config.

Village de Noël (une seule fois) : mettre `happychristmas.schem` dans `plugins/HUB/schematics/`, puis en jeu
`/lyonhub noel installer confirmer` (crée le monde `noel` et y colle tout, le hub n'est pas touché),
puis au spawn du hub : `/lyonhub noel pnj-hub` (le PNJ Père Noël). Après les fêtes : `noel.actif: false` dans `plugins/HUB/config.yml` puis `/lyonhub reload`.

Site : extraire `lyonmc-site.zip` dans le dossier `siteweb` (les dossiers `data` et `pages` ne sont pas touchés).
Le site passe tout seul en mode Noël du 20 novembre au 6 janvier.
