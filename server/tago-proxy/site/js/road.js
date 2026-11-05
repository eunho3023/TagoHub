// 도로: 한국도로공사 돌발·정체·휴게소 주유 가격(서버 /w/ex/), 실시간 신호등(서울·울산, /w/signal/).
import { getText, esc } from "./core.js";
import { act, header, note, bindInput } from "./ui.js";
import { MapView } from "./map.js";

const JAM = "#F08C00", RED = "#D7262E";
const won = (n) => n.toLocaleString("ko-KR") + "원";
const dist = (m) => (m >= 1000 ? `${Math.floor(m / 1000)}.${Math.floor((m % 1000) / 100)}km` : m + "m");

// ── 자료 ──
function parse(body) {
  const root = JSON.parse(body);
  if (root.code && String(root.code).toUpperCase() !== "SUCCESS") throw new Error(root.message || root.code);
  const rows = Object.values(root).find((v) => Array.isArray(v) && v.length && v.every((x) => x && typeof x === "object")) || [];
  return [rows.map((r) => Object.fromEntries(Object.entries(r).map(([k, v]) => [k, v == null ? null : String(v).trim()]).filter(([, v]) => v && v !== "null"))), parseInt(root.count, 10) || null];
}
async function allRows(path) {
  const [first, total] = parse(await getText("w/ex/" + path, { pageNo: 1 }));
  if (!total || first.length < 99 || first.length >= total) return first;
  const pages = Math.min(20, Math.ceil(total / 99));
  const rest = await Promise.all(Array.from({ length: pages - 1 }, (_, i) => getText("w/ex/" + path, { pageNo: i + 2 }).then((b) => parse(b)[0]).catch(() => [])));
  return first.concat(...rest);
}
const inKr = (la, lo) => la >= 30 && la <= 40 && lo >= 123 && lo <= 133;
async function incidents() {
  const seen = new Set();
  return (await allRows("burstInfo/realTimeSms")).filter((r) => r.accType !== "이벤트/홍보").map((r) => {
    const text = (r.smsText || "").replace(/\*/g, "").trim(), la = parseFloat(r.latitude), lo = parseFloat(r.altitude);
    const ok = inKr(la, lo);
    return { id: [r.nosunNM, r.seriesNM, r.accDate, r.accHour, text.slice(0, 20)].join("|"), kind: r.accType || "돌발", road: r.roadNM, text, place: r.accPointNM,
      dir: r.startEndTypeCode, status: r.accProcessNM, time: [r.accDate, (r.accHour || "").slice(0, 5)].filter(Boolean).join(" ") || null,
      jam: parseInt(r.lateLength, 10) > 0 ? parseInt(r.lateLength, 10) : null, lat: ok ? la : null, lng: ok ? lo : null };
  }).filter((x) => !seen.has(x.id) && seen.add(x.id)).sort((a, b) => (b.time || "").localeCompare(a.time || ""));
}
let vdsP = null;
async function jams() {
  if (!vdsP) vdsP = getText("data/files/ex_vds.json").then(JSON.parse).catch(() => ({}));
  const [rows, vds] = await Promise.all([allRows("odtraffic/trafficAmountByCongest"), vdsP]);
  const groups = new Map();
  for (const r of rows) { const k = (r.conzoneId || r.vdsId) + ":" + (r.updownTypeCode || ""); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(r); }
  return [...groups].map(([k, g]) => {
    const r = g.reduce((a, b) => ((parseFloat(b.speed) ?? 1e9) < (parseFloat(a.speed) ?? 1e9) ? b : a));
    const pos = vds[r.vdsId] || g.map((x) => vds[x.vdsId]).find(Boolean);
    return { id: k, route: r.routeName || "", zone: r.conzoneName || "", speed: r.speed != null ? Math.trunc(parseFloat(r.speed)) : null,
      dir: { S: "기점 방향", E: "종점 방향" }[r.updownTypeCode] || null, time: r.stdHour && r.stdHour.length >= 4 ? r.stdHour.slice(0, 2) + ":" + r.stdHour.slice(2, 4) : null,
      lat: pos ? +pos[0] : null, lng: pos ? +pos[1] : null };
  }).sort((a, b) => (a.speed ?? 1e9) - (b.speed ?? 1e9));
}
async function gas() {
  const w = (v) => { const n = parseInt((v || "").replace(/\D/g, ""), 10); return n > 0 ? n : null; };
  const seen = new Set();
  return (await allRows("business/curStateStation")).filter((r) => r.serviceAreaName).map((r) => ({
    name: r.serviceAreaName, route: r.routeName, dir: r.direction, company: r.oilCompany, gasoline: w(r.gasolinePrice), diesel: w(r.diselPrice), lpg: r.lpgYn !== "N" ? w(r.lpgPrice) : null,
  })).filter((g) => !seen.has(g.name + "|" + g.dir) && seen.add(g.name + "|" + g.dir)).sort((a, b) => (a.gasoline ?? 1e9) - (b.gasoline ?? 1e9) || a.name.localeCompare(b.name));
}

// 신호등
const DIRS = { nt: "북쪽", ne: "북동쪽", et: "동쪽", se: "남동쪽", st: "남쪽", sw: "남서쪽", wt: "서쪽", nw: "북서쪽" };
const KINDS = { St: "직진", Lt: "좌회전", Pd: "보행자", Ut: "유턴", Bs: "버스", Bc: "자전거" };
const lamp = (s) => ({ "stop-and-remain": "red", "pre-movement": "red", "stop-then-proceed": "redBlink", "stop-then-processed": "redBlink", "protected-movement-allowed": "green",
  "permissive-movement-allowed": "green", "protected-clearance": "yellow", "permissive-clearance": "yellow", "caution-conflicting-traffic": "yellowBlink" })[s.trim().toLowerCase()] || "off";
const lampColor = (c) => (c.startsWith("red") ? RED : c.startsWith("yellow") ? "#F2B600" : c === "green" ? "#2E9E5B" : "#9E9E9E");
const lampLabel = (c) => ({ red: "적색", redBlink: "적색 점멸", yellow: "황색", yellowBlink: "황색 점멸", green: "녹색" })[c] || "정보 없음";
function sortSig(states, remains) {
  const d = Object.keys(DIRS), k = Object.keys(KINDS);
  return [...states.keys()].sort((a, b) => d.indexOf(a.split("|")[0]) - d.indexOf(b.split("|")[0]) || k.indexOf(a.split("|")[1]) - k.indexOf(b.split("|")[1]))
    .map((key) => ({ dir: key.split("|")[0], kind: key.split("|")[1], color: lamp(states.get(key)), remainMs: remains.get(key) ?? null }));
}
function ulsanItems(j) { const b = j.body || j.response?.body; const it = b?.items?.item ?? b?.items; return Array.isArray(it) ? it : it ? [it] : []; }
let spotsP = null;
function spots() {
  if (!spotsP) spotsP = Promise.all([
    getText("data/files/seoul_signals.tsv").then((t) => t.split("\n").map((l) => l.split("\t")).filter((f) => f.length >= 4 && f[0].trim() && Number.isFinite(+f[2]))
      .map((f) => ({ id: "seoul:" + f[0].trim(), name: f[1].trim() || "교차로", lat: +f[2], lng: +f[3] }))).catch(() => []),
    getText("w/signal/ulsan/map").then((t) => ulsanItems(JSON.parse(t)).filter((m) => m.crsrdId && Number.isFinite(+m.mapCtptIntLat))
      .map((m) => ({ id: "ulsan:" + m.crsrdId, name: m.crsrdNm || "교차로", lat: +m.mapCtptIntLat, lng: +m.mapCtptIntLot }))).catch(() => []),
  ]).then(([a, b]) => a.concat(b));
  return spotsP;
}
async function signalNow(spot) {
  const states = new Map(), remains = new Map();
  if (spot.id.startsWith("seoul:")) {
    const rows = [JSON.parse(await getText("w/signal/seoul/" + spot.id.slice(6)))].flat(3).filter((x) => x && x.itstId);
    const r = rows.sort((a, b) => (+b.trsmUtcTime || 0) - (+a.trsmUtcTime || 0))[0];
    if (!r) return [];
    for (const [k, v] of Object.entries(r)) {
      const m = /^(nt|et|st|wt|ne|se|sw|nw)(Bs|Bc|Lt|Pd|St|Ut)sg(StatNm|RmdrCs)$/.exec(k);
      if (!m || v == null || v === "" || v === "null") continue;
      const key = m[1] + "|" + m[2];
      if (m[3] === "StatNm") states.set(key, String(v)); else if (+v < 36000) remains.set(key, Math.round(+v * 100));
    }
  } else {
    const m0 = ulsanItems(JSON.parse(await getText("w/signal/ulsan/now"))).find((m) => String(m.crsrdId) === spot.id.slice(6));
    if (!m0) return [];
    for (const [k, v] of Object.entries(m0)) {
      const m = /^(nt|et|st|wt|ne|se|sw|nw)(St|Lt|Pd|Ut)sg(SttsNm|RmndCs)$/.exec(k);
      if (!m || v == null || v === "" || v === "null") continue;
      const key = m[1] + "|" + m[2];
      if (m[3] === "SttsNm") states.set(key, String(v)); else if (Number.isFinite(+v)) remains.set(key, Math.trunc(+v));
    }
  }
  return sortSig(states, remains);
}

// ── 화면 (대중교통 → 도로) ──
const road = { tab: 0, picked: null, q: "", incidents: null, jams: null, gas: null, failed: new Set(), sig: null, fitted: false };
export function roadScreen(root, chipsHtml, ctx) {
  let alive = true, offIdle = null, sigTimer = null, tickTimer = null;
  const load = (i) => {
    const [fn, key] = [[incidents, "incidents"], [jams, "jams"], [gas, "gas"]][i];
    road.failed.delete(i);
    fn().then((x) => { road[key] = x; }).catch(() => { road.failed.add(i); road[key] = []; }).finally(() => { if (alive && road.tab === i) draw(); });
  };
  const tabs = ["돌발", "정체", "주유 가격", "신호등"];
  function stopSig() { clearInterval(sigTimer); clearInterval(tickTimer); sigTimer = tickTimer = null; }
  function draw() {
    if (!alive) return;
    stopSig();
    offIdle?.(); offIdle = null;
    const h = header("TagoHub", "고속도로 돌발·정체·주유 가격·신호등", { action: { label: "새로", run: () => { road.incidents = road.jams = road.gas = null; spotsP = null; [0, 1, 2].forEach(load); draw(); } } }) + chipsHtml +
      `<div class="segs">${tabs.map((l, i) => `<button class="${road.tab === i ? "on" : ""}" ${act(() => { road.tab = i; road.picked = null; draw(); })}>${l}</button>`).join("")}</div>`;
    if (road.tab === 2) {
      MapView.clear();
      const list = road.gas;
      if (road.failed.has(2)) { root.innerHTML = h + note("주유 가격을 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return; }
      if (!list) { root.innerHTML = h + note("주유 가격을 불러오는 중..."); return; }
      root.innerHTML = h + `<div class="searchbox"><input id="gq" type="search" placeholder="휴게소·노선 이름" value="${esc(road.q)}" autocomplete="off"></div><div id="gl"></div>`;
      const drawGas = () => {
        const q = road.q.replace(/ /g, ""), shown = list.filter((g) => !q || g.name.replace(/ /g, "").includes(q) || (g.route || "").includes(q));
        const cheap = Math.min(...shown.map((g) => g.gasoline || 1e9));
        root.querySelector("#gl").innerHTML = shown.length ? `<div class="list">${shown.map((g) => `<div class="row"><span class="grow"><b>${esc(g.name)}</b><small>${esc([g.route, g.dir, g.company].filter(Boolean).join("  ·  "))}</small></span>
          <span class="right">${g.gasoline ? `<b class="sm ${g.gasoline === cheap ? "blue" : ""}">휘발유 ${won(g.gasoline)}</b>` : ""}<small>${[g.diesel && "경유 " + won(g.diesel), g.lpg && "LPG " + won(g.lpg)].filter(Boolean).join("  ")}</small></span></div>`).join("")}</div>` : note("주유 가격 정보가 없어요");
      };
      bindInput(root, "#gq", (v) => { road.q = v; drawGas(); }, 200);
      drawGas();
      return;
    }
    root.innerHTML = h + `<div class="map-slot"></div><div id="rl"></div>`;
    ctx.useMap(root.querySelector(".map-slot"));
    const list = root.querySelector("#rl");
    if (road.tab === 3) return drawSignals(list);
    const items = road.tab === 0 ? road.incidents : road.jams, color = road.tab === 0 ? RED : JAM;
    const pts = (items || []).filter((x) => x.lat != null);
    MapView.lines([]);
    MapView.dots([...pts.filter((x) => x.id !== road.picked), ...pts.filter((x) => x.id === road.picked)].map((x) => ({ id: x.id, lat: x.lat, lng: x.lng, color, ring: "#fff", r: x.id === road.picked ? 9 : 6 })),
      (d) => { road.picked = d.id; draw(); });
    MapView.onTap(() => { road.picked = null; draw(); });
    if (!road.fitted) { MapView.view(36.4, 127.8, 7); road.fitted = true; }
    const pick = (x) => { road.picked = x.id; if (x.lat != null) MapView.view(x.lat, x.lng, 13); draw(); };
    if (road.failed.has(road.tab)) { list.innerHTML = note(road.tab === 0 ? "돌발 정보를 받지 못했어요. 잠시 뒤 다시 시도해 주세요." : "정체 정보를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return; }
    if (!items) { list.innerHTML = note(road.tab === 0 ? "돌발 정보를 불러오는 중..." : "정체 구간을 불러오는 중..."); return; }
    if (!items.length) { list.innerHTML = note(road.tab === 0 ? "지금 고속도로 돌발 정보가 없어요" : "지금 고속도로 정체 구간이 없어요"); return; }
    const sorted = [...items].sort((a, b) => (a.id === road.picked ? -1 : b.id === road.picked ? 1 : 0));
    list.innerHTML = `<div class="list">${sorted.map((x) => road.tab === 0 ?
      `<button class="row col ${x.id === road.picked ? "picked" : ""}" ${act(() => pick(x))}><span class="flexrow"><b class="red sm">${esc(x.kind)}</b><b class="grow sm">${esc([x.road, x.dir].filter(Boolean).join(" · "))}</b><small>${esc(x.time || "")}</small></span>
        ${x.text ? `<span>${esc(x.text)}</span>` : ""}<small>${esc([x.place, x.jam && "정체 " + dist(x.jam), x.status].filter(Boolean).join("  ·  "))}</small></button>` :
      `<button class="row ${x.id === road.picked ? "picked" : ""}" ${act(() => pick(x))}><span class="grow"><b class="sm">${esc([x.route, x.zone].filter(Boolean).join(" "))}</b><small>${esc([x.dir, x.time && x.time + " 기준"].filter(Boolean).join("  ·  "))}</small></span>${x.speed != null ? `<b style="color:${JAM}">${x.speed}km/h</b>` : ""}</button>`).join("")}</div>`;
  }
  function drawSignals(list) {
    let all = null;
    const primary = getComputedStyle(document.documentElement).getPropertyValue("--primary").trim();
    if (!road.sig) { road.sig = true; MapView.view(37.5665, 126.978, 15); }
    const paint = () => {
      const b = MapView.bounds(), z = MapView.zoom();
      const shown = !all || !b || z < 14 ? [] : all.filter((s) => s.lat >= b.s && s.lat <= b.n && s.lng >= b.w && s.lng <= b.e).slice(0, 400);
      const p = road.picked && typeof road.picked === "object" ? road.picked : null;
      MapView.lines([]);
      MapView.dots([...shown.filter((s) => !p || s.id !== p.id), ...(p ? [p] : [])].map((s) => ({ id: s.id, lat: s.lat, lng: s.lng, color: p && s.id === p.id ? RED : primary, ring: "#fff", r: p && s.id === p.id ? 8 : 5 })),
        (d) => { road.picked = shown.find((s) => s.id === d.id) || p; showSpot(); paint(); });
      if (!p) list.innerHTML = note(!all ? "교차로 목록을 불러오는 중..." : z < 14 ? "지도를 확대하면 교차로가 보여요 (서울·울산)" : !shown.length ? "이 근처에는 실시간 신호 교차로가 없어요 (서울·울산만 있어요)" : `교차로 ${shown.length}곳 · 점을 누르면 지금 신호가 보여요`);
    };
    MapView.onTap(() => { road.picked = null; stopSig(); paint(); });
    offIdle = MapView.onIdle(paint);
    function showSpot() {
      stopSig();
      const p = road.picked;
      if (!p || typeof p !== "object") return;
      let now = null, got = 0;
      const render = () => {
        if (!alive || road.picked !== p) return;
        const passed = Date.now() - got;
        list.innerHTML = `<div class="status"><b>${esc(p.name)}</b></div>` + (now == null ? note("지금 신호를 불러오는 중...") : !now.length ? note("이 교차로는 지금 신호 정보가 없어요") :
          `<div class="list">${now.map((s) => `<div class="row"><span class="lamp" style="background:${lampColor(s.color)}"></span><span class="grow">${esc((DIRS[s.dir] || s.dir) + " " + (KINDS[s.kind] || s.kind))}<small>${lampLabel(s.color)}</small></span>
            ${s.remainMs != null ? `<b class="eta-m" style="color:${lampColor(s.color)}">${Math.max(0, Math.floor((s.remainMs - passed) / 1000))}초</b>` : ""}</div>`).join("")}</div>` + note("신호 정보는 몇 초 늦을 수 있어요. 실제 신호를 꼭 확인하세요."));
      };
      const fetchNow = async () => { try { now = await signalNow(p); } catch (e) { now = now || []; } got = Date.now(); render(); };
      fetchNow(); render();
      sigTimer = setInterval(fetchNow, 2000);
      tickTimer = setInterval(render, 250);
    }
    spots().then((s) => { all = s; if (alive && road.tab === 3) { paint(); showSpot(); } });
    paint();
  }
  if (!road.incidents) load(0);
  if (!road.jams) load(1);
  if (!road.gas) load(2);
  draw();
  return () => { alive = false; stopSig(); offIdle?.(); };
}
