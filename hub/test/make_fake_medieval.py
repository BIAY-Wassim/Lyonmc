"""Faux hub médiéval pour les tests (même taille et même décalage que hub-medieval.schem, qui n'est pas dans le dépôt)."""
import sys, numpy as np, nbtlib
from nbtlib.tag import Compound, Short, Int, IntArray, ByteArray, List

W, H, L = 295, 92, 295
pal = ["minecraft:air", "minecraft:andesite", "minecraft:stone", "minecraft:grass_block[snowy=false]", "minecraft:oak_log[axis=y]"]
a = np.zeros((H, L, W), np.uint8)
zz, xx = np.meshgrid(np.arange(L), np.arange(W), indexing="ij")
r = np.hypot(xx - 147, zz - 147)
a[0:20][:, r < 140] = 2                          # l'île
a[20][r < 140] = 3                               # herbe
a[21][(abs(xx - 174) < 40) & (abs(zz - 146) < 30)] = 1   # la place, sol en y = 64 au collage
a[22:40, 100, 100] = 4                           # un tronc
s = Compound({"Version": Int(2), "DataVersion": Int(4440), "Width": Short(W), "Height": Short(H), "Length": Short(L),
              "Offset": IntArray([0, 0, 0]), "PaletteMax": Int(len(pal)),
              "Palette": Compound({k: Int(i) for i, k in enumerate(pal)}),
              "BlockData": ByteArray(a.ravel().astype(np.int8)), "BlockEntities": List[Compound]([]),
              "Metadata": Compound({"WEOffsetX": Int(-174), "WEOffsetY": Int(-22), "WEOffsetZ": Int(-146)})})
nbtlib.File(s, root_name="Schematic").save(sys.argv[1], gzipped=True)
print("blocs :", int((a != 0).sum()))
