"""Faux village de Noël pour les tests (happychristmas.schem n'est pas dans le dépôt) :
un sol enneigé aux endroits utilisés par le plugin (arrivée, attente, PNJ), de l'herbe au nom d'avant 1.20.3
et deux têtes avec un skin (ancien format SkullOwner, comme dans le vrai fichier)."""
import sys, base64, numpy as np, nbtlib
from nbtlib.tag import Compound, Short, Int, IntArray, ByteArray, List, String

W, H, L = 150, 90, 200
pal = ["minecraft:air", "minecraft:snow_block", "minecraft:grass_block[snowy=true]", "minecraft:grass",
       "minecraft:player_head[powered=false,rotation=4]", "minecraft:spruce_log[axis=y]"]
a = np.zeros((H, L, W), np.uint8)
a[60:76, 90:150, 20:101] = 1        # le rocher
a[76, 90:150, 20:101] = 2           # la place (arrivée en x 84, z 120, pieds en y 77)
a[60:75, 100:150, 101:116] = 1      # la pente vers l'arène (attente et PNJ)
a[77, 125, 60:70] = 3               # herbe (nom d'avant 1.20.3 : short_grass)
a[77, 118, 60] = 4                  # deux têtes
a[77, 118, 62] = 4
a[77:85, 130, 40] = 5               # un tronc
texture = base64.b64encode(b'{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/'
                           b'94ebbb40c46f7799c4b0696cdee6a5c5cb44501c93a008762ff9c0ed12432793"}}}').decode()
heads = [Compound({"Id": String("minecraft:skull"), "Pos": IntArray([x, 77, 118]),
                   "SkullOwner": Compound({"Id": IntArray([67411088, -739686879, -1666252800, -1431940749 + x]),
                                           "Properties": Compound({"textures": List[Compound]([Compound({"Value": String(texture)})])}),
                                           "Name": String("Head Database")})}) for x in (60, 62)]
s = Compound({"Version": Int(2), "DataVersion": Int(3578), "Width": Short(W), "Height": Short(H), "Length": Short(L),
              "Offset": IntArray([0, 0, 0]), "PaletteMax": Int(len(pal)),
              "Palette": Compound({k: Int(i) for i, k in enumerate(pal)}),
              "BlockData": ByteArray(a.ravel().astype(np.int8)), "BlockEntities": List[Compound](heads),
              "Metadata": Compound({"WEOffsetX": Int(-159), "WEOffsetY": Int(-1), "WEOffsetZ": Int(-101)})})
nbtlib.File(s, root_name="Schematic").save(sys.argv[1], gzipped=True)
print("blocs :", int((a != 0).sum()))
