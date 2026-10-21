/* lyonmc.fr — site du serveur. Les données viennent du plugin (dossier /data). */
(() => {
  "use strict";

  const app = document.getElementById("app");
  const tipEl = document.getElementById("tip");
  const cache = {};
  let FR = {};

  // ───────────── Outils ─────────────
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const num = (n) => Number(n || 0).toLocaleString("fr-FR");
  // Le jour est ajouté à l'adresse des skins : les navigateurs reprennent le skin à jour au moins une fois par jour
  const DAY = new Date().toISOString().slice(0, 10).replace(/-/g, "");
  const face = (uuid, size = 64) => `https://mc-heads.net/avatar/${uuid}/${size}?d=${DAY}`;
  const body = (uuid) => `https://mc-heads.net/body/${uuid}/240?d=${DAY}`;
  const icon = (id) => `/img/icons/${id}.png`;
  const img = (src, cls = "", alt = "") => `<img class="${cls}" src="${src}" alt="${esc(alt)}" loading="lazy" onerror="this.style.visibility='hidden'">`;
  const ico = (id, cls = "") => `<span class="ico ${cls}">${img(icon(id))}</span>`;
  const tr = (key, fallback) => (key && FR[key]) || fallback || key || "";
  const plink = (name, cls = "") => `<a class="${cls}" href="/joueur/${encodeURIComponent(name)}" data-link>${esc(name)}</a>`;
  const pct = (r) => `${String(r ?? 0).replace(".", ",")} %`;
  /** Icône du serveur (celle de la liste des serveurs), via l'API mcstatus.io. */
  const serverIcon = (ip) => `https://api.mcstatus.io/v2/icon/${encodeURIComponent(ip || "lyonmc.fr")}`;

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
  const TZ = "Europe/Paris";
  const fmtDay = new Intl.DateTimeFormat("fr-FR", { weekday: "long", day: "numeric", month: "long", hour: "2-digit", minute: "2-digit", timeZone: TZ });
  const fmtDate = new Intl.DateTimeFormat("fr-FR", { weekday: "long", day: "numeric", month: "long", timeZone: TZ });
  const fmtClock = new Intl.DateTimeFormat("fr-FR", { hour: "2-digit", minute: "2-digit", timeZone: TZ });
  const fmtShort = new Intl.DateTimeFormat("fr-FR", { day: "2-digit", month: "2-digit", timeZone: TZ });
  const fmtMonth = new Intl.DateTimeFormat("fr-FR", { month: "long", year: "numeric", timeZone: "UTC" });

  /** Heure, jour de la semaine, jour et mois à Lyon. */
  function paris(date = new Date()) {
    const parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", { timeZone: TZ, hour12: false, weekday: "short", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })
      .formatToParts(date).map((p) => [p.type, p.value]));
    return { y: +parts.year, mo: +parts.month, d: +parts.day, h: +parts.hour % 24, mi: +parts.minute, wd: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].indexOf(parts.weekday) };
  }
  /** Date (en ms) correspondant à une heure donnée à Lyon. */
  function parisTime(y, mo, d, h = 0) {
    const guess = Date.UTC(y, mo - 1, d, h);
    const p = paris(new Date(guess));
    return guess - (Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi) - guess);
  }

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
    (b.items || []).forEach((it) => p.push(`${it.amount}× ${esc(tr(it.key, it.id))}`));
    return `<span class="reward-line">${p.map((x) => `<span class="r">${x}</span>`).join("")}</span>`;
  }
  const advTitle = (a) => (a.titleKey ? tr(a.titleKey, a.title) : a.title);
  const advDesc = (a) => (a.descKey ? tr(a.descKey, a.desc) : a.desc);
  const catIcon = { story: "grass_block", nether: "netherrack", end: "end_stone", adventure: "filled_map", husbandry: "hay_block", lyonmc: "nether_star" };
  const catName = (c) => (c.id === "lyonmc" ? c.name : tr(`advancements.${c.id}.root.title`, c.name));
  function advIndex(contenu) {
    const idx = {};
    (contenu?.categories || []).forEach((c) => c.advancements.forEach((a) => (idx[a.key] = { ...a, cat: c.id })));
    return idx;
  }
  function frame(a, state) {
    return `<span class="frame ${a.frame} ${state || ""}">${img(icon(a.icon))}</span>`;
  }

  // ───────────── Info-bulles ─────────────
  function tipHtml(name, lines = [], action = "") {
    return `<b>${name}</b>${lines.map((l) => `<span class="l">${l}</span>`).join("")}${action ? `<span class="a">${action}</span>` : ""}`;
  }
  const tipAttr = (html) => `data-tip="${esc(html)}" tabindex="0"`;
  function placeTip(x, y) {
    const r = tipEl.getBoundingClientRect();
    let left = x + 14, top = y - r.height - 10;
    if (left + r.width > innerWidth - 8) left = x - r.width - 14;
    if (top < 8) top = y + 18;
    tipEl.style.left = `${Math.max(8, left)}px`;
    tipEl.style.top = `${top}px`;
  }
  function showTip(t, x, y) {
    tipEl.innerHTML = t.dataset.tip;
    tipEl.hidden = false;
    placeTip(x, y);
  }
  document.addEventListener("mouseover", (e) => { const t = e.target.closest("[data-tip]"); if (t) showTip(t, e.clientX, e.clientY); });
  document.addEventListener("mousemove", (e) => { if (!tipEl.hidden) placeTip(e.clientX, e.clientY); });
  document.addEventListener("mouseout", (e) => { if (e.target.closest("[data-tip]")) tipEl.hidden = true; });
  document.addEventListener("focusin", (e) => { const t = e.target.closest("[data-tip]"); if (t) { const r = t.getBoundingClientRect(); showTip(t, r.right, r.top + 4); } });
  document.addEventListener("focusout", () => (tipEl.hidden = true));

  function setTitle(t) {
    document.title = t ? `${t} · LyonMC` : "LyonMC · Serveur Minecraft survie 1.21.8";
  }
  function setNav(path) {
    document.querySelectorAll(".nav a").forEach((a) => {
      const href = a.getAttribute("href");
      const on = href === "/" ? path === "/" : path === href || path.startsWith(href + "/") || (href === "/joueurs" && path.startsWith("/joueur/"));
      if (on) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
    });
  }

  async function refreshOnline() {
    const s = await data("serveur");
    const el = document.getElementById("online");
    if (!s) { el.innerHTML = `<span class="dot"></span><span>Serveur injoignable</span>`; return; }
    el.innerHTML = `<span class="dot ${s.online > 0 ? "on" : ""}"></span><span>${s.online} en ligne</span>`;
    const d = document.getElementById("discord-link");
    if (s.discord) { d.href = s.discord; d.hidden = false; }
  }

  // ───────────── Le ciel suit l'heure de Lyon ─────────────
  const SKIES = {
    nuit:       { top: "#0a0e1a", bottom: "#1d2646", far: "#151d33", hill: "#0f1a16", light: .55, stars: 1,   cloud: .07, ink: "#e8ecf3" },
    aube:       { top: "#2c3964", bottom: "#c48d72", far: "#3d4166", hill: "#223523", light: .8,  stars: .2,  cloud: .35, ink: "#ffffff" },
    jour:       { top: "#4a7dcc", bottom: "#a7c2e8", far: "#7d9cc4", hill: "#3e6c34", light: 1,   stars: 0,   cloud: .55, ink: "#0f1a2e" },
    crepuscule: { top: "#23264f", bottom: "#c27759", far: "#3a3157", hill: "#1f2f1f", light: .72, stars: .4,  cloud: .3,  ink: "#ffffff" },
  };
  function skyPhase() {
    const forced = new URLSearchParams(location.search).get("ciel");
    if (forced && SKIES[forced]) return forced;
    const { h, mi } = paris();
    const t = h + mi / 60;
    if (t >= 6 && t < 8) return "aube";
    if (t >= 8 && t < 18.5) return "jour";
    if (t >= 18.5 && t < 21.5) return "crepuscule";
    return "nuit";
  }
  function applySky(el) {
    const phase = skyPhase(), k = SKIES[phase];
    Object.entries({ "--sky-top": k.top, "--sky-bottom": k.bottom, "--hill-far": k.far, "--hill": k.hill, "--light": k.light, "--stars": k.stars, "--cloud": k.cloud, "--hero-ink": k.ink })
      .forEach(([p, v]) => el.style.setProperty(p, v));
    return phase;
  }
  function buildSky(scene) {
    const phase = applySky(scene);
    let seed = 7;
    const rand = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);

    // Soleil le jour, lune la nuit, dans la moitié droite du ciel
    const { h, mi } = paris();
    const t = h + mi / 60;
    const day = phase === "jour" || phase === "aube" || (phase === "crepuscule" && t < 20.5);
    let p = Math.min(1, Math.max(0, ((t - (day ? 6 : 20.5) + 24) % 24) / (day ? 14.5 : 9.5)));
    if (new URLSearchParams(location.search).get("ciel")) p = { jour: .4, nuit: .35, aube: .08, crepuscule: .9 }[phase];
    const cel = scene.querySelector(".celestial");
    cel.src = day ? "/img/sun.png" : "/img/moon.png";
    cel.style.left = `${52 + p * 42}%`;
    cel.style.top = `${52 - Math.sin(Math.PI * p) * 36}%`;

    scene.querySelector(".stars").innerHTML = Array.from({ length: 70 }, () =>
      `<i style="left:${(rand() * 100).toFixed(2)}%;top:${(rand() * 100).toFixed(2)}%;opacity:${(.3 + rand() * .6).toFixed(2)}"></i>`).join("");
    scene.querySelector(".clouds").innerHTML = Array.from({ length: 4 }, (_, i) =>
      `<div class="cloud" style="left:${(i * 26 + rand() * 12).toFixed(0)}%;top:${(8 + rand() * 22).toFixed(0)}%;width:${(110 + rand() * 150).toFixed(0)}px"></div>`).join("");

    const W = 1600, H = 190;
    const ridge = (base, amp, s) => {
      let r = s, y = base, d = `M0 ${H}`;
      const rr = () => ((r = (r * 48271) % 2147483647) / 2147483647);
      for (let x = 0; x <= W; x += 32) {
        y = Math.max(8, Math.min(H - 16, y + Math.round((rr() - .5) * amp / 16) * 16));
        d += ` L${x} ${y} L${x + 32} ${y}`;
      }
      return d + ` L${W} ${H} Z`;
    };
    scene.querySelector(".hills").innerHTML = `<path class="far" d="${ridge(70, 64, 11)}"/><path class="near" d="${ridge(126, 48, 29)}"/>`;
  }

  // ───────────── Accueil ─────────────
  function seasonBounds(season) {
    if (!season?.endsAt) return null;
    const end = season.endsAt;
    const p = paris(new Date(end - 60000));
    const start = parisTime(p.y, p.mo, 1, 0);
    return { progress: Math.min(1, Math.max(0, (Date.now() - start) / (end - start))) };
  }

  function feed(joueurs, idx, limit = 12) {
    const all = [];
    Object.values(joueurs || {}).forEach((p) => (p.done || []).forEach(([k, t]) => {
      const a = idx[k];
      if (a && !k.endsWith("/root") && k !== "lyonmc:root") all.push({ p, a, t });
    }));
    all.sort((x, y) => y.t - x.t);
    const verb = { task: "a réalisé le progrès", goal: "a atteint l'objectif", challenge: "a relevé le défi" };
    return all.slice(0, limit).map(({ p, a, t }) => {
      const when = Date.now() - t < 86400000 ? fmtClock.format(t) : fmtShort.format(t);
      const tip = tipHtml(esc(advTitle(a)), [esc(advDesc(a)), `${pct(a.rarity)} des joueurs l'ont`]);
      return `<li><time datetime="${new Date(t).toISOString()}">${when}</time>${img(face(p.uuid, 32))}
        <p>${plink(p.name, "who")} <span class="verb">${verb[a.frame] || verb.task}</span> <span class="fadv ${a.frame}" ${tipAttr(tip)}>${esc(advTitle(a))}</span></p></li>`;
    }).join("");
  }

  function agenda(s) {
    const items = [];
    const now = Date.now();
    const boss = s?.boss || {};
    const bossName = esc(boss.name || "Le Colosse");
    if (boss.active) items.push({ t: now, ic: "wither_skeleton_skull", title: `${bossName} est en combat`, sub: "Tape /boss en jeu pour rejoindre l'arène.", now: true });
    else if (boss.next) items.push({ t: boss.next, ic: "wither_skeleton_skull", title: `${bossName} apparaît`, sub: fmtDay.format(boss.next) });
    const season = s?.season;
    const mult = String(season?.multiplier ?? 2).replace(/\.0$/, "");
    if (season?.weekend) items.push({ t: now, ic: "experience_bottle", title: `Points de saison ×${mult}`, sub: "Tout le week-end, jusqu'à dimanche minuit.", now: true });
    else {
      const p = paris();
      const sat = parisTime(p.y, p.mo, p.d + ((6 - p.wd + 7) % 7 || 7), 0);
      items.push({ t: sat, ic: "experience_bottle", title: `Points de saison ×${mult}`, sub: `Le week-end, à partir de ${fmtDate.format(sat)}` });
    }
    const sb = seasonBounds(season);
    if (season?.endsAt) items.push({ t: season.endsAt, ic: "nether_star", title: `Fin de la ${esc(season.name)}`, sub: "Les trois premiers gagnent titres et pièces.", bar: sb?.progress });
    const p = paris();
    const reset = parisTime(p.mo === 12 ? p.y + 1 : p.y, p.mo === 12 ? 1 : p.mo + 1, 1, 0);
    items.push({ t: reset, ic: "iron_pickaxe", title: "Monde ressources remis à neuf", sub: fmtDate.format(reset) });
    return items.sort((a, b) => a.t - b.t).map((it) => `<li class="card">${ico(it.ic, "sm")}
      <div><strong>${it.title}</strong><small>${it.sub}</small></div>
      <span class="in ${it.now ? "now" : ""}">${it.now ? "En cours" : `dans ${until(it.t)}`}</span>
      ${it.bar != null ? `<span class="bar" title="Avancement de la saison"><i style="width:${(it.bar * 100).toFixed(1)}%"></i></span>` : ""}</li>`).join("");
  }

  async function home() {
    setTitle("");
    const [s, c, j, contenu] = await Promise.all([data("serveur"), data("classements"), data("joueurs"), data("contenu", false)]);
    const idx = advIndex(contenu);
    const top = c?.achievements || [];
    const ip = s?.ip || "lyonmc.fr";
    const totals = s?.totals || {};
    const custom = (contenu?.categories || []).find((x) => x.id === "lyonmc")?.advancements.length || 22;

    const step = (p, cls, n) => p
      ? `<a class="step ${cls}" href="/joueur/${encodeURIComponent(p.name)}" data-link>
           <span class="nametag"><span>${esc(p.name)}</span><span class="val">★ ${p.count}</span></span>
           ${img(body(p.uuid), "skin", "")}<span class="block"><span class="place">${n}</span></span></a>`
      : `<div class="step ${cls}"><span class="nametag">Place libre</span>
           <img class="skin empty" src="https://mc-heads.net/body/MHF_Steve/240" alt=""><span class="block"><span class="place">${n}</span></span></div>`;
    const online = (s?.players || []).slice(0, 10).map((p) => img(face(p.uuid, 32), "", p.name)).join("");
    const stats = [
      ["emerald", s?.online ?? 0, `joueur${(s?.online ?? 0) > 1 ? "s" : ""} en ligne`],
      ["name_tag", totals.profiles, "joueurs déjà venus"],
      ["knowledge_book", totals.unlocked, "succès débloqués"],
      ["white_banner", totals.teams, `équipe${totals.teams > 1 ? "s" : ""}`],
      ["gold_ingot", totals.listings, "objets en vente"],
    ];
    const sTop = (c?.season || []).slice(0, 5);

    app.innerHTML = `
      <section class="scene" aria-label="Accueil">
        <div class="stars" aria-hidden="true"></div>
        <img class="celestial" alt="" aria-hidden="true">
        <div class="clouds" aria-hidden="true"></div>
        <svg class="hills" viewBox="0 0 1600 190" preserveAspectRatio="none" aria-hidden="true"></svg>
        <div class="ground" aria-hidden="true"></div>
        <div class="wrap">
          <div class="hero-copy">
            <h1 class="wordmark">LyonMC</h1>
            <p class="hero-lead">Survie Java 1.21.8. ${num(totals.achievements || 146)} succès à obtenir, des missions chaque jour, un boss deux fois par semaine et un classement qui repart chaque mois.</p>
            <div class="join">
              <p class="label">Adresse du serveur</p>
              <div class="row"><img class="srv-icon" src="${serverIcon(ip)}" alt="" onerror="this.onerror=null;this.src='/img/grass_side.png'"><span class="field">${esc(ip)}</span><button class="btn" type="button" id="copy">Copier</button></div>
              <div class="meta"><span class="dot ${s?.online ? "on" : ""}"></span><span>${s ? `${s.online} en ligne sur ${s.max}` : "Statut indisponible"}</span><span class="faces">${online}</span></div>
            </div>
          </div>
          <div class="podium" aria-label="Podium des succès">${step(top[1], "second", 2)}${step(top[0], "first", 1)}${step(top[2], "third", 3)}</div>
        </div>
      </section>

      <div class="wrap"><div class="stats-strip">
        ${stats.map(([ic, n, label]) => `<div class="card stat">${ico(ic)}<div><b>${num(n)}</b><span>${label}</span></div></div>`).join("")}
      </div></div>

      <section class="section"><div class="wrap">
        <h2>En ce moment sur le serveur</h2>
        <p class="intro">Les derniers succès obtenus et les prochains rendez-vous. Survole un succès pour voir ce qu'il demande.</p>
        <div class="live">
          <div>
            <div class="panel-h"><h3>Derniers exploits</h3><span class="muted">mis à jour chaque minute</span></div>
            <div class="card">${j ? `<ul class="feed">${feed(j, idx) || `<li><span></span><span></span><p class="muted">Aucun succès pour l'instant.</p></li>`}</ul>` : `<p class="empty">Les données du serveur arrivent dans une minute.</p>`}</div>
          </div>
          <div>
            <div class="panel-h"><h3>À venir</h3></div>
            <ul class="agenda">${agenda(s)}</ul>
            <div class="mini-board">
              <div class="panel-h"><h3>${esc(s?.season?.name || "Saison en cours")}</h3><a href="/classements/saison" data-link class="muted">Tout le classement</a></div>
              <ol>${sTop.length ? sTop.map((p, i) => `<li><span class="r">${i + 1}</span>${img(face(p.uuid, 32))}${plink(p.name)}<span class="pts">${num(p.points)} pts</span></li>`).join("") : `<li class="muted">Personne n'a encore marqué de points.</li>`}</ol>
            </div>
          </div>
        </div>
      </div></section>

      <section class="section"><div class="wrap">
        <h2>Ce qui t'attend</h2>
        <p class="intro">Une survie classique, sans avantage à acheter, avec de quoi se fixer des objectifs chaque jour.</p>
        <div class="features">
          ${[
            ["knowledge_book", "Succès", `${num(totals.achievements || 146)} succès, dont ${custom} propres au serveur. Chacun rapporte des pièces et des points de saison.`],
            ["writable_book", "Missions", "Trois missions par jour et trois par semaine. Les finir toutes fait monter ta série et ton bonus."],
            ["clock", "Saisons", "Un classement qui repart chaque mois. Le week-end, les points comptent double."],
            ["mace", "Boss mondial", "Le Colosse apparaît deux fois par semaine. Ta récompense dépend des dégâts que tu infliges."],
            ["name_tag", "Équipes", "Un tag devant ton pseudo, un chat privé et un home commun."],
            ["emerald", "Hôtel des ventes", "Vends et achète entre joueurs avec les pièces du serveur."],
          ].map(([ic, t, d]) => `<div class="feature">${ico(ic)}<div><h3>${t}</h3><p>${d}</p></div></div>`).join("")}
        </div>
      </div></section>

      <section class="section"><div class="wrap">
        <h2>Rejoindre le serveur</h2>
        <p class="intro">Il te faut Minecraft Java en version 1.21.8.</p>
        <ol class="steps">
          <li class="card"><strong>Lance Minecraft en 1.21.8</strong><p>Dans le launcher, choisis la dernière version.</p></li>
          <li class="card"><strong>Ajoute le serveur</strong><p>Multijoueur, Ajouter un serveur, puis l'adresse <code class="cmd">${esc(ip)}</code>.</p></li>
          <li class="card"><strong>Tape <code class="cmd">/menu</code></strong><p>Tout est là : missions, récompense du jour, classements et boutique.</p></li>
        </ol>
      </div></section>`;

    buildSky(app.querySelector(".scene"));
    const btn = document.getElementById("copy");
    btn.addEventListener("click", async () => {
      try { await navigator.clipboard.writeText(ip); btn.textContent = "Copiée"; }
      catch (e) { btn.textContent = ip; }
      setTimeout(() => (btn.textContent = "Copier"), 2000);
    });
  }

  // ───────────── Classements ─────────────
  const BOARDS = [
    ["achievements", "Succès", "", "knowledge_book"],
    ["season", "Saison", "saison", "nether_star"],
    ["teams", "Équipes", "equipes", "white_banner"],
    ["missions", "Missions", "missions", "writable_book"],
    ["richest", "Fortune", "fortune", "gold_ingot"],
  ];
  function pastSeasons(list) {
    if (!list || !list.length) return "";
    return `<div class="mini-board past"><div class="panel-h"><h3>Saisons passées</h3></div><ol>${list.slice().reverse().map((h) => {
      const [y, m] = String(h.month).split("-").map(Number);
      const label = y && m ? fmtMonth.format(new Date(Date.UTC(y, m - 1, 1))) : esc(h.month);
      return `<li style="align-items:flex-start"><span style="min-width:170px"><strong>Saison ${esc(h.id)}</strong><br><span class="muted small">${label}</span></span>
        <span>${(h.podium || []).map((p) => `<span class="r" style="display:inline-block">${esc(p.pos)}</span> ${plink(p.name)} <span class="muted small">${num(p.points)} pts</span>`).join("<br>")}</span></li>`;
    }).join("")}</ol></div>`;
  }

  async function boards(slug) {
    setTitle("Classements");
    const [c, s] = await Promise.all([data("classements"), data("serveur")]);
    const cur = BOARDS.find((b) => b[2] === (slug || "")) || BOARDS[0];
    const key = cur[0];
    const total = s?.totals?.achievements || 0;
    const rows = c?.[key] || [];
    const pos = (i) => i < 3 ? `<span class="pos b b${i + 1}">${i + 1}</span>` : `<span class="pos">${i + 1}</span>`;
    const who = (p) => `<span class="who"><strong>${esc(p.name)}</strong>${p.team ? `<span class="tag">${esc(p.team)}</span>` : ""}<span class="t">${p.grade ? `<span class="grade">${p.grade}</span>` : ""}${p.title ? `${p.grade ? " · " : ""}${p.title}` : ""}</span></span>`;
    const val = {
      achievements: (p) => `${p.count}<small> / ${total}</small><span class="meter"><i style="width:${total ? (p.count / total) * 100 : 0}%"></i></span>`,
      season: (p) => `${num(p.points)}<small> pts</small>`,
      missions: (p) => `${num(p.missions)}<small> missions</small><br><small>série record ${p.streak} j</small>`,
      richest: (p) => `${num(p.coins)}<small> pièces</small>`,
    };
    let list;
    if (!c) list = `<p class="empty">Les classements arrivent dans une minute.</p>`;
    else if (!rows.length) list = `<p class="empty">${{ season: "Personne n'a encore marqué de points cette saison. Chaque succès, mission ou boss en rapporte.", teams: "Aucune équipe pour l'instant. Fondes-en une en jeu avec /equipe creer.", missions: "Personne n'a encore terminé de mission. Tape /missions en jeu pour voir les tiennes." }[key] || "Personne ici pour l'instant."}</p>`;
    else if (key === "teams") list = rows.map((t, i) => `<div class="rowp">${pos(i)}${ico("white_banner", "sm")}
        <span class="who"><strong>${esc(t.name)}</strong><span class="tag" style="color:${esc(t.color)}">${esc(t.tag)}</span><span class="t muted">${t.members.map(esc).join(", ")}</span></span>
        <span class="val">${num(t.achievements)}<small> succès</small><br><small>${num(t.season)} pts de saison</small></span></div>`).join("");
    else list = rows.map((p, i) => `<a class="rowp" href="/joueur/${encodeURIComponent(p.name)}" data-link>${pos(i)}${img(face(p.uuid, 64), "face", "")}${who(p)}<span class="val">${val[key](p)}</span></a>`).join("");

    const intro = {
      achievements: `Les joueurs qui ont débloqué le plus de succès sur les ${total}.`,
      season: `${esc(s?.season?.name || "Saison en cours")}, fin dans ${s?.season ? until(s.season.endsAt) : "?"}. Les trois premiers gagnent des titres et des pièces.`,
      teams: "Les équipes, classées par succès cumulés de leurs membres.",
      missions: "Les joueurs les plus réguliers sur les missions du jour et de la semaine.",
      richest: "Les plus grosses fortunes en pièces.",
    }[key];

    app.innerHTML = `
      <div class="page-head"><div class="wrap"><h1>Classements</h1><p>${intro}</p></div></div>
      <div class="wrap" style="padding-bottom:72px">
        <div class="tabs" role="tablist">${BOARDS.map(([k, label, path, ic]) => `<button role="tab" aria-selected="${k === key}" data-tab="${path}">${img(icon(ic))}${label}</button>`).join("")}</div>
        <form class="search" id="search"><label class="visually-hidden" for="q">Chercher un joueur</label>
          <input id="q" placeholder="Chercher un joueur" autocomplete="off" list="names"><button class="btn ghost">Voir le profil</button>
          <datalist id="names">${(c?.achievements || []).map((p) => `<option value="${esc(p.name)}">`).join("")}</datalist></form>
        <div class="card rows">${list}</div>
        ${key === "season" ? pastSeasons(c?.history) : ""}
      </div>`;
    app.querySelectorAll("[data-tab]").forEach((b) => b.addEventListener("click", () => navigate(b.dataset.tab ? `/classements/${b.dataset.tab}` : "/classements", true)));
    document.getElementById("search").addEventListener("submit", (e) => {
      e.preventDefault();
      const q = document.getElementById("q").value.trim();
      if (q) navigate(`/joueur/${encodeURIComponent(q)}`);
    });
  }

  // ───────────── Joueurs ─────────────
  async function players() {
    setTitle("Joueurs");
    const j = await data("joueurs");
    const list = Object.values(j || {});
    app.innerHTML = `
      <div class="page-head"><div class="wrap"><h1>Joueurs</h1><p>${list.length ? `${num(list.length)} joueurs sont déjà venus sur LyonMC.` : "La liste arrive dans une minute."}</p></div></div>
      <div class="wrap" style="padding-block:26px 72px">
        <div class="search"><label class="visually-hidden" for="pq">Chercher un joueur</label><input id="pq" placeholder="Chercher un joueur" autocomplete="off">
          <label class="visually-hidden" for="sort">Trier</label>
          <select id="sort"><option value="count">Plus de succès</option><option value="recent">Vus récemment</option><option value="name">Ordre alphabétique</option><option value="playtime">Temps de jeu</option></select></div>
        <div class="players" id="plist"></div>
      </div>`;
    const draw = () => {
      const q = document.getElementById("pq").value.trim().toLowerCase();
      const sort = document.getElementById("sort").value;
      const sorted = list.filter((p) => !q || p.name.toLowerCase().includes(q)).sort((a, b) =>
        sort === "name" ? a.name.localeCompare(b.name, "fr")
          : sort === "recent" ? (b.online - a.online) || (b.lastSeen || 0) - (a.lastSeen || 0)
            : sort === "playtime" ? (b.playtime || 0) - (a.playtime || 0)
              : b.count - a.count);
      document.getElementById("plist").innerHTML = sorted.map((p) => `<a class="card pcard" href="/joueur/${encodeURIComponent(p.name)}" data-link>${img(face(p.uuid, 64))}
        <div><strong>${p.online ? `<span class="dot on" title="En ligne"></span>` : ""}${esc(p.name)}</strong>
        <span>${p.count} succès${p.rank ? `, ${p.rank === 1 ? "1er" : p.rank + "e"}` : ""}</span>${p.grade || p.title ? `<span>${p.grade ? `<span class="grade">${p.grade}</span>` : ""}${p.grade && p.title ? " · " : ""}${p.title || ""}</span>` : ""}</div></a>`).join("") || `<p class="empty">Aucun joueur ne correspond.</p>`;
    };
    document.getElementById("pq").addEventListener("input", draw);
    document.getElementById("sort").addEventListener("change", draw);
    draw();
  }

  // ───────────── Profil joueur ─────────────
  async function player(name, filter) {
    const [j, contenu, s] = await Promise.all([data("joueurs"), data("contenu", false), data("serveur")]);
    const p = j?.[name.toLowerCase()];
    if (!p) {
      setTitle("Joueur introuvable");
      app.innerHTML = `<div class="page-head"><div class="wrap"><h1>Joueur introuvable</h1>
        <p>Aucun joueur nommé « ${esc(name)} » ne s'est encore connecté sur LyonMC. Vérifie l'orthographe du pseudo.</p></div></div>
        <div class="wrap section"><a class="btn ghost" href="/joueurs" data-link>Voir tous les joueurs</a></div>`;
      return;
    }
    setTitle(p.name);
    if (name !== p.name) history.replaceState({}, "", location.pathname.replace(/\/joueur\/[^/]+/, `/joueur/${encodeURIComponent(p.name)}`));
    const total = s?.totals?.achievements || 0;
    const done = new Map((p.done || []).map(([k, t]) => [k, t]));
    const idx = advIndex(contenu);
    const status = p.online ? `<span class="dot on"></span>En ligne` : p.lastSeen ? `Vu ${ago(p.lastSeen)}` : "";
    const stat = (label, value) => `<div><dt>${label}</dt><dd>${value}</dd></div>`;
    const cats = (contenu?.categories || []).map((c) => {
      const n = p.categories?.[c.id] || 0, t = c.advancements.length;
      return `<div class="cat-row">${img(icon(catIcon[c.id] || "book"))}<span>${esc(catName(c))}</span>
        <span class="meter"><i style="width:${t ? (n / t) * 100 : 0}%"></i></span><span class="n">${n} / ${t}</span></div>`;
    }).join("");

    const mode = filter === "manquants" ? "missing" : "done";
    const list = mode === "done"
      ? [...done.entries()].map(([k, t]) => ({ a: idx[k], t })).filter((x) => x.a)
      : Object.values(idx).filter((a) => !done.has(a.key)).sort((x, y) => (y.rarity || 0) - (x.rarity || 0)).map((a) => ({ a }));
    const PAGE = 24;
    const advHtml = list.map(({ a, t }, i) => {
      const secret = mode === "missing" && a.hidden && !a.rarity;
      return `<div class="card adv ${a.frame} ${mode === "missing" ? "missing" : ""}"${i >= PAGE ? " hidden" : ""}>
        ${frame(secret ? { ...a, icon: "book" } : a, mode === "done" ? "done" : "locked")}
        <div class="txt"><strong>${secret ? "Succès secret" : esc(advTitle(a))}</strong>
          <span class="desc">${secret ? "À découvrir en jouant." : esc(advDesc(a))}</span>
          <span class="when">${t ? ago(t) : `${pct(a.rarity)} des joueurs l'ont`}</span></div></div>`;
    }).join("");

    app.innerHTML = `
      <div class="wrap profile">
        <aside class="card profile-side">
          <div class="sky ${p.rank === 1 ? "gold" : ""}">${img(body(p.uuid), "body", p.name)}</div>
          <div class="info">
            <h1>${esc(p.name)}</h1>
            ${p.grade ? `<p class="grade-line"><span class="grade">${p.grade}</span></p>` : ""}
            ${p.title ? `<p class="title">${p.title}</p>` : ""}
            ${p.teamInfo ? `<p class="small muted" style="margin:0">Équipe <strong style="color:var(--ink)">${esc(p.teamInfo.name)}</strong><span class="tag" style="color:${esc(p.teamInfo.color)}">${esc(p.teamInfo.tag)}</span></p>` : ""}
            <p class="seen">${status}</p>
          </div>
        </aside>
        <div>
          <div class="count-line"><span class="big">${p.count}</span><span class="of">succès sur ${total}${p.rank ? `, ${p.rank === 1 ? "1er" : p.rank + "e"} du serveur` : ""}</span></div>
          <div class="progress" role="img" aria-label="${p.count} succès sur ${total}"><i style="width:${total ? (p.count / total) * 100 : 0}%"></i></div>
          <dl class="card statgrid">
            ${stat("Saison", p.seasonPoints ? `${num(p.seasonPoints)} pts${p.seasonRank ? ` <small class="muted">#${p.seasonRank}</small>` : ""}` : "0 pt")}
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
            <button role="tab" aria-selected="${mode === "missing"}" data-f="manquants">À obtenir (${Math.max(0, total - p.count)})</button>
          </div>
          <div class="adv-list">${advHtml || `<p class="empty">${mode === "done" ? "Aucun succès pour l'instant." : "Tous les succès sont débloqués."}</p>`}</div>
          ${list.length > PAGE ? `<button type="button" class="btn ghost more" id="more">Afficher les ${list.length - PAGE} autres</button>` : ""}
        </div>
      </div>`;
    applySky(app.querySelector(".profile-side .sky"));
    document.getElementById("more")?.addEventListener("click", (e) => {
      app.querySelectorAll(".adv[hidden]").forEach((el) => (el.hidden = false));
      e.currentTarget.remove();
    });
    app.querySelectorAll("[data-f]").forEach((b) => b.addEventListener("click", () =>
      navigate(`/joueur/${encodeURIComponent(p.name)}${b.dataset.f ? "/" + b.dataset.f : ""}`, true)));
  }

  // ───────────── Guide ─────────────
  const ACCENT = "#e8c872";
  const HUB = {
    title: "LyonMC",
    rows: 6,
    items: {
      4: { head: true, name: "Ton profil", lore: ["Rang  #4 sur 30", "★ Succès  110 / 146", "⛁ Pièces  12 480", "✦ Saison  640 pts (#3)", "✔ Série de missions  6 j"], action: "", what: "Ton résumé : rang, succès, pièces, points de saison et série de missions." },
      19: { icon: "writable_book", name: "Missions", lore: ["Missions du jour et de la semaine", "Aujourd'hui  2 / 3", "Série  6 jour(s)"], glint: true, cmd: "/missions", what: "Tes missions du jour et de la semaine. L'icône brille tant qu'il en reste." },
      20: { icon: "knowledge_book", name: "Succès", lore: ["Ta progression et ce qu'il te reste", "Obtenus  110 / 146"], cmd: "/succes", what: "Tous les succès par catégorie, avec ceux qu'il te manque." },
      21: { icon: "gold_ingot", name: "Parcours de récompenses", lore: ["Les paliers à débloquer", "Débloqués  9 / 23"], cmd: "/parcours", what: "Les paliers qui se débloquent en jouant." },
      22: { icon: "chest", name: "Récompense du jour", lore: ["Disponible !", "Série  4 jour(s)"], glint: true, cmd: "/recompense", what: "Un coffre par jour, de plus en plus gros jusqu'au 7e. Brille quand il est disponible." },
      23: { icon: "nether_star", name: "Saison 2 · Octobre", lore: ["Classement du mois", "Fin dans  13 j 4 h"], cmd: "/saison", what: "Le classement du mois et ses récompenses." },
      24: { icon: "diamond", name: "Classement des succès", lore: ["Le podium avec les têtes des joueurs"], cmd: "/classement", what: "Le podium et le top des succès." },
      25: { icon: "white_banner", name: "Équipe", lore: ["Ton équipe : Nordique"], cmd: "/equipe", what: "Créer, rejoindre ou gérer ton équipe." },
      28: { icon: "name_tag", name: "Titres", lore: ["Choisis le titre devant ton pseudo", "Débloqués  8 / 27"], cmd: "/titres", what: "Le titre affiché devant ton pseudo dans le chat." },
      29: { icon: "blaze_powder", name: "Cosmétiques", lore: ["Couleur du pseudo et traînées"], cmd: "/cosmetiques", what: "Couleur de pseudo et traînées de particules." },
      30: { icon: "emerald", name: "Boutique", lore: ["Titres, cosmétiques, homes", "Solde  12 480 pièces"], cmd: "/boutique", what: "Dépense tes pièces en titres, cosmétiques et homes." },
      31: { icon: "gold_block", name: "Hôtel des ventes", lore: ["Achète et vends entre joueurs", "Objets en vente  17"], cmd: "/hdv", what: "Le marché entre joueurs." },
      32: { icon: "red_bed", name: "Homes", lore: ["Tes points de téléportation", "Emplacements  2 / 3"], cmd: "/homes", what: "Tes homes, pour revenir chez toi." },
      33: { icon: "wither_skeleton_skull", name: "Boss mondial", lore: ["Prochaine apparition :", "mercredi à 21:00"], cmd: "/boss", what: "Quand apparaît Le Colosse, et l'accès à l'arène." },
      34: { icon: "iron_pickaxe", name: "Monde ressources", lore: ["Un monde à miner, réinitialisé", "chaque mois"], cmd: "/ressources", what: "Pour miner sans abîmer le monde principal." },
      47: { icon: "filled_map", name: "Dynmap", lore: ["La carte du serveur en direct", "sur lyonmc.fr"], cmd: "/carte", what: "Le lien vers la carte en direct." },
      49: { icon: "barrier", name: "Fermer", lore: [], action: "", what: "Ferme le menu." },
      51: { icon: "comparator", name: "Scoreboard : affiché", lore: ["Afficher ou masquer le scoreboard"], action: "Cliquer pour changer", what: "Masque ou réaffiche le tableau à droite de l'écran." },
    },
  };
  function chest(menu) {
    const cells = [];
    for (let i = 0; i < menu.rows * 9; i++) {
      const it = menu.items[i];
      if (!it) { cells.push(`<span class="slot filler">${img(icon("black_stained_glass_pane"))}</span>`); continue; }
      const action = it.action ?? "Cliquer pour ouvrir";
      const tip = tipHtml(esc(it.name), it.lore.map(esc), action ? `<span style="color:${ACCENT}">${action}</span>` : "");
      cells.push(`<span class="slot ${it.glint ? "glint" : ""}" ${tipAttr(tip)} aria-label="${esc(it.name)}">${img(it.head ? face("MHF_Steve", 32) : icon(it.icon))}</span>`);
    }
    return `<div class="chest"><p class="gui-title">${esc(menu.title)}</p><div class="grid">${cells.join("")}</div></div>`;
  }

  const COMMANDS = [
    ["Général", [["/menu", "Le menu principal : tout le serveur en un clic"], ["/missions", "Tes missions du jour et de la semaine"], ["/recompense", "Récompense du jour"], ["/succes", "Ton profil de succès"], ["/classement", "Le podium des succès"], ["/saison", "Le classement du mois"], ["/carte", "Lien vers la carte en direct"]]],
    ["Récompenses", [["/parcours", "Les paliers à débloquer"], ["/titres", "Choisir ton titre"], ["/cosmetiques", "Couleur de pseudo et traînées"], ["/boutique", "Acheter titres, cosmétiques et homes"]]],
    ["Pièces", [["/money", "Ton solde"], ["/pay <joueur> <montant>", "Envoyer des pièces"], ["/baltop", "Les plus riches"], ["/hdv", "Hôtel des ventes"], ["/hdv vendre <prix>", "Vendre l'objet en main"], ["/hdv recuperer", "Récupérer tes objets non vendus"]]],
    ["Déplacements", [["/sethome <nom>", "Créer un home"], ["/home <nom>", "Aller à un home"], ["/homes", "Tes homes"], ["/hub", "Retour au hub (aussi /spawn)"], ["/tpa <joueur>", "Demander à rejoindre un joueur"], ["/tpahere <joueur>", "Demander à un joueur de venir"], ["/tpaccept", "Accepter une demande"], ["/back", "Revenir à ta position précédente"], ["/rtp", "Téléportation aléatoire"], ["/ressources", "Aller dans le monde ressources"]]],
    ["Équipes", [["/equipe", "Menu de ton équipe"], ["/equipe creer <nom> <tag>", "Fonder une équipe"], ["/equipe inviter <joueur>", "Inviter un joueur"], ["/equipe rejoindre <équipe>", "Accepter une invitation"], ["/equipe chat", "Parler seulement à ton équipe"], ["/equipe home", "Home de l'équipe"]]],
    ["Événements", [["/boss", "Prochain boss, ou rejoindre l'arène"]]],
  ];
  const SECTIONS = [
    ["debuter", "Bien commencer"], ["menus", "Le menu principal"], ["commandes", "Commandes"], ["missions", "Missions"], ["pieces", "Pièces et boutique"],
    ["parcours", "Parcours de récompenses"], ["titres", "Grades, titres et cosmétiques"], ["saisons", "Saisons"], ["boss", "Boss mondial"],
    ["equipes", "Équipes"], ["hdv", "Hôtel des ventes"], ["teleport", "Homes et téléportation"], ["ressources", "Monde ressources"], ["succes", "Tous les succès"],
  ];
  const DAYS = { MONDAY: "lundi", TUESDAY: "mardi", WEDNESDAY: "mercredi", THURSDAY: "jeudi", FRIDAY: "vendredi", SATURDAY: "samedi", SUNDAY: "dimanche" };

  async function guide(anchor) {
    setTitle("Guide");
    const g = await data("contenu", false);
    if (!g) {
      app.innerHTML = `<div class="page-head"><div class="wrap"><h1>Guide du serveur</h1><p>Le guide se charge depuis le serveur. Reviens dans une minute.</p></div></div>`;
      return;
    }
    const st = g.settings || {}, eco = g.economy || {}, mr = g.missionRewards || {};
    const tierNames = { easy: "Facile", medium: "Moyenne", hard: "Difficile", weekly: "Hebdomadaire" };
    const chips = (list) => `<ul class="chips">${(list || []).map((m) => `<li>${img(icon(m.icon))}${esc(m.name)}</li>`).join("")}</ul>`;
    const sched = (g.boss?.schedule || []).map((x) => { const [d, h] = x.split(/\s+/); return `le ${DAYS[d] || d} à ${h}`; });
    const totalAdv = (g.categories || []).reduce((n, c) => n + c.advancements.length, 0);
    const rewardsTable = (rows) => `<table class="cmds"><tbody>${rows.map(([l, b]) => `<tr><td><strong>${l}</strong></td><td>${bundle(b)}</td></tr>`).join("")}</tbody></table>`;

    const sec = {
      debuter: `<p>Bienvenue sur LyonMC. Trois choses à faire en arrivant :</p>
        <ol class="steps" style="margin-top:18px">
          <li class="card"><strong>Récupère ta récompense</strong><p>Tape <code class="cmd">/recompense</code> chaque jour. Elle grossit jusqu'au 7e jour d'affilée.</p></li>
          <li class="card"><strong>Lance tes missions</strong><p><code class="cmd">/missions</code> te donne trois objectifs par jour, du plus simple au plus dur.</p></li>
          <li class="card"><strong>Pose ton home</strong><p><code class="cmd">/sethome base</code> près de ta maison, puis <code class="cmd">/home base</code> pour y revenir.</p></li>
        </ol>
        <p style="margin-top:22px">Tu commences avec <strong>${num(eco.start)} pièces</strong> et <strong>${st.homesBase} homes</strong>. Le reste se gagne en jouant.</p>`,
      menus: `<p>Tape <code class="cmd">/menu</code> pour l'ouvrir. C'est la copie exacte du menu en jeu : survole une case pour voir ce qu'elle affiche. Les chiffres sont des exemples.</p>
        <div class="wiki-menu" style="margin-top:18px">${chest(HUB)}
          <ul class="legend">${Object.values(HUB.items).map((e) => `<li>${img(e.head ? face("MHF_Steve", 32) : icon(e.icon))}<div><strong>${esc(e.name)}</strong><span>${esc(e.what)}${e.cmd ? ` <code class="cmd">${e.cmd}</code>` : ""}</span></div></li>`).join("")}</ul></div>`,
      commandes: COMMANDS.map(([group, list]) => `<h3>${group}</h3><table class="cmds"><tbody>${list.map(([c, d]) => `<tr><td><code class="cmd">${esc(c)}</code></td><td>${esc(d)}</td></tr>`).join("")}</tbody></table>`).join(""),
      missions: `<p>Chaque jour à minuit, tu reçois une mission <strong>facile</strong>, une <strong>moyenne</strong> et une <strong>difficile</strong>. Le lundi, trois missions <strong>hebdomadaires</strong> plus longues arrivent.</p>
        <p>Terminer les trois missions du jour rapporte un bonus (${bundle(mr.bonus)}) et fait avancer ta <strong>série</strong> : chaque jour de série ajoute ${mr.streakPercent} % de pièces sur les missions, jusqu'à +${mr.streakMax} %. Rater un jour remet la série à zéro. Tu peux changer une mission par jour gratuitement avec un clic droit dans le menu.</p>
        <div class="tiers">${["easy", "medium", "hard", "weekly"].map((t) => `<div class="card"><h4>${tierNames[t]}</h4>${bundle(mr[t])}</div>`).join("")}</div>
        ${["easy", "medium", "hard", "weekly"].map((t) => `<h3>Missions ${tierNames[t].toLowerCase()}s possibles</h3>${chips(g.missions?.[t])}`).join("")}`,
      pieces: `<p>Les pièces se gagnent avec chaque nouveau succès (<strong>${eco.task}</strong> pour un progrès, <strong>${eco.goal}</strong> pour un objectif, <strong>${eco.challenge}</strong> pour un défi), les missions, la récompense du jour, le boss et l'hôtel des ventes.</p>
        <h3>Récompense du jour</h3>
        <p>Elle augmente chaque jour où tu la récupères avec <code class="cmd">/recompense</code>, jusqu'au 7e jour, puis le cycle recommence. Si tu manques un jour, tu repars du jour 1.</p>
        <ol class="days">${(g.daily || []).map((b, i) => `<li class="card"><strong>Jour ${i + 1}</strong>${bundle(b)}</li>`).join("")}</ol>
        <h3>À la boutique</h3>
        <p>Titres et cosmétiques avec un prix, et des emplacements de home en plus. Tout s'achète avec <code class="cmd">/boutique</code>.</p>`,
      parcours: `<p>${(g.paliers || []).length} paliers se débloquent tout seuls quand tu remplis leur condition. Ta progression est visible avec <code class="cmd">/parcours</code>.</p>
        <ol class="path">${(g.paliers || []).map((p, i) => `<li class="card"><span class="n">${i + 1}</span>${ico(p.icon, "sm")}<div><strong>${esc(p.name)}</strong><span class="muted small">${esc(p.condition)}</span><br>${bundle(p.rewards)}</div></li>`).join("")}</ol>`,
      titres: `<h3 style="margin-top:0">Grades</h3>
        <p>Ton grade monte tout seul selon la part des succès que tu as obtenus. Il s'affiche devant ton pseudo dans le chat, et tout le serveur est prévenu quand tu passes au suivant. Une fois <strong>Légende</strong>, tu peux choisir le grade que tu affiches avec <code class="cmd">/titres</code> (tous sauf Novice).</p>
        <ol class="grades">${(g.grades || []).map((x) => `<li class="card"><b>${x.html}</b><small>${x.percent ? `dès ${String(x.percent).replace(/\.0$/, "")} % des succès` : "au départ"}</small></li>`).join("")}</ol>
        <h3>Titres</h3>
        <p>En plus du grade, tu peux afficher un titre entre crochets. Tu les gagnes avec les paliers, les saisons ou la boutique, et tu choisis lequel porter avec <code class="cmd">/titres</code>.</p>
        <div class="titles-grid">${(g.titles || []).map((t) => `<div class="card"><b>${t.html}</b><small>${esc(t.description)}${t.price ? `, ${num(t.price)} pièces` : ""}</small></div>`).join("")}</div>
        <h3>Couleurs de pseudo</h3>
        <div class="titles-grid">${(g.colors || []).map((c) => `<div class="card"><b>${c.html}</b><small>${c.price ? `${num(c.price)} pièces` : "Débloquée par un palier"}</small></div>`).join("")}</div>
        <h3>Traînées de particules</h3>
        ${chips((g.trails || []).map((t) => ({ icon: t.icon, name: t.name + (t.price ? ` (${num(t.price)} pièces)` : "") })))}`,
      saisons: `<p>Une saison dure un mois. Chaque nouveau succès rapporte des points (<strong>${g.season?.points?.task}</strong> pour un progrès, <strong>${g.season?.points?.goal}</strong> pour un objectif, <strong>${g.season?.points?.challenge}</strong> pour un défi), tout comme les missions et le boss. Le samedi et le dimanche, les points sont multipliés par <strong>${String(g.season?.weekendMultiplier).replace(/\.0$/, "")}</strong>.</p>
        <h3>Récompenses de fin de saison</h3>
        ${rewardsTable([["1re place", g.season?.rewards?.["1"]], ["2e place", g.season?.rewards?.["2"]], ["3e place", g.season?.rewards?.["3"]], ["4e à 10e", g.season?.rewards?.top10], ["Participation", g.season?.rewards?.participation]])}`,
      boss: `<p><strong>${esc(g.boss?.name)}</strong> apparaît ${sched.length ? sched.join(" et ") : "à des horaires annoncés en jeu"}. L'arène ouvre 15 minutes avant avec <code class="cmd">/boss</code>. Le combat dure ${g.boss?.duration} minutes : passé ce délai, le boss s'enfuit.</p>
        <p>Il repousse tout le monde d'une onde de choc, invoque des renforts, appelle la foudre et bondit sur le joueur le plus éloigné. Sous la moitié de sa vie, il attaque plus souvent. Ta récompense dépend des dégâts que tu infliges.</p>
        ${rewardsTable([["Participation", g.boss?.rewards?.participation], ["Meilleurs dégâts", g.boss?.rewards?.top1], ["2e", g.boss?.rewards?.top2], ["3e", g.boss?.rewards?.top3], ["Coup de grâce", g.boss?.rewards?.["last-hit"]]])}`,
      equipes: `<p>Fonde une équipe avec <code class="cmd">/equipe creer &lt;nom&gt; &lt;tag&gt;</code> pour ${num(st.teamCost)} pièces, jusqu'à ${st.teamMax} membres. Ton équipe a un tag devant le pseudo, un chat privé, un home commun et sa place dans le classement des équipes. Les coéquipiers ne peuvent pas se blesser, sauf si le chef active le combat.</p>`,
      hdv: `<p>Prends un objet en main et tape <code class="cmd">/hdv vendre &lt;prix&gt;</code>. Tu peux avoir ${st.hdvMax} objets en vente en même temps. Une taxe de ${String(st.hdvTax).replace(/\.0$/, "")} % est prélevée sur chaque vente. Les objets non vendus se récupèrent avec <code class="cmd">/hdv recuperer</code>.</p>`,
      teleport: `<p>À chaque connexion, tu arrives au <strong>hub</strong>. Tu y trouves les statistiques du serveur, les classements en direct et le podium des meilleurs joueurs. <code class="cmd">/back</code> te ramène là où tu t'étais déconnecté, et <code class="cmd">/hub</code> te ramène au hub à tout moment.</p>
        <p>Tu as ${st.homesBase} homes au départ. Les paliers et la boutique en donnent d'autres. Toutes les téléportations ont un court délai : ne bouge pas pendant le compte à rebours. <code class="cmd">/rtp</code> t'envoie dans un endroit sûr au hasard, une fois toutes les ${Math.round((st.rtpCooldown || 600) / 60)} minutes.</p>`,
      ressources: `<p>Le monde ressources est fait pour miner sans abîmer le monde principal. Il est remis à neuf le 1er de chaque mois : n'y construis rien d'important. Accès avec <code class="cmd">/ressources</code>.</p>`,
      succes: `<p>${totalAdv} succès au total, répartis en ${(g.categories || []).length} catégories. Les succès secrets restent cachés tant que personne ne les a obtenus. Le pourcentage indique la part des joueurs qui l'ont.</p>
        ${(g.categories || []).map((c) => `<div class="cat-block"><h3>${img(icon(catIcon[c.id] || "book"))}${esc(catName(c))} <span class="muted small">${c.advancements.length} succès</span></h3>
          <div class="adv-list">${c.advancements.map((a) => { const secret = a.hidden && !a.rarity; return `<div class="card adv ${a.frame}">${frame(secret ? { ...a, icon: "book" } : a, secret ? "locked" : "")}
            <div class="txt"><strong>${secret ? "Succès secret" : esc(advTitle(a))}</strong><span class="desc">${secret ? "À découvrir en jouant." : esc(advDesc(a))}</span>
            <span class="when">${pct(a.rarity)} des joueurs</span></div></div>`; }).join("")}</div></div>`).join("")}`,
    };

    app.innerHTML = `
      <div class="page-head"><div class="wrap"><h1>Guide du serveur</h1><p>Tout ce qu'il faut savoir pour jouer sur LyonMC. Les chiffres viennent directement du serveur.</p></div></div>
      <div class="wrap guide">
        <nav class="toc" aria-label="Sommaire">${SECTIONS.map(([id, t]) => `<a href="#${id}">${t}</a>`).join("")}</nav>
        <div>${SECTIONS.map(([id, t]) => `<section id="${id}"><h2>${t}</h2>${sec[id]}</section>`).join("")}</div>
      </div>`;

    const links = [...app.querySelectorAll(".toc a")];
    const obs = new IntersectionObserver((entries) => entries.forEach((e) => {
      if (e.isIntersecting) links.forEach((l) => l.classList.toggle("active", l.getAttribute("href") === `#${e.target.id}`));
    }), { rootMargin: "-20% 0px -70% 0px" });
    app.querySelectorAll(".guide section").forEach((s) => obs.observe(s));
    if (anchor) document.getElementById(anchor)?.scrollIntoView();
  }

  // ───────────── Carte (Dynmap intégrée) ─────────────
  function carte() {
    setTitle("Carte");
    if (app.querySelector("iframe.map-frame")) return;
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
    tipEl.hidden = true;
    const path = decodeURI(location.pathname).replace(/\/+$/, "") || "/";
    setNav(path);
    const parts = path.split("/").filter(Boolean);
    document.body.classList.toggle("is-map", parts[0] === "carte");
    try {
      if (path === "/") await home();
      else if (parts[0] === "classements") await boards(parts[1]);
      else if (parts[0] === "joueurs") await players();
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
    refreshOnline();
    setInterval(refreshOnline, 30000);
    render();
  })();
})();
