import json, urllib.request, urllib.parse, os
UA = {"User-Agent": "LyonMC-HUB/1.9 (skins de PNJ)"}
def get(url):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20)
        return r.read()
    except Exception as e:
        print("ERR", url, e); return None
found = {}
for q in ["santa", "santa claus", "pere noel", "père noël", "christmas santa", "father christmas"]:
    raw = get("https://api.mineskin.org/v2/skins?size=48&filter=" + urllib.parse.quote(q))
    if not raw: continue
    open("list-" + q.replace(" ", "_") + ".json", "wb").write(raw)
    d = json.loads(raw)
    items = d.get("skins") or d.get("items") or d.get("data") or []
    for s in items:
        u = s.get("uuid") or s.get("id")
        if u and u not in found: found[u] = s
print("mineskin :", len(found))
res = []
for u, s in list(found.items())[:120]:
    raw = get("https://api.mineskin.org/v2/skins/" + u)
    if not raw: continue
    d = json.loads(raw); sk = d.get("skin") or d
    tex = sk.get("texture", {})
    data = tex.get("data", {}); url = (tex.get("url") or {}).get("skin")
    h = (tex.get("hash") or {}).get("skin")
    if not url and h: url = "https://textures.minecraft.net/texture/" + h
    png = get(url) if url else None
    if png: open("ms-" + u + ".png", "wb").write(png)
    res.append({"src": "mineskin", "id": u, "name": sk.get("name"), "variant": sk.get("variant"),
                "value": data.get("value"), "signature": data.get("signature"), "png": bool(png)})
for name in ["Santa", "SantaClaus", "Santa_Claus", "PereNoel", "Pere_Noel", "FatherChristmas", "SantaClausMC", "Saint_Nicolas", "Noel"]:
    raw = get("https://api.mojang.com/users/profiles/minecraft/" + name)
    if not raw: continue
    uid = json.loads(raw)["id"]
    raw = get("https://sessionserver.mojang.com/session/minecraft/profile/" + uid + "?unsigned=false")
    if not raw: continue
    p = json.loads(raw)
    for prop in p.get("properties", []):
        if prop["name"] != "textures": continue
        import base64
        t = json.loads(base64.b64decode(prop["value"]))
        url = t.get("textures", {}).get("SKIN", {}).get("url")
        png = get(url) if url else None
        if png: open("mj-" + name + ".png", "wb").write(png)
        res.append({"src": "mojang", "id": name, "name": p.get("name"), "value": prop["value"], "signature": prop.get("signature"), "png": bool(png)})
json.dump(res, open("skins.json", "w"), indent=1)
print("total :", len(res))
