#!/bin/bash
# Installe ou met à jour le site lyonmc.fr dans le dossier siteweb du serveur.
# À lancer avec l'utilisateur du serveur Minecraft (sans sudo) :
#   bash installer-site.sh [dossier]
# Le dossier data/ (écrit par le plugin) n'est jamais touché.
set -euo pipefail

DEST="${1:-/home/serveur/SSDM2/Event/LyonMc/siteweb}"

if [ "$(id -u)" -eq 0 ]; then
  echo "Lance ce script sans sudo, avec l'utilisateur du serveur Minecraft."
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
echo "Téléchargement du site..."
curl -fsSL https://github.com/BIAY-Wassim/Lyonmc/archive/refs/heads/main.tar.gz | tar xz -C "$TMP"
SRC=$(find "$TMP" -mindepth 2 -maxdepth 2 -type d -name web | head -1)
[ -n "$SRC" ] && [ -f "$SRC/index.html" ] || { echo "Archive incomplète, rien n'a été modifié."; exit 1; }

mkdir -p "$DEST/data"
# Remplace tout sauf data/
find "$DEST" -mindepth 1 -maxdepth 1 ! -name data -exec rm -rf {} +
cp -r "$SRC"/. "$DEST"/
# Apache (www-data) doit pouvoir lire le site
find "$DEST" -type d -exec chmod 755 {} +
find "$DEST" -type f -exec chmod 644 {} +

echo "Site installé dans $DEST"
