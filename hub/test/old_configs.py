"""Simule un serveur mis à jour : fichiers de config d'avant (sans grades, sans bonus ressources, sans objets du hub)."""
import os, sys
root, srv = sys.argv[1], sys.argv[2]
def cut(text, start, end):
    i = text.index(start); j = text.index(end, i)
    return text[:i] + text[j:]
os.makedirs(f"{srv}/plugins/LyonAchievements", exist_ok=True)
os.makedirs(f"{srv}/plugins/HUB", exist_ok=True)
r = open(f"{root}/src/main/resources/recompenses.yml").read()
open(f"{srv}/plugins/LyonAchievements/recompenses.yml", "w").write(cut(r, "# ───────────────────────── Grades (automatiques)", "# ───────────────────────── Titres"))
c = open(f"{root}/src/main/resources/config.yml").read()
open(f"{srv}/plugins/LyonAchievements/config.yml", "w").write(cut(c, "  # Tout ce qui pousse à miner ici", "# Site lyonmc.fr"))
h = open(f"{root}/hub/src/main/resources/config.yml").read()
open(f"{srv}/plugins/HUB/config.yml", "w").write(cut(h, "# ───────────────────────── Joueurs au hub", "# Classements écrits"))
print("anciens fichiers de config en place")
