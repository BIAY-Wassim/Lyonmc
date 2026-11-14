Plugins du serveur LyonMC (par wassdead), pour Purpur / Paper 1.21.8.

- **LyonAchievements-7.2.0.jar** : succès, grades, missions, métiers, saisons, économie, hologrammes, menus, chat, site.
  Nouveau : le **boss s'adapte au nombre de joueurs** (arène et serveur), **stuff gardé** en cas de mort dans l'arène pendant le combat
  (-100 pièces, un peu de lenteur à partir de la 2e mort), **un œuf de l'End à chaque dragon tué** (l'œuf repart sur le portail
  une fois le succès obtenu), succès **Shear Brilliance** corrigé.
- **HUB-1.8.2.jar** : le monde du hub, son inventaire, les podiums de PNJ, le hub médiéval.
- **COMMUNAUTE-1.4.0.jar** : premiers pas, centre d'aide, règlement, astuces, votes, parrainage, fidélité, amis, messages privés,
  signalements, modération. Nouveau : **`/site`** connecte le joueur au site, et la **page Suggestions** (idées, votes, statuts du staff).
- **lyonmc-site.zip** : le site lyonmc.fr, avec la nouvelle page **Suggestions**.

Mise à jour : télécharger les nouveaux .jar dans `plugins/`, supprimer les anciens, puis `stop` (le serveur redémarre tout seul).
Les données des joueurs sont conservées, les nouveaux réglages s'ajoutent tout seuls aux fichiers de config.

Site : extraire `lyonmc-site.zip` dans le dossier `siteweb` (les dossiers `data` et `pages` ne sont pas touchés).

Apache (une seule fois) : dans le site lyonmc.fr, ajouter sous la ligne `ProxyPassReverse /dynmap/ ...` :
`ProxyPass /api/ http://127.0.0.1:8135/api/ retry=0 timeout=15`, puis `apache2ctl configtest` et `systemctl reload apache2`
(voir `deploy/lyonmc.conf`). Sans ça, la page Suggestions s'affiche mais personne ne peut se connecter.
