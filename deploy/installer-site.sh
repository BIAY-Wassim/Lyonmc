#!/bin/bash
# Installe ou met à jour le site lyonmc.fr dans /var/www/lyonmc
# Usage : sudo bash installer-site.sh <utilisateur qui lance le serveur Minecraft>
set -euo pipefail

DEST=/var/www/lyonmc
MC_USER="${1:-${SUDO_USER:-}}"
if [ -z "$MC_USER" ] || ! id "$MC_USER" >/dev/null 2>&1; then
  echo "Indique l'utilisateur qui lance le serveur Minecraft : sudo bash installer-site.sh <utilisateur>"
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
echo "Téléchargement du site..."
curl -fsSL https://github.com/BIAY-Wassim/Lyonmc/archive/refs/heads/main.tar.gz | tar xz -C "$TMP"
SRC=$(find "$TMP" -mindepth 2 -maxdepth 2 -type d -name web | head -1)
[ -f "$SRC/index.html" ] || { echo "Archive incomplète, rien n'a été modifié."; exit 1; }

mkdir -p "$DEST/data"
# Remplace tout sauf le dossier data/ (les fichiers du plugin)
find "$DEST" -mindepth 1 -maxdepth 1 ! -name data -exec rm -rf {} +
cp -a "$SRC"/. "$DEST"/
chown -R root:root "$DEST"
chown -R "$MC_USER": "$DEST/data"
find "$DEST" -type d -exec chmod 755 {} +
find "$DEST" -type f -exec chmod 644 {} +

echo "Site installé dans $DEST"
echo "Le plugin écrira ses données dans $DEST/data (propriétaire : $MC_USER)."
