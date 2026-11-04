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
# evenements.yml d'avant la 7.1 (boss limité à 1024 de vie)
e = open(f"{root}/src/main/resources/evenements.yml").read()
i = e.index("  vie-base:"); j = e.index("  # Jours en anglais", i)
e = e[:i] + "  # Vie : base + par joueur présent (maximum 1024)\n  health-base: 400\n  health-per-player: 150\n  damage: 12\n  armor: 12\n" + e[j:]
open(f"{srv}/plugins/LyonAchievements/evenements.yml", "w").write(e)
print("anciens fichiers de config en place")
