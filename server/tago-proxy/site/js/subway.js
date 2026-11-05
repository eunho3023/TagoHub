// 지하철: 역 찾기(국토교통부), 역 화면(서울 실시간 도착·시간표·혼잡도·시설), 서울 지하철 노선도 그림.
import { BASE, getText, xmlRows, esc, seoulNow, isoDay, pad2, subwayColor } from "./core.js";
import { act, header, note, sectionHead, bindInput, chips } from "./ui.js";
import { go, isWide } from "./nav.js";
import { Favorites, Recent } from "./store.js";

// ── 자료 ──
export async function searchStations(q) {
  const name = q.trim().replace(/(.{2,})역$/, "$1");
  if (!name) return [];
  const rows = xmlRows(await getText("w/dg/1613000/SubwayInfo/GetKwrdFndSubwaySttnList", { subwayStationName: name }));
  const seen = new Set();
  return rows.map((r) => ({ id: r.subwaystationid, name: r.subwaystationname || r.subwaystationnm || "", line: r.subwayroutename || r.subwayroutenm || "" }))
    .filter((s) => s.id && !seen.has(s.id) && seen.add(s.id));
}

/** "053000" → 하루 중 분 (새벽 0~2시는 24시 뒤로) */
function serviceMinute(s) {
  const d = (s || "").replace(/\D/g, "");
  if (d.length < 4 || /^0+$/.test(d)) return null;
  const h = +d.slice(0, 2), m = +d.slice(2, 4);
  return h < 3 ? h * 60 + m + 1440 : h * 60 + m;
}
const schedules = new Map();
async function schedule(id, day, ud) {
  const k = `${id}|${day}|${ud}`;
  if (!schedules.has(k)) {
    const rows = xmlRows(await getText("w/dg/1613000/SubwayInfo/GetSubwaySttnAcctoSchdulList", { subwayStationId: id, dailyTypeCode: day, upDownTypeCode: ud }));
    const seen = new Set();
    const list = rows.map((r) => {
      const d = serviceMinute(r.deptime), a = serviceMinute(r.arrtime);
      const min = d ?? a;
      return min == null ? null : { min, end: r.endsubwaystationnm || r.endsubwaystationname || "", arrivalOnly: d == null };
    }).filter((x) => x && !seen.has(x.min + "|" + x.end) && seen.add(x.min + "|" + x.end)).sort((a, b) => a.min - b.min);
    schedules.set(k, list);
  }
  return schedules.get(k);
}
export const clock = (min) => { const m = ((min % 1440) + 1440) % 1440; return pad2(Math.floor(m / 60)) + ":" + pad2(m % 60); };
const dayCode = (d) => (d === 6 ? "02" : d === 7 ? "03" : "01");

// 서울 실시간 (서울 열린데이터광장, 서버 /w/seoul/arrival/<역>)
const OTHER_CITY = ["MTRBS", "MTRDG", "MTRGJ", "MTRDJ", "MTRIC", "MTRGMG", "MTRUL"];
const LINE_IDS = { "1호선": "1001", "2호선": "1002", "3호선": "1003", "4호선": "1004", "5호선": "1005", "6호선": "1006", "7호선": "1007", "8호선": "1008", "9호선": "1009",
  경의중앙: "1063", 공항: "1065", 경춘: "1067", 수인분당: "1075", 신분당: "1077", 경강: "1081", 우이신설: "1092", 서해: "1093", 신림: "1094", "GTX-A": "1032" };
function lineIdOf(s) {
  if (OTHER_CITY.some((p) => s.id.startsWith(p))) return null;
  let n = s.line.replace(/ /g, "").replace(/선$/, "");
  if (n.endsWith("호")) n += "선";
  return LINE_IDS[n] || LINE_IDS[s.line.trim()] || null;
}
function mainText(m, code) {
  m = (m || "").trim();
  let r = /\[(\d+)]번째 전역/.exec(m); if (r) return r[1] + "번째 전역";
  r = /(\d+)분\s*후/.exec(m); if (r) return r[1] + "분 후";
  const c = { 0: "진입", 1: "도착", 2: "출발", 3: "전역 출발", 4: "전역 진입", 5: "전역 도착" }[code];
  return c || m || "정보 없음";
}
async function seoulLive(s) {
  const line = lineIdOf(s);
  if (!line) return null;
  const n = s.name.trim().length > 2 ? s.name.trim().replace(/역$/, "") : s.name.trim();
  let rows = [];
  for (const q of [...new Set([n, n.split("(")[0]])].filter(Boolean)) {
    const b = JSON.parse(await getText("w/seoul/arrival/" + encodeURIComponent(q)));
    rows = (b.realtimeArrivalList || []).map((r) => ({
      lineId: String(r.subwayId || ""), upDown: r.updnLine || "", dest: r.bstatnNm || null, text: mainText(r.arvlMsg2, parseInt(r.arvlCd, 10)),
      seconds: parseInt(r.barvlDt, 10) > 0 ? parseInt(r.barvlDt, 10) : null, lineText: r.trainLineNm || "",
      express: !!r.btrainSttus && r.btrainSttus !== "일반", last: r.lstcarAt === "1", order: r.ordkey || "",
    }));
    if (rows.length) break;
  }
  return rows.filter((r) => r.lineId === line).sort((a, b) => a.upDown.localeCompare(b.upDown) || a.order.localeCompare(b.order) || (a.seconds ?? 1e9) - (b.seconds ?? 1e9));
}

// 혼잡도·시설 (서버에 올린 앱 자료 파일)
const PAREN = /\(.*?\)/g;
export const stationNorm = (s) => { const t = s.replace(PAREN, "").replace(/ /g, "").trim(); return t.length >= 3 && t.endsWith("역") ? t.slice(0, -1) : t; };
const CITY = { MTRBS: "부산", MTRDG: "대구", MTRGJ: "광주", MTRDJ: "대전", MTRIC: "인천" };
let congP = null, facP = null;
function congestionTable() {
  if (!congP) congP = getText("data/files/subway_congestion.tsv").then((t) => {
    const out = new Map();
    for (const l of t.split("\n")) {
      const f = l.split("\t");
      if (f.length < 5 || !["W", "S", "U"].includes(f[0])) continue;
      const k = `${f[0]}|${f[1].trim()}|${stationNorm(f[2])}`;
      if (!out.has(k)) out.set(k, []);
      out.get(k).push({ dir: f[3].trim(), values: f[4].split(",").map((v) => { const n = parseInt(v, 10); return Number.isFinite(n) ? n : -1; }) });
    }
    return out;
  });
  return congP;
}
function facilityTable() {
  if (!facP) facP = getText("data/files/subway_station_facilities.tsv").then((t) => {
    const out = new Map();
    t.split("\n").slice(1).forEach((row) => {
      const c = row.split("\t");
      if (c.length < 5 || !c[2].trim() || !c[4].trim()) return;
      const f = { operator: c[0].trim().replace(/^﻿/, ""), line: c[1].trim(), station: c[2].trim(), category: c[3].trim(), detail: c[4].trim() };
      const k = stationNorm(f.station);
      if (!out.has(k)) out.set(k, []);
      out.get(k).push(f);
    });
    return out;
  });
  return facP;
}
const lineNorm = (s) => s.replace(/ /g, "").replace(/선$/, "");
async function facilities(s) {
  const rows = (await facilityTable()).get(stationNorm(s.name)) || [];
  const city = Object.entries(CITY).find(([p]) => s.id.startsWith(p))?.[1];
  const line = lineNorm(s.line);
  return rows.filter((f) => (city ? f.operator.includes(city) : !Object.values(CITY).some((c) => f.operator.includes(c))) &&
    (() => { const fl = lineNorm(f.line); return fl === line || fl.includes(line) || line.includes(fl); })());
}
function slotOf(h, mi) { let m = h * 60 + mi; if (m < 60) m += 1440; else if (m < 330) return 0; return Math.min(38, Math.max(0, Math.floor((m - 330) / 30))); }
const slotLabel = (i) => clock(330 + i * 30);
const describe = (p) => (p < 0 ? "정보 없음" : p < 60 ? "여유" : p < 100 ? "보통" : p < 130 ? "다소 혼잡" : p < 150 ? "혼잡" : "매우 혼잡");
const congColor = (p) => (p < 0 ? "#BDBDBD" : p < 60 ? "#2E9E5B" : p < 100 ? "#3B7DDD" : p < 130 ? "#F08C00" : "#D7262E");

// ── 찾기 화면 (대중교통 → 지하철) ──
const search = { q: "" };
const stLine = (s) => `<button class="row" ${act(() => go("subway", s.id, { name: s.name, line: s.line }))}>
  <span class="badge" style="background:${subwayColor(s.line)}">${esc(s.line.replace(/^(서울|수도권)\s*/, "").slice(0, 6))}</span><b class="grow">${esc(s.name)}</b></button>`;

export function subwaySearch(root, chipsHtml) {
  root.innerHTML = header("TagoHub", "지하철 시간표") + chipsHtml +
    `<div class="searchbox"><input id="q" type="search" placeholder="지하철역 이름" value="${esc(search.q)}" autocomplete="off"></div>
     <button class="link pad" ${act(() => go("subwaymap"))}>서울 지하철 노선도 보기</button><div id="out"></div>`;
  const out = root.querySelector("#out");
  let seq = 0;
  const draw = async () => {
    const q = search.q.trim();
    if (!q) {
      const fav = Favorites.stations.items, rec = Recent.stations.items;
      out.innerHTML = note("역 이름을 입력해 주세요") + (fav.length ? sectionHead("즐겨찾기") + `<div class="list">${fav.map(stLine).join("")}</div>` : "") +
        (rec.length ? sectionHead("최근 본 역", () => { Recent.stations.clear(); draw(); }) + `<div class="list">${rec.map(stLine).join("")}</div>` : "");
      return;
    }
    const my = ++seq;
    out.innerHTML = note("찾는 중...");
    const r = await searchStations(q).catch(() => null);
    if (my !== seq) return;
    out.innerHTML = !r ? note("역 정보를 받지 못했어요") : !r.length ? note("찾는 역이 없어요") : `<div class="list">${r.map(stLine).join("")}</div>`;
  };
  bindInput(root, "#q", (v) => { search.q = v; draw(); }, 350);
  draw();
}

// ── 역 화면 ──
export function stationScreen(root, p) {
  const s = { id: p.id, name: p.name || "역", line: p.line || "" };
  Recent.addStation(s);
  let alive = true, tab = 0, day = dayCode(isoDay()), all = false, up = null, down = null, failed = false, live = undefined, congDay = ["W", "W", "W", "W", "W", "S", "U"][isoDay() - 1];
  const realtime = !!lineIdOf(s);
  const timers = [];
  const segs = (list, cur, set) => `<div class="segs">${list.map(([k, l]) => `<button class="${k === cur ? "on" : ""}" ${act(() => { set(k); draw(); })}>${l}</button>`).join("")}</div>`;
  const loadSched = async () => {
    up = down = null; failed = false; draw();
    try { [up, down] = await Promise.all([schedule(s.id, day, "U"), schedule(s.id, day, "D")]); } catch (e) { failed = true; }
    draw();
  };
  function liveHtml() {
    if (!realtime) return "";
    let h = `<div class="shead live">실시간 도착</div>`;
    if (live === undefined) return h + note("실시간 도착을 불러오는 중...");
    if (!live || !live.length) return h + note("지금 실시간 도착 정보가 없어요");
    const by = new Map();
    live.forEach((a) => { if (!by.has(a.upDown)) by.set(a.upDown, []); if (by.get(a.upDown).length < 2) by.get(a.upDown).push(a); });
    return h + `<div class="list">${[...by.values()].flat().map((a) => {
      const soon = ["진입", "도착", "전역 출발", "전역 진입", "전역 도착"].includes(a.text) || (a.seconds ?? 1e9) <= 180;
      const right = a.seconds >= 60 && !a.text.endsWith("전역") ? Math.floor(a.seconds / 60) + "분" : a.text;
      return `<div class="row"><span class="grow"><b>${esc((a.dest ? a.dest + "행" : a.lineText) + (a.express ? " (급행)" : "") + (a.last ? " · 막차" : ""))}</b><small>${esc(a.upDown)}</small></span><b class="eta ${soon ? "soon" : ""}">${esc(right)}</b></div>`;
    }).join("")}</div>`;
  }
  async function draw() {
    if (!alive) return;
    let h = header(s.name, s.line, { back: true, star: { on: Favorites.hasStation(s), toggle: () => { Favorites.toggleStation(s); draw(); } } }) +
      chips(["시간표", "혼잡도", "시설"].map((l, i) => ({ label: l, on: tab === i, run: () => { tab = i; draw(); } })));
    if (tab === 1) {
      h += segs([["W", "평일"], ["S", "토요일"], ["U", "일요일"]], congDay, (k) => (congDay = k));
      root.innerHTML = h + `<div id="c">${note("혼잡도를 불러오는 중...")}</div>`;
      const seoulArea = !Object.keys(CITY).some((k) => s.id.startsWith(k));
      const list = seoulArea ? ((await congestionTable().catch(() => new Map())).get(`${congDay}|${s.line.trim()}|${stationNorm(s.name)}`) || []) : [];
      const box = root.querySelector("#c");
      if (!box) return;
      if (!list.length) { box.innerHTML = note("이 역은 혼잡도 자료가 없어요 (서울교통공사 1~8호선만 있어요)"); return; }
      const t = seoulNow(), slot = slotOf(t.h, t.mi);
      box.innerHTML = `<div class="list">${list.map((x) => {
        const v = x.values[slot] ?? -1, top = Math.max(100, ...x.values);
        return `<div class="row col"><span class="flexrow"><b class="grow">${esc(x.dir)}</b><b style="color:${congColor(v)}">${slotLabel(slot)} ${v >= 0 ? v + "% · " : ""}${describe(v)}</b></span>
          <span class="cbars">${x.values.map((p2, i) => `<i style="height:${Math.max(2, Math.min(100, (Math.max(0, p2) / top) * 100))}%;background:${congColor(p2)};opacity:${i === slot ? 1 : 0.45}"></i>`).join("")}</span>
          <span class="cticks"><small>05:30</small><small>12:00</small><small>18:00</small><small>00:30</small></span></div>`;
      }).join("")}</div>` + note("과거 평균 자료예요 (정원 대비 %). 실제와 다를 수 있어요.");
      return;
    }
    if (tab === 2) {
      root.innerHTML = h + `<div id="c">${note("역 시설 정보를 불러오는 중...")}</div>`;
      const list = await facilities(s).catch(() => []);
      const box = root.querySelector("#c");
      if (!box) return;
      if (!list.length) { box.innerHTML = note("이 역은 시설 정보가 없어요"); return; }
      const groups = new Map();
      list.forEach((f) => { if (!groups.has(f.category)) groups.set(f.category, new Set()); groups.get(f.category).add(f.detail); });
      box.innerHTML = [...groups].map(([c, ds]) => sectionHead(c) + `<div class="list">${[...ds].map((d) => `<div class="row">${esc(d)}</div>`).join("")}</div>`).join("");
      return;
    }
    h += `<div class="toolrow">${segs([["01", "평일"], ["02", "토요일"], ["03", "일·공휴일"]], day, (k) => { day = k; loadSched(); })}<button class="link" ${act(() => { all = !all; draw(); })}>${all ? "다음 열차만" : "전체 시간표"}</button></div>`;
    h += liveHtml();
    if (failed) { root.innerHTML = h + note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return; }
    if (!up || !down) { root.innerHTML = h + note("시간표를 불러오는 중..."); return; }
    const today = day === dayCode(isoDay());
    const t = seoulNow(), nowSec = (t.h * 3600 + t.mi * 60 + new Date().getSeconds()) + (t.h < 3 ? 86400 : 0);
    for (const [title, deps] of [["상행", up], ["하행", down]]) {
      const counts = new Map(); deps.forEach((d) => counts.set(d.end, (counts.get(d.end) || 0) + 1));
      const main = [...counts].sort((a, b) => b[1] - a[1])[0]?.[0];
      h += sectionHead(title + (main ? ` · ${main} 방면` : ""));
      const upcoming = today ? deps.filter((d) => d.min * 60 >= nowSec - 30) : deps;
      const shown = all ? deps : upcoming.slice(0, 6);
      if (!shown.length) { h += note(deps.length ? "오늘 운행이 끝났어요" : "시간표가 없어요"); continue; }
      h += `<div class="list">${shown.map((d) => {
        const left = d.min * 60 - nowSec, gone = today && left < -30;
        const lt = today && !gone && left < 3600 ? (left <= 60 ? "곧 도착" : left < 180 ? `${Math.floor(left / 60)}분 ${pad2(left % 60)}초` : Math.floor(left / 60) + "분") : "";
        return `<div class="row ${gone ? "gone" : ""}"><b class="clock">${clock(d.min)}</b><span class="grow muted">${esc(d.end + (d.arrivalOnly ? " (종착)" : ""))}행</span>${lt ? `<b class="eta-m ${left <= 180 ? "soon" : ""}">${lt}</b>` : ""}</div>`;
      }).join("")}</div>`;
    }
    const y = root.scrollTop;
    root.innerHTML = h;
    root.scrollTop = y;
  }
  loadSched();
  if (realtime) {
    const tick = async () => { try { live = await seoulLive(s); } catch (e) { live = live || []; } if (tab === 0) draw(); };
    tick();
    timers.push(setInterval(tick, 20000));
  }
  timers.push(setInterval(() => { if (tab === 0 && !all) draw(); }, 15000));
  return () => { alive = false; timers.forEach(clearInterval); };
}

// ── 서울 지하철 노선도 (그림, 끌어서 옮기기·휠로 확대, 역 이름을 누르면 그 역 찾기) ──
export function subwayMap(root) {
  root.innerHTML = header("서울 지하철 노선도", "역 이름을 누르면 그 역 찾기", { back: true }) +
    `<div class="mapimg"><img alt="서울 지하철 노선도" draggable="false"><span class="hint">끌어서 옮기기 · 휠·두 손가락으로 확대</span></div>`;
  const box = root.querySelector(".mapimg"), img = box.querySelector("img");
  let spots = [], scale = 0, ox = 0, oy = 0;
  getText("data/files/seoul_metro_map_hotspots.tsv").then((t) => {
    spots = t.split("\n").map((l) => l.split("\t")).filter((f) => f.length >= 3 && Number.isFinite(+f[1])).map((f) => [f[0].trim(), +f[1], +f[2]]);
  }).catch(() => {});
  const apply = () => { img.style.transform = `translate(${ox}px, ${oy}px) scale(${scale})`; };
  const fit = () => Math.min(box.clientWidth / img.naturalWidth, box.clientHeight / img.naturalHeight);
  img.onload = () => { scale = fit(); ox = (box.clientWidth - img.naturalWidth * scale) / 2; oy = (box.clientHeight - img.naturalHeight * scale) / 2; apply(); };
  img.onerror = () => { box.innerHTML = note("노선도를 받지 못했어요"); };
  img.src = BASE + "data/files/seoul_metro_map.jpg";
  const zoomAt = (cx, cy, k) => { const s2 = Math.min(2.5, Math.max(fit(), scale * k)); const r = s2 / scale; ox = cx - (cx - ox) * r; oy = cy - (cy - oy) * r; scale = s2; apply(); };
  box.addEventListener("wheel", (e) => { e.preventDefault(); const b = box.getBoundingClientRect(); zoomAt(e.clientX - b.left, e.clientY - b.top, e.deltaY < 0 ? 1.2 : 1 / 1.2); }, { passive: false });
  const pts = new Map(); let moved = 0, lastDist = 0;
  box.addEventListener("pointerdown", (e) => { box.setPointerCapture(e.pointerId); pts.set(e.pointerId, [e.clientX, e.clientY]); moved = 0; lastDist = 0; });
  box.addEventListener("pointermove", (e) => {
    if (!pts.has(e.pointerId)) return;
    const [px, py] = pts.get(e.pointerId);
    pts.set(e.pointerId, [e.clientX, e.clientY]);
    if (pts.size === 2) {
      const [a, b] = [...pts.values()], d = Math.hypot(a[0] - b[0], a[1] - b[1]), bb = box.getBoundingClientRect();
      if (lastDist) zoomAt((a[0] + b[0]) / 2 - bb.left, (a[1] + b[1]) / 2 - bb.top, d / lastDist);
      lastDist = d; moved += 10; return;
    }
    ox += e.clientX - px; oy += e.clientY - py; moved += Math.abs(e.clientX - px) + Math.abs(e.clientY - py); apply();
  });
  box.addEventListener("pointerup", (e) => {
    pts.delete(e.pointerId);
    if (moved > 6 || !scale || !spots.length) return;
    const b = box.getBoundingClientRect();
    const ix = (e.clientX - b.left - ox) / scale / img.naturalWidth, iy = (e.clientY - b.top - oy) / scale / img.naturalHeight;
    let best = null, bd = Infinity;
    for (const sp of spots) { const d = (sp[1] - ix) ** 2 + (sp[2] - iy) ** 2; if (d < bd) { bd = d; best = sp; } }
    if (best) { const dx = (best[1] - ix) * img.naturalWidth * scale, dy = (best[2] - iy) * img.naturalHeight * scale; if (dx * dx + dy * dy < 1600) { search.q = best[0]; location.hash = "#/transit/subway"; } }
  });
  box.addEventListener("pointercancel", (e) => pts.delete(e.pointerId));
}
