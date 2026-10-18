/* lyonmc.fr — site du serveur. Les données viennent du plugin (dossier /data). */
(() => {
  "use strict";

  const app = document.getElementById("app");
  const cache = {};
  let FR = {};

  // ───────────── Outils ─────────────
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const num = (n) => Number(n || 0).toLocaleString("fr-FR");
  const face = (uuid, size = 64) => `https://mc-heads.net/avatar/${uuid}/${size}`;
  const body = (uuid) => `https://mc-heads.net/body/${uuid}/240`;
  const icon = (id) => `/img/icons/${id}.png`;
  const img = (src, cls = "", alt = "") => `<img class="${cls}" src="${src}" alt="${esc(alt)}" loading="lazy" onerror="this.style.visibility='hidden'">`;
  const tr = (key, fallback) => (key && FR[key]) || fallback || key || "";

  function ago(ms) {
    if (!ms) return "";
    const s = Math.max(0, (Date.now() - ms) / 1000);
    if (s < 60) return "à l'instant";
    if (s < 3600) return `il y a ${Math.floor(s / 60)} min`;
    if (s < 86400) return `il y a ${Math.floor(s / 3600)} h`;
    if (s < 86400 * 30) return `il y a ${Math.floor(s / 86400)} j`;
    if (s < 86400 * 365) return `il y a ${Math.floor(s / 86400 / 30)} mois`;
    return `il y a ${Math.floor(s / 86400 / 365)} an(s)`;
  }
  function until(ms) {
    const m = Math.max(0, Math.round((ms - Date.now()) / 60000));
    const d = Math.floor(m / 1440), h = Math.floor((m % 1440) / 60), mn = m % 60;
    if (d > 0) return `${d} j ${h} h`;
    if (h > 0) return `${h} h ${mn} min`;
    return `${mn} min`;
  }
  function duration(sec) {
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
    return h > 0 ? `${num(h)} h ${m} min` : `${m} min`;
  }
  const dayFmt = new Intl.DateTimeFormat("fr-FR", { weekday: "long", hour: "2-digit", minute: "2-digit", timeZone: "Europe/Paris" });
  const dateFmt = new Intl.DateTimeFormat("fr-FR", { day: "numeric", month: "long", year: "numeric", timeZone: "Europe/Paris" });

  async function data(name, fresh = true) {
    if (!fresh && cache[name]) return cache[name];
    try {
      const r = await fetch(`/data/${name}.json`, { cache: "no-store" });
      if (!r.ok) throw new Error(r.status);
      cache[name] = await r.json();
    } catch (e) {
      if (!cache[name]) cache[name] = null;
    }
    return cache[name];
  }
  async function loadFr() {
    try { FR = await (await fetch("/assets/fr.json")).json(); } catch (e) { FR = {}; }
  }

  const ITEM = (it) => `${it.amount}× ${esc(tr(it.key, it.id))}`;
  function bundle(b) {
    if (!b) return "";
    const p = [];
    if (b.coins) p.push(`${num(b.coins)} pièces`);
    if (b.seasonPoints) p.push(`${num(b.seasonPoints)} points de saison`);
    (b.titles || []).forEach((t) => p.push(`titre ${t}`));
    if (b.color) p.push(`couleur ${b.color}`);
    if (b.trail) p.push(`traînée ${esc(b.trail)}`);
    if (b.homes) p.push(`+${b.homes} home${b.homes > 1 ? "s" : ""}`);
    if (b.xpLevels) p.push(`${b.xpLevels} niveaux d'XP`);
    if (b.xp) p.push(`${b.xp} points d'XP`);
    (b.items || []).forEach((it) => p.push(ITEM(it)));
    return `<span class="reward-line">${p.map((x) => `<span class="r">${x}</span>`).join("")}</span>`;
  }
  function frame(a, done, small = false) {
    return `<span class="frame ${a.frame} ${done === true ? "done" : done === false ? "locked" : ""} ${small ? "small" : ""}">${img(icon(a.icon))}</span>`;
  }
  const advTitle = (a) => (a.titleKey ? tr(a.titleKey, a.title) : a.title);
  const advDesc = (a) => (a.descKey ? tr(a.descKey, a.desc) : a.desc);
  const catIcon = { story: "grass_block", nether: "netherrack", end: "end_stone", adventure: "filled_map", husbandry: "hay_block", lyonmc: "nether_star", other: "book" };

  const catName = (c) => (c.id === "lyonmc" ? c.name : tr(`advancements.${c.id}.root.title`, c.name));

  function advIndex(contenu) {
    const idx = {};
    (contenu?.categories || []).forEach((c) => c.advancements.forEach((a) => (idx[a.key] = { ...a, cat: c.id })));
    return idx;
  }

  function setTitle(t) {
    document.title = t ? `${t} · LyonMC` : "LyonMC · Serveur Minecraft survie 1.21.8";
  }
  function setNav(path) {
    document.querySelectorAll(".nav a").forEach((a) => {
      const href = a.getAttribute("href");
      const on = href === "/" ? path === "/" : path.startsWith(href.replace(/\/$/, ""));
      a.toggleAttribute("aria-current", on);
      if (on) a.setAttribute("aria-current", "page");
    });
  }

  // ───────────── Barre du haut ─────────────
  async function refreshPill() {
    const s = await data("serveur");
    const pill = document.getElementById("online-pill");
    if (!s) {
      pill.innerHTML = `<span class="dot"></span><span>Serveur injoignable</span>`;
      return;
    }
    pill.innerHTML = `<span class="dot ${s.online > 0 ? "on" : ""}"></span><span>${s.online} en ligne</span>`;
    const d = document.getElementById("discord-link");
    if (s.discord) { d.href = s.discord; d.hidden = false; }
  }

  // ───────────── Accueil ─────────────
  async function home() {
    setTitle("");
    const [s, c, contenu] = await Promise.all([data("serveur"), data("classements"), data("contenu", false)]);
    const idx = advIndex(contenu);
    const top = c?.achievements || [];
    const ip = s?.ip || "lyonmc.fr";
    const totals = s?.totals || {};

    const step = (p, cls, n) => p
      ? `<a class="step ${cls}" href="/joueur/${encodeURIComponent(p.name)}" data-link>
           <span class="nametag"><span class="who">${esc(p.name)}</span><span class="val">★ ${p.count}</span></span>
           ${img(body(p.uuid), "skin", "")}
           <span class="block"><span class="place">${n}</span></span></a>`
      : `<div class="step ${cls} empty"><span class="nametag"><span class="who">Place libre</span></span>
           <img class="skin empty" src="https://mc-heads.net/body/MHF_Steve/240" alt="">
           <span class="block"><span class="place">${n}</span></span></div>`;

    const online = (s?.players || []).slice(0, 12).map((p) => img(face(p.uuid, 32), "", p.name)).join("");

    const season = s?.season;
    const sTop = (c?.season || []).slice(0, 3);
    const boss = s?.boss || {};
    let bossBig, bossSub;
    if (boss.active) { bossBig = "En combat"; bossSub = "Tape /boss en jeu pour rejoindre l'arène."; }
    else if (boss.next) { bossBig = dayFmt.format(new Date(boss.next)); bossSub = `Dans ${until(boss.next)}. L'arène ouvre 15 minutes avant.`; }
    else { bossBig = "Bientôt"; bossSub = "Les horaires seront annoncés en jeu."; }

    const last = s?.last;
    const lastAdv = last && idx[last.advancement];

    app.innerHTML = `
      <section class="hero">
        <div class="wrap">
          <div class="hero-copy">
            <h1 class="wordmark">LyonMC</h1>
            <p class="hero-lead">Survie Java 1.21.8. ${num(totals.achievements || 146)} succès à obtenir, des missions chaque jour et un classement qui repart chaque mois.</p>
            <div class="address"><code id="ip">${esc(ip)}</code><button type="button" id="copy">Copier l'adresse</button></div>
            <div class="hero-meta">
              <span class="online-pill"><span class="dot ${s?.online ? "on" : ""}"></span>${s ? `${s.online} joueur${s.online > 1 ? "s" : ""} en ligne sur ${s.max}` : "Statut indisponible"}</span>
              <span class="faces">${online}</span>
            </div>
          </div>
          <div class="podium" aria-label="Podium des succès">
            ${step(top[1], "second", 2)}${step(top[0], "first", 1)}${step(top[2], "third", 3)}
          </div>
        </div>
      </section>

      <section class="section"><div class="wrap">
        <h2>En ce moment</h2>
        <p class="intro">${num(totals.unlocked)} succès débloqués par ${num(totals.ranked)} joueurs depuis l'ouverture.</p>
        <div class="now">
          <div>
            <h3>${esc(season?.name || "Saison")}</h3>
            <span class="big">fin dans ${season ? until(season.endsAt) : "?"}</span>
            ${season?.weekend ? `<span class="small" style="color:var(--gold)">Week-end : les points comptent ×${season.multiplier}</span>` : ""}
            <ol>${sTop.length ? sTop.map((p, i) => `<li>${img(face(p.uuid, 32), "", p.name)}<a href="/joueur/${encodeURIComponent(p.name)}" data-link>${esc(p.name)}</a><span class="pts">${num(p.points)} pts</span></li>`).join("") : `<li class="muted">Personne n'a encore marqué de points.</li>`}</ol>
          </div>
          <div>
            <h3>Boss mondial : ${esc(boss.name || "Le Colosse")}</h3>
            <span class="big">${esc(bossBig)}</span>
            <span class="muted">${esc(bossSub)}</span>
          </div>
          <div>
            <h3>Dernier exploit</h3>
            ${last ? `<div class="exploit">${img(face(last.uuid, 64), "face", last.player)}
              <div><a href="/joueur/${encodeURIComponent(last.player)}" data-link><strong>${esc(last.player)}</strong></a>
              <div>${lastAdv ? esc(advTitle(lastAdv)) : ""}</div>
              <div class="small muted">${ago(last.time)}</div></div></div>` : `<span class="muted">Aucun succès pour l'instant.</span>`}
          </div>
        </div>
      </div></section>

      <section class="section"><div class="wrap">
        <h2>Ce qui t'attend</h2>
        <p class="intro">Une survie classique, sans pay-to-win, avec de quoi se fixer des objectifs chaque jour.</p>
        <div class="features">
          ${[
            ["knowledge_book", "Succès", `${num(totals.achievements || 146)} succès, dont ${(contenu?.categories || []).find((c) => c.id === "lyonmc")?.advancements.length || 22} propres au serveur. Chacun rapporte des pièces et des points de saison.`],
            ["writable_book", "Missions", "Trois missions par jour et trois par semaine. Les finir toutes fait monter ta série et ton bonus."],
            ["clock", "Saisons", "Un classement qui repart chaque mois. Le week-end, les points comptent double."],
            ["mace", "Boss mondial", "Le Colosse apparaît deux fois par semaine. Ta récompense dépend des dégâts que tu infliges."],
            ["name_tag", "Équipes", "Un tag devant ton pseudo, un chat privé et un home commun."],
            ["emerald", "Hôtel des ventes", "Vends et achète entre joueurs avec les pièces du serveur."],
          ].map(([ic, t, d]) => `<div class="feature"><span class="slot">${img(icon(ic), "", "")}</span><div><h3>${t}</h3><p>${d}</p></div></div>`).join("")}
        </div>
      </div></section>

      <section class="section"><div class="wrap">
        <h2>Rejoindre le serveur</h2>
        <p class="intro">Il te faut Minecraft Java. Les versions plus anciennes ne sont pas acceptées.</p>
        <ol class="steps">
          <li><strong>Lance Minecraft en 1.21.8</strong><p>Dans le launcher, choisis la dernière version.</p></li>
          <li><strong>Ajoute le serveur</strong><p>Multijoueur, Ajouter un serveur, puis l'adresse ${esc(ip)}.</p></li>
          <li><strong>Tape /menu</strong><p>Tout est là : missions, récompense du jour, classements et boutique.</p></li>
        </ol>
      </div></section>`;

    const btn = document.getElementById("copy");
    btn.addEventListener("click", async () => {
      try { await navigator.clipboard.writeText(ip); btn.textContent = "Adresse copiée"; }
      catch (e) { btn.textContent = ip; }
      setTimeout(() => (btn.textContent = "Copier l'adresse"), 2000);
    });
  }

  // ───────────── Classements ─────────────
  // [clé dans classements.json, libellé, adresse]
  const BOARDS = [
    ["achievements", "Succès", ""],
    ["season", "Saison", "saison"],
    ["teams", "Équipes", "equipes"],
    ["missions", "Missions", "missions"],
    ["richest", "Fortune", "fortune"],
  ];
  const monthFmt = new Intl.DateTimeFormat("fr-FR", { month: "long", year: "numeric", timeZone: "UTC" });
  function pastSeasons(list) {
    if (!list || !list.length) return "";
    const medal = ["first", "second", "third"];
    return `<h2 class="sub">Saisons passées</h2><table class="board history"><tbody>${list.slice().reverse().map((h) => {
      const [y, m] = String(h.month).split("-").map(Number);
      const label = y && m ? monthFmt.format(new Date(Date.UTC(y, m - 1, 1))) : esc(h.month);
      return `<tr><td class="name"><strong>Saison ${esc(h.id)}</strong><span class="t muted">${label}</span></td>
        <td>${(h.podium || []).map((p, i) => `<span class="h-pos ${medal[i] || ""}">${p.pos}</span> <a href="/joueur/${encodeURIComponent(p.name)}" data-link>${esc(p.name)}</a> <small class="muted">${num(p.points)} pts</small>`).join("<br>")}</td></tr>`;
    }).join("")}</tbody></table>`;
  }

  async function boards(slug) {
    setTitle("Classements");
    const [c, s] = await Promise.all([data("classements"), data("serveur")]);
    const current = (BOARDS.find((b) => b[2] === (slug || "")) || BOARDS[0])[0];
    const total = s?.totals?.achievements || 0;
    const rows = (c?.[current] || []);
    const row = (p, i, value) => `<tr class="p${i + 1}">
        <td class="pos">${i + 1}</td>
        <td style="width:44px">${img(face(p.uuid, 64), "face", p.name)}</td>
        <td class="name"><a href="/joueur/${encodeURIComponent(p.name)}" data-link>${esc(p.name)}</a>${p.team ? `<span class="tag">${esc(p.team)}</span>` : ""}${p.title ? `<span class="t">${p.title}</span>` : ""}</td>
        <td class="num">${value}</td></tr>`;
    let body;
    if (!c) body = `<p class="notice">Les classements ne sont pas encore disponibles. Reviens dans une minute.</p>`;
    else if (!rows.length) body = `<p class="empty">${{
      season: "Personne n'a encore marqué de points cette saison. Chaque succès, mission ou boss en rapporte.",
      teams: "Aucune équipe pour l'instant. Fondes-en une en jeu avec /equipe creer.",
      missions: "Personne n'a encore terminé de mission. Tape /missions en jeu pour voir les tiennes.",
    }[current] || "Personne dans ce classement pour l'instant."}</p>`;
    else if (current === "teams") {
      body = `<table class="board"><tbody>${rows.map((t, i) => `<tr class="p${i + 1}">
          <td class="pos">${i + 1}</td>
          <td class="name"><strong>${esc(t.name)}</strong><span class="tag" style="color:${esc(t.color)}">${esc(t.tag)}</span>
            <span class="t muted">${t.members.map(esc).join(", ")}</span></td>
          <td class="num">${num(t.achievements)} <small>succès</small><br><small>${num(t.season)} pts de saison</small></td></tr>`).join("")}</tbody></table>`;
    } else {
      const fmt = {
        achievements: (p) => `${p.count} <small>/ ${total}</small><span class="meter mini"><i style="width:${total ? (p.count / total) * 100 : 0}%"></i></span>`,
        season: (p) => `${num(p.points)} <small>pts</small>`,
        missions: (p) => `${num(p.missions)} <small>missions</small><br><small>série record ${p.streak} j</small>`,
        richest: (p) => `${num(p.coins)} <small>pièces</small>`,
      }[current];
      body = `<table class="board"><tbody>${rows.map((p, i) => row(p, i, fmt(p))).join("")}</tbody></table>`;
    }
    const intro = {
      achievements: `Les joueurs qui ont débloqué le plus de succès sur les ${total}.`,
      season: `${esc(s?.season?.name || "Saison en cours")}, fin dans ${s?.season ? until(s.season.endsAt) : "?"}. Les trois premiers gagnent des titres et des pièces.`,
      teams: "Classées par succès cumulés de leurs membres.",
      missions: "Les joueurs les plus réguliers sur les missions du jour et de la semaine.",
      richest: "Les plus grosses fortunes en pièces.",
    }[current];
    app.innerHTML = `
      <div class="page-head"><div class="wrap"><h1>Classements</h1><p>${intro}</p></div></div>
      <div class="wrap">
        <div class="tabs" role="tablist">${BOARDS.map(([id, label, path]) => `<button role="tab" aria-selected="${id === current}" data-tab="${path}">${label}</button>`).join("")}</div>
        <form class="search" id="search"><label class="visually-hidden" for="q">Chercher un joueur</label>
          <input id="q" placeholder="Chercher un joueur" autocomplete="off" list="names"><button>Voir le profil</button>
          <datalist id="names">${(c?.achievements || []).map((p) => `<option value="${esc(p.name)}">`).join("")}</datalist></form>
        <div style="margin:16px 0 64px">${body}${current === "season" ? pastSeasons(c?.history) : ""}</div>
      </div>`;
    app.querySelectorAll("[data-tab]").forEach((b) => b.addEventListener("click", () => navigate(b.dataset.tab ? `/classements/${b.dataset.tab}` : "/classements", true)));
    document.getElementById("search").addEventListener("submit", (e) => {
      e.preventDefault();
      const q = document.getElementById("q").value.trim();
      if (q) navigate(`/joueur/${encodeURIComponent(q)}`);
    });
  }

  // ───────────── Profil joueur ─────────────
  async function player(name, filter) {
    const [j, contenu, s] = await Promise.all([data("joueurs"), data("contenu", false), data("serveur")]);
    const p = j?.[name.toLowerCase()];
    if (!p) {
      setTitle("Joueur introuvable");
      app.innerHTML = `<div class="page-head"><div class="wrap"><h1>Joueur introuvable</h1>
        <p>Aucun joueur nommé « ${esc(name)} » ne s'est encore connecté sur LyonMC. Vérifie l'orthographe du pseudo.</p></div></div>
        <div class="wrap section"><a href="/classements" data-link>Voir les classements</a></div>`;
      return;
    }
    setTitle(p.name);
    if (name !== p.name) history.replaceState({}, "", location.pathname.replace(/\/joueur\/[^/]+/, `/joueur/${encodeURIComponent(p.name)}`));
    const total = s?.totals?.achievements || 0;
    const done = new Map((p.done || []).map(([k, t]) => [k, t]));
    const idx = advIndex(contenu);
    const segments = 20;
    const filled = total ? Math.round((p.count / total) * segments) : 0;
    const status = p.online ? `<span class="online-pill"><span class="dot on"></span>En ligne</span>`
      : p.lastSeen ? `<span class="muted small">Vu ${ago(p.lastSeen)}</span>` : "";
    const stat = (label, value) => `<div><dt>${label}</dt><dd>${value}</dd></div>`;
    const cats = (contenu?.categories || []).map((c) => {
      const n = p.categories?.[c.id] || 0, t = c.advancements.length;
      return `<div class="cat-row">${img(icon(catIcon[c.id] || "book"), "", "")}<span>${esc(catName(c))}</span>
        <span class="meter"><i style="width:${t ? (n / t) * 100 : 0}%"></i></span><span class="n">${n} / ${t}</span></div>`;
    }).join("");

    const mode = filter === "manquants" ? "missing" : "done";
    let list = [];
    if (mode === "done") {
      list = [...done.entries()].map(([k, t]) => ({ a: idx[k], t })).filter((x) => x.a);
    } else {
      list = Object.values(idx).filter((a) => !done.has(a.key)).sort((x, y) => (y.rarity || 0) - (x.rarity || 0)).map((a) => ({ a }));
    }
    const missingCount = Math.max(0, total - p.count);
    const PAGE = 24;
    const advHtml = list.map(({ a, t }, i) => {
      const secret = mode === "missing" && a.hidden && !a.rarity;
      return `<div class="adv ${a.frame} ${mode === "missing" ? "missing" : ""}"${i >= PAGE ? " hidden" : ""}>
        ${frame(secret ? { ...a, icon: "book" } : a, mode === "done", true)}
        <div class="txt"><strong>${secret ? "Succès secret" : esc(advTitle(a))}</strong>
          <span class="desc">${secret ? "À découvrir en jouant." : esc(advDesc(a))}</span>
          <span class="when">${t ? ago(t) : mode === "missing" ? `${String(a.rarity).replace(".", ",")} % des joueurs l'ont` : ""}</span></div></div>`;
    }).join("");

    app.innerHTML = `
      <div class="wrap profile">
        <aside class="profile-side">
          ${img(body(p.uuid), "body", p.name)}
          <div class="pedestal ${p.rank === 1 ? "gold" : ""}"></div>
          <h1>${esc(p.name)}</h1>
          ${p.title ? `<p class="title">${p.title}</p>` : ""}
          ${p.teamInfo ? `<p class="small">Équipe <strong>${esc(p.teamInfo.name)}</strong> <span class="tag" style="color:${esc(p.teamInfo.color)}">${esc(p.teamInfo.tag)}</span></p>` : ""}
          <p class="seen">${status}</p>
        </aside>
        <div>
          <div class="count-line"><span class="big">${p.count}</span><span class="of">succès sur ${total}${p.rank ? `, ${p.rank === 1 ? "1er" : p.rank + "e"} du serveur` : ""}</span></div>
          <div class="xpbar" role="img" aria-label="${p.count} succès sur ${total}">${Array.from({ length: segments }, (_, i) => `<i class="${i < filled ? "on" : ""}"></i>`).join("")}</div>
          <dl class="stats">
            ${stat("Saison", p.seasonPoints != null ? `${num(p.seasonPoints)} pts${p.seasonRank ? ` <small class="muted">#${p.seasonRank}</small>` : ""}` : "-")}
            ${stat("Pièces", num(p.coins))}
            ${stat("Missions terminées", num(p.missions))}
            ${stat("Série record", `${p.bestStreak || 0} j`)}
            ${stat("Défis réussis", `${p.frames?.challenge || 0}`)}
            ${stat("Temps de jeu", p.playtime ? duration(p.playtime) : "-")}
            ${stat("Boss vaincus", num(p.bossKills))}
            ${stat("Titres débloqués", num(p.titles))}
          </dl>
          <div class="cats">${cats}</div>
          <div class="tabs" role="tablist">
            <button role="tab" aria-selected="${mode === "done"}" data-f="">Obtenus (${p.count})</button>
            <button role="tab" aria-selected="${mode === "missing"}" data-f="manquants">À obtenir (${missingCount})</button>
          </div>
          <div class="adv-list">${advHtml || `<p class="empty">${mode === "done" ? "Aucun succès pour l'instant." : "Tous les succès sont débloqués."}</p>`}</div>
          ${list.length > PAGE ? `<button type="button" class="more" id="more">Afficher les ${list.length - PAGE} autres</button>` : ""}
        </div>
      </div>`;
    document.getElementById("more")?.addEventListener("click", (e) => {
      app.querySelectorAll(".adv[hidden]").forEach((el) => (el.hidden = false));
      e.currentTarget.remove();
    });
    app.querySelectorAll("[data-f]").forEach((b) => b.addEventListener("click", () =>
      navigate(`/joueur/${encodeURIComponent(p.name)}${b.dataset.f ? "/" + b.dataset.f : ""}`, true)));
  }

  // ───────────── Guide ─────────────
  const COMMANDS = [
    ["Général", [["/menu", "Le menu principal : tout le serveur en un clic"], ["/missions", "Tes missions du jour et de la semaine"], ["/recompense", "Récompense du jour"], ["/succes", "Ton profil de succès"], ["/classement", "Le podium des succès"], ["/saison", "Le classement du mois"], ["/carte", "Lien vers la carte en direct"]]],
    ["Récompenses", [["/parcours", "Les paliers à débloquer"], ["/titres", "Choisir ton titre"], ["/cosmetiques", "Couleur de pseudo et traînées"], ["/boutique", "Acheter titres, cosmétiques et homes"]]],
    ["Pièces", [["/money", "Ton solde"], ["/pay <joueur> <montant>", "Envoyer des pièces"], ["/baltop", "Les plus riches"], ["/hdv", "Hôtel des ventes"], ["/hdv vendre <prix>", "Vendre l'objet en main"], ["/hdv recuperer", "Récupérer tes objets non vendus"]]],
    ["Déplacements", [["/sethome <nom>", "Créer un home"], ["/home <nom>", "Aller à un home"], ["/homes", "Tes homes"], ["/spawn", "Retour au spawn"], ["/tpa <joueur>", "Demander à rejoindre un joueur"], ["/tpahere <joueur>", "Demander à un joueur de venir"], ["/tpaccept", "Accepter une demande"], ["/back", "Revenir à ta position précédente"], ["/rtp", "Téléportation aléatoire"], ["/ressources", "Aller dans le monde ressources"]]],
    ["Équipes", [["/equipe", "Menu de ton équipe"], ["/equipe creer <nom> <tag>", "Fonder une équipe"], ["/equipe inviter <joueur>", "Inviter un joueur"], ["/equipe rejoindre <équipe>", "Accepter une invitation"], ["/equipe chat", "Parler seulement à ton équipe"], ["/equipe home", "Home de l'équipe"]]],
    ["Événements", [["/boss", "Prochain boss, ou rejoindre l'arène"]]],
  ];
  const SECTIONS = [
    ["debuter", "Bien commencer"], ["commandes", "Commandes"], ["missions", "Missions"], ["pieces", "Pièces et boutique"],
    ["parcours", "Parcours de récompenses"], ["titres", "Titres et cosmétiques"], ["saisons", "Saisons"], ["boss", "Boss mondial"],
    ["equipes", "Équipes"], ["hdv", "Hôtel des ventes"], ["teleport", "Homes et téléportation"], ["ressources", "Monde ressources"], ["succes", "Tous les succès"],
  ];
  const DAYS = { MONDAY: "lundi", TUESDAY: "mardi", WEDNESDAY: "mercredi", THURSDAY: "jeudi", FRIDAY: "vendredi", SATURDAY: "samedi", SUNDAY: "dimanche" };

  async function guide(anchor) {
    setTitle("Guide");
    const g = await data("contenu", false);
    if (!g) {
      app.innerHTML = `<div class="page-head"><div class="wrap"><h1>Guide</h1><p class="notice">Le guide se charge depuis le serveur. Reviens dans une minute.</p></div></div>`;
      return;
    }
    const st = g.settings || {}, eco = g.economy || {}, mr = g.missionRewards || {};
    const tierNames = { easy: "Facile", medium: "Moyenne", hard: "Difficile", weekly: "Hebdomadaire" };
    const chips = (list) => `<ul class="chips">${(list || []).map((m) => `<li>${img(icon(m.icon), "", "")}${esc(m.name)}</li>`).join("")}</ul>`;
    const sched = (g.boss?.schedule || []).map((x) => { const [d, h] = x.split(/\s+/); return `${DAYS[d] || d} à ${h}`; });
    const totalAdv = (g.categories || []).reduce((n, c) => n + c.advancements.length, 0);

    const sec = {
      debuter: `<p>Bienvenue sur LyonMC. Voici comment bien démarrer :</p>
        <ol class="steps" style="margin-top:20px">
          <li><strong>Récupère ta récompense</strong><p>Tape <code class="cmd">/recompense</code> chaque jour. Elle grossit jusqu'au 7e jour d'affilée.</p></li>
          <li><strong>Lance tes missions</strong><p><code class="cmd">/missions</code> te donne trois objectifs par jour, du plus simple au plus dur.</p></li>
          <li><strong>Pose ton home</strong><p><code class="cmd">/sethome base</code> près de ta maison, puis <code class="cmd">/home base</code> pour y revenir.</p></li>
        </ol>
        <p style="margin-top:24px">Tu commences avec <strong>${num(eco.start)} pièces</strong> et <strong>${st.homesBase} homes</strong>. Le reste se gagne en jouant.</p>`,
      commandes: COMMANDS.map(([group, list]) => `<h3>${group}</h3><table class="cmds"><tbody>${list.map(([c, d]) => `<tr><td><code class="cmd">${esc(c)}</code></td><td>${esc(d)}</td></tr>`).join("")}</tbody></table>`).join(""),
      missions: `<p>Chaque jour à minuit, tu reçois une mission <strong>facile</strong>, une <strong>moyenne</strong> et une <strong>difficile</strong>. Le lundi, trois missions <strong>hebdomadaires</strong> plus longues arrivent.</p>
        <p>Terminer les trois missions du jour rapporte un bonus (${bundle(mr.bonus)}) et fait avancer ta <strong>série</strong> : chaque jour de série ajoute ${mr.streakPercent} % de pièces sur les missions, jusqu'à +${mr.streakMax} %. Rater un jour remet la série à zéro. Tu peux changer une mission par jour gratuitement (clic droit dans le menu).</p>
        <div class="tiers" style="margin-top:20px">${["easy", "medium", "hard", "weekly"].map((t) => `<div><h4>${tierNames[t]}</h4>${bundle(mr[t])}</div>`).join("")}</div>
        ${["easy", "medium", "hard", "weekly"].map((t) => `<h3>Missions ${tierNames[t].toLowerCase()}s possibles</h3>${chips(g.missions?.[t])}`).join("")}`,
      pieces: `<p>Les pièces se gagnent avec chaque nouveau succès (<strong>${eco.task}</strong> pour un progrès, <strong>${eco.goal}</strong> pour un objectif, <strong>${eco.challenge}</strong> pour un défi), les missions, la récompense du jour, le boss et l'hôtel des ventes.</p>
        <h3>Récompense du jour</h3>
        <p>Elle augmente chaque jour où tu la récupères avec <code class="cmd">/recompense</code>, jusqu'au 7e jour, puis le cycle recommence. Si tu manques un jour, tu repars du jour 1.</p>
        <ol class="days">${(g.daily || []).map((b, i) => `<li><strong>Jour ${i + 1}</strong>${bundle(b)}</li>`).join("")}</ol>
        <h3>À la boutique</h3>
        <p>Titres et cosmétiques avec un prix, et des emplacements de home en plus. Tout s'achète avec <code class="cmd">/boutique</code>.</p>`,
      parcours: `<p>${(g.paliers || []).length} paliers se débloquent tout seuls quand tu remplis leur condition. Ta progression est visible avec <code class="cmd">/parcours</code>.</p>
        <ol class="path">${(g.paliers || []).map((p) => `<li>${img(icon(p.icon), "", "")}<div><strong>${esc(p.name)}</strong><span class="muted small">${esc(p.condition)}</span><br>${bundle(p.rewards)}</div></li>`).join("")}</ol>`,
      titres: `<p>Ton titre s'affiche devant ton pseudo dans le chat. Choisis-le avec <code class="cmd">/titres</code>.</p>
        <div class="titles-grid">${(g.titles || []).map((t) => `<div><b>${t.html}</b><small>${esc(t.description)}${t.price ? `, ${num(t.price)} pièces` : ""}</small></div>`).join("")}</div>
        <h3>Couleurs de pseudo</h3>
        <div class="titles-grid">${(g.colors || []).map((c) => `<div><b>${c.html}</b><small>${c.price ? `${num(c.price)} pièces` : "Débloquée par un palier"}</small></div>`).join("")}</div>
        <h3>Traînées de particules</h3>
        ${chips((g.trails || []).map((t) => ({ icon: t.icon, name: t.name + (t.price ? ` (${num(t.price)} pièces)` : "") })))}`,
      saisons: `<p>Une saison dure un mois. Chaque nouveau succès rapporte des points (<strong>${g.season?.points?.task}</strong> pour un progrès, <strong>${g.season?.points?.goal}</strong> pour un objectif, <strong>${g.season?.points?.challenge}</strong> pour un défi), tout comme les missions et le boss. Le samedi et le dimanche, les points sont multipliés par <strong>${g.season?.weekendMultiplier}</strong>.</p>
        <h3>Récompenses de fin de saison</h3>
        <table class="cmds"><tbody>
          ${[["1", "1re place"], ["2", "2e place"], ["3", "3e place"], ["top10", "4e à 10e"], ["participation", "Participation"]].map(([k, l]) => `<tr><td><strong>${l}</strong></td><td>${bundle(g.season?.rewards?.[k])}</td></tr>`).join("")}
        </tbody></table>`,
      boss: `<p><strong>${esc(g.boss?.name)}</strong> apparaît ${sched.length ? sched.join(" et ") : "à des horaires annoncés en jeu"}. L'arène ouvre 15 minutes avant avec <code class="cmd">/boss</code>. Le combat dure ${g.boss?.duration} minutes : passé ce délai, le boss s'enfuit.</p>
        <p>Il repousse tout le monde d'une onde de choc, invoque des renforts, appelle la foudre et bondit sur le joueur le plus éloigné. Sous la moitié de sa vie, il attaque plus souvent. Ta récompense dépend des dégâts que tu infliges.</p>
        <table class="cmds"><tbody>
          ${[["participation", "Participation"], ["top1", "Meilleurs dégâts"], ["top2", "2e"], ["top3", "3e"], ["last-hit", "Coup de grâce"]].map(([k, l]) => `<tr><td><strong>${l}</strong></td><td>${bundle(g.boss?.rewards?.[k])}</td></tr>`).join("")}
        </tbody></table>`,
      equipes: `<p>Fonde une équipe avec <code class="cmd">/equipe creer &lt;nom&gt; &lt;tag&gt;</code> pour ${num(st.teamCost)} pièces, jusqu'à ${st.teamMax} membres. Ton équipe a un tag devant le pseudo, un chat privé, un home commun et sa place dans le classement des équipes. Les coéquipiers ne peuvent pas se blesser, sauf si le chef active le combat.</p>`,
      hdv: `<p>Prends un objet en main et tape <code class="cmd">/hdv vendre &lt;prix&gt;</code>. Tu peux avoir ${st.hdvMax} objets en vente en même temps. Une taxe de ${st.hdvTax} % est prélevée sur chaque vente. Les objets non vendus se récupèrent avec <code class="cmd">/hdv recuperer</code>.</p>`,
      teleport: `<p>Tu as ${st.homesBase} homes au départ. Les paliers et la boutique en donnent d'autres. Toutes les téléportations ont un court délai : ne bouge pas pendant le compte à rebours. <code class="cmd">/rtp</code> t'envoie dans un endroit sûr au hasard (une fois toutes les ${Math.round((st.rtpCooldown || 600) / 60)} minutes).</p>`,
      ressources: `<p>Le monde ressources est fait pour miner sans abîmer le monde principal. Il est remis à neuf le 1er de chaque mois : n'y construis rien d'important. Accès avec <code class="cmd">/ressources</code>.</p>`,
      succes: `<p>${totalAdv} succès au total, répartis en ${(g.categories || []).length} catégories. Les succès secrets restent cachés jusqu'à ce que quelqu'un les obtienne. Le pourcentage indique la part des joueurs qui l'ont.</p>
        ${(g.categories || []).map((c) => `<div class="cat-block"><h3>${img(icon(catIcon[c.id] || "book"), "", "")}${esc(catName(c))} <span class="muted small">${c.advancements.length} succès</span></h3>
          <div class="adv-list">${c.advancements.map((a) => { const secret = a.hidden && !a.rarity; return `<div class="adv ${a.frame}">${frame(secret ? { ...a, icon: "book" } : a, secret ? false : null, true)}
            <div class="txt"><strong>${secret ? "Succès secret" : esc(advTitle(a))}</strong><span class="desc">${secret ? "À découvrir en jouant." : esc(advDesc(a))}</span>
            <span class="when">${String(a.rarity).replace(".", ",")} % des joueurs</span></div></div>`; }).join("")}</div></div>`).join("")}`,
    };

    app.innerHTML = `
      <div class="page-head"><div class="wrap"><h1>Guide du serveur</h1><p>Tout ce qu'il faut savoir pour jouer sur LyonMC. Les chiffres viennent directement du serveur.</p></div></div>
      <div class="wrap guide">
        <nav class="toc" aria-label="Sommaire">${SECTIONS.map(([id, t]) => `<a href="#${id}">${t}</a>`).join("")}</nav>
        <div>${SECTIONS.map(([id, t]) => `<section id="${id}"><h2>${t}</h2>${sec[id]}</section>`).join("")}</div>
      </div>`;

    const links = [...app.querySelectorAll(".toc a")];
    const obs = new IntersectionObserver((entries) => {
      entries.forEach((e) => {
        if (e.isIntersecting) links.forEach((l) => l.classList.toggle("active", l.getAttribute("href") === `#${e.target.id}`));
      });
    }, { rootMargin: "-20% 0px -70% 0px" });
    app.querySelectorAll(".guide section").forEach((s) => obs.observe(s));
    if (anchor) document.getElementById(anchor)?.scrollIntoView();
  }

  // ───────────── Carte (Dynmap intégrée) ─────────────
  function carte() {
    setTitle("Carte");
    if (app.querySelector("iframe.map-frame")) return; // déjà affichée, on ne recharge pas la carte
    const src = "/dynmap/" + location.search;
    app.innerHTML = `
      <div class="map-bar"><div class="wrap">
        <h1>Carte en direct</h1>
        <span class="muted small">Le monde du serveur, mis à jour en continu.</span>
        <a class="map-full" href="${esc(src)}" target="_blank" rel="noopener">Ouvrir en plein écran</a>
      </div></div>
      <iframe class="map-frame" src="${esc(src)}" title="Carte Dynmap du serveur LyonMC" allow="fullscreen"></iframe>`;
  }

  // ───────────── Routage ─────────────
  async function render(scroll = true) {
    const path = decodeURI(location.pathname).replace(/\/+$/, "") || "/";
    setNav(path);
    const parts = path.split("/").filter(Boolean);
    document.body.classList.toggle("is-map", parts[0] === "carte");
    try {
      if (path === "/") await home();
      else if (parts[0] === "classements") await boards(parts[1]);
      else if (parts[0] === "joueur" && parts[1]) await player(parts[1], parts[2]);
      else if (parts[0] === "guide") await guide(location.hash.slice(1));
      else if (parts[0] === "carte") carte();
      else {
        setTitle("Page introuvable");
        app.innerHTML = `<div class="page-head"><div class="wrap"><h1>Page introuvable</h1><p>Cette page n'existe pas. <a href="/" data-link>Retour à l'accueil</a></p></div></div>`;
      }
    } catch (e) {
      console.error(e);
      app.innerHTML = `<div class="page-head"><div class="wrap"><h1>Erreur de chargement</h1><p>Recharge la page dans quelques secondes.</p></div></div>`;
    }
    if (scroll && !location.hash) window.scrollTo(0, 0);
  }
  function navigate(url, keepScroll = false) {
    history.pushState({}, "", url);
    render(!keepScroll);
  }
  document.addEventListener("click", (e) => {
    const a = e.target.closest("a[data-link]");
    if (!a || e.metaKey || e.ctrlKey || e.shiftKey || a.target) return;
    e.preventDefault();
    navigate(a.getAttribute("href"));
  });
  window.addEventListener("popstate", () => render(false));

  (async () => {
    await loadFr();
    refreshPill();
    setInterval(refreshPill, 30000);
    render();
  })();
})();
