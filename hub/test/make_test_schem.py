"""Crée un petit .schem de test (format Sponge v2) pour vérifier /lyonhub coller."""
import sys, numpy as np, nbtlib
from nbtlib.tag import Compound, Short, Int, IntArray, ByteArray, String, List

W = L = 161
H = 40
pal = ["minecraft:air", "minecraft:andesite", "minecraft:stone", "minecraft:oak_fence[east=false,north=false,south=false,west=false,waterlogged=false]",
       "minecraft:cobblestone_wall", "minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]",
       "minecraft:glass_pane[east=false,north=false,south=false,west=false,waterlogged=false]", "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"]
a = np.zeros((H, L, W), np.int32)
a[0, :, :] = 1                                   # sol
a[1, 85, 70:91] = 3                              # ligne de clôtures (z = +5 au collage)
a[1, 75, 70:91] = 4                              # ligne de murets (z = -5)
a[1, 90, 70:91] = 6                              # vitres (z = +10)
a[2, 60:70, 60:70] = 5                           # feuilles
a[1, 70, 100:110] = 7                            # escaliers
yy, zz, xx = np.meshgrid(np.arange(2, 40), np.arange(L), np.arange(W), indexing="ij")
mask = ((xx + zz) % 2 == 0) & ((abs(xx - 80) > 30) | (abs(zz - 80) > 30))
a[2:40][mask] = 2                                # masse de blocs pour tester la vitesse
out = bytearray()
for v in a.ravel():
    v = int(v)
    while v & ~0x7F:
        out.append((v & 0x7F) | 0x80); v >>= 7
    out.append(v)
s = Compound({"Version": Int(2), "DataVersion": Int(4440), "Width": Short(W), "Height": Short(H), "Length": Short(L),
              "Offset": IntArray([0, 0, 0]), "PaletteMax": Int(len(pal)),
              "Palette": Compound({k: Int(i) for i, k in enumerate(pal)}),
              "BlockData": ByteArray(np.frombuffer(bytes(out), dtype=np.int8)), "BlockEntities": List[Compound]([]),
              "Metadata": Compound({"WEOffsetX": Int(-80), "WEOffsetY": Int(-1), "WEOffsetZ": Int(-80)})})
nbtlib.File(s, root_name="Schematic").save(sys.argv[1], gzipped=True)
print("blocs :", int((a != 0).sum()))
