// 연안여객선: 항구(ferry_ports.json)·항로(ferry_lines.json, 들르는 항구 순서·소요 시간)는 서버에 올린 앱과 같은 파일,
// 그날 운항(출항 시각·배 이름·정상/통제·통제 사유)은 한국해양교통안전공단 실시간 자료 (오늘·내일만). 앱 data/Ferry.kt 와 같은 규칙
import { getText, esc, seoulNow, addDays, ymd, onReturn, cachedText } from "./core.js";
import { act, header, note } from "./ui.js";
import { clock } from "./subway.js";
import { dayRow, boxes, tripList, duration, RecentRoutes, recentRow } from "./trips.js";

let dataP = null;
/** {ports: Map(코드 → {code, name, sido}), lines: {키 → {name, fwd, rev, stops: [[코드, 정박]]}}, places: 고를 수 있는 항구} */
function ferryData() {
  if (!dataP) dataP = Promise.all([cachedText("data/files/ferry_ports.json"), cachedText("data/files/ferry_lines.json")]).then(([p, l]) => {
    const ports = new Map((JSON.parse(p) || []).filter((r) => r[0] && r[1]).map((r) => [r[0], { code: r[0], name: r[1], sido: shortSido(r[4] || "") }]));
    const lines = {};
    for (const [k, v] of Object.entries(JSON.parse(l) || {})) lines[k] = { name: v.n || "", fwd: v.f || 0, rev: v.r || 0, stops: (v.s || []).map((s) => [s[0], s[1] || "YY"]) };
    const used = new Set(Object.values(lines).flatMap((x) => x.stops.map((s) => s[0])));
    const places = [...ports.values()].filter((x) => used.has(x.code)).sort((a, b) => a.name.localeCompare(b.name, "ko"));
    return { ports, lines, places };
  }).catch((e) => { dataP = null; throw e; });
  return dataP;
}
/** "전라남도" → "전남", "인천광역시" → "인천" (앱 FerryClient.shortSido) */
function shortSido(s) {
  const t = s.replace("특별자치도", "").replace("특별자치시", "").replace("광역시", "").replace("특별시", "");
  return t.length === 4 && t.endsWith("도") ? t[0] + t[2] : t.replace(/도$/, "");
}
/** 출발 항구와 같은 항로에 있는 항구 (도착으로 고를 수 있는 곳) */
function connected(lines, code) {
  const out = new Set();
  for (const l of Object.values(lines)) if (l.stops.some((s) => s[0] === code)) l.stops.forEach((s) => out.add(s[0]));
  out.delete(code);
  return out;
}

const sailMemo = new Map();
/** 그날 운항 목록 (1분 기억) */
async function sailings(day) {
  const hit = sailMemo.get(day);
  if (hit && Date.now() - hit.at < 60000) return hit.p;
  const p = getText("w/dg/B554035/oprt-schd-info-v2/get-oprt-schd-info-v2", { rlvtYmd: day }).then((t) => {
    if (/NOT_FOUND_DATA/.test(t)) return [];
    const root = JSON.parse(t)?.response;
    const code = String(root?.header?.resultCode ?? "");
    if (code && code !== "200" && code !== "00" && code !== "0") throw new Error(root?.header?.resultMsg || code);
    const items = root?.body?.items?.item;
    return (Array.isArray(items) ? items : items ? [items] : []).filter((m) => m.oport_cd && m.dest_cd).map((m) => ({
      time: String(m.sail_tm || "").padStart(4, "0"), ship: m.psnshp_nm || "", from: m.oport_cd, fromName: m.oport_nm || "", to: m.dest_cd, toName: m.dest_nm || "",
      lineKey: (m.lcns_seawy_cd || "") + "|" + (m.nvg_seawy_cd || ""), reverse: String(m.nvg_drc_cd) === "2", route: m.nvg_seawy_nm || m.lcns_seawy_nm || "",
      kind: m.nvg_se_nm || "", state: m.nvg_stts_nm || "", reason: [m.cntrl_rsn_nm, m.nnavi_rsn_nm, m.cnls_etc_rsn].find((v) => v && v !== "null") || null,
    }));
  });
  sailMemo.set(day, { at: Date.now(), p });
  p.catch(() => sailMemo.delete(day));
  return p;
}
/** 이 운항이 들르는 항구 (출항지부터 도착지까지). 역방향은 거꾸로, 정박 표시가 N 인 곳은 건너뜀 (앱 Ferry.stopsOf) */
function stopsOf(s, line) {
  const pair = [s.from, s.to];
  if (!line) return pair;
  const seq = s.reverse ? [...line.stops].reverse() : line.stops;
  const i = seq.findIndex((x) => x[0] === s.from);
  if (i < 0) return pair;
  let j = -1;
  for (let k = i + 1; k < seq.length; k++) if (seq[k][0] === s.to) { j = k; break; }
  if (j < 0) return pair;
  const flag = s.reverse ? 1 : 0;
  return seq.slice(i, j + 1).filter((x, k) => k === 0 || k === j - i || x[1][flag] !== "N").map((x) => x[0]);
}
/** "통제(풍랑주의보)" / "운항 안 함" / "운항 완료" / null (앱 Ferry.status) */
function status(s) {
  if (s.kind === "통제") return "통제" + (s.reason ? `(${s.reason})` : "");
  if (s.kind === "비운") return "운항 안 함" + (s.reason ? `(${s.reason})` : "");
  if (s.state === "완료") return "운항 완료";
  if (s.state === "출항중") return "운항 중";
  return s.kind === "증회" || s.kind === "증선" ? s.kind : null;
}
/** dep 에서 출항해 arr 에 들르는 그날 운항 (도착 시각은 arr 이 종착이고 소요 시간을 알 때만) */
function trips(list, lines, dep, arr) {
  return list.map((s) => {
    if (s.from !== dep) return null;
    const line = lines[s.lineKey], stops = stopsOf(s, line);
    if (!stops.slice(1).includes(arr)) return null;
    const depMin = parseInt(s.time.slice(0, 2), 10) * 60 + parseInt(s.time.slice(2, 4), 10);
    if (!Number.isFinite(depMin)) return null;
    const min = line ? (s.reverse ? line.rev : line.fwd) : 0;
    const via = stops.length - 2;
    return { depMin, arrMin: arr === s.to && min > 0 ? depMin + min : null, ship: s.ship, route: s.route, status: status(s), via };
  }).filter(Boolean).sort((a, b) => a.depMin - b.depMin);
}

const lastFerry = RecentRoutes.load("ferry")[0];
const ferry = { dep: lastFerry?.dep || null, arr: lastFerry?.arr || null, tomorrow: false, editing: lastFerry ? null : "dep", q: "" };
export function ferryScreen(root, chipsHtml) {
  let alive = true, list = null, failed = false, data = null;
  ferryData().then((d) => { data = d; if (alive) (ferry.editing ? draw() : load()); }).catch(() => { failed = true; draw(); });
  const load = async () => {
    list = null; failed = false; draw();
    if (!ferry.dep || !ferry.arr || !data) return;
    RecentRoutes.add("ferry", ferry.dep, ferry.arr);
    const t = ferry.tomorrow ? addDays(seoulNow(), 1) : seoulNow();
    try { list = trips(await sailings(ymd(t)), data.lines, ferry.dep.code, ferry.arr.code); } catch (e) { failed = true; list = []; }
    draw();
  };
  function draw() {
    if (!alive) return;
    const h = header("타볼까", "여객선 운항") + chipsHtml + `<div class="searchtop">${boxes(ferry, ferry.dep?.name || "항구 고르기", ferry.arr?.name || "항구 고르기", () => { if (!ferry.editing) load(); else draw(); })}${dayRow(ferry, load)}${ferry.editing ? "" : recentRow("ferry", (x) => x.name, (r) => { ferry.dep = r.dep; ferry.arr = r.arr; load(); })}</div>`;
    if (ferry.editing) {
      if (!data) { root.innerHTML = h + note(failed ? "항구 목록을 받지 못했어요. 잠시 뒤 다시 시도해 주세요." : "항구 목록을 불러오는 중..."); return; }
      const q = ferry.q.replace(/\s/g, ""), other = ferry.editing === "arr" ? ferry.dep : ferry.arr;
      const ok = other ? connected(data.lines, other.code) : null;
      const shown = data.places.filter((x) => (!ok || ok.has(x.code)) && (!q || x.name.includes(q) || x.sido.includes(q)))
        .sort((a, b) => (a.name.startsWith(q) ? 0 : 1) - (b.name.startsWith(q) ? 0 : 1) || a.name.length - b.name.length).slice(0, 120);
      root.innerHTML = h + `<div class="searchbox"><input id="q" type="search" placeholder="${ferry.editing === "dep" ? "출발" : "도착"} 항구 이름" value="${esc(ferry.q)}" autocomplete="off"></div>
        ${other ? note(`${other.name}에서 배로 갈 수 있는 항구만 보여요`) : ""}
        <div class="list">${shown.map((x) => `<button class="row" ${act(() => {
          if (ferry.editing === "dep") { ferry.dep = x; if (ferry.arr && !connected(data.lines, x.code).has(ferry.arr.code)) ferry.arr = null; ferry.editing = ferry.arr ? null : "arr"; }
          else { ferry.arr = x; ferry.editing = null; }
          ferry.q = ""; ferry.editing ? draw() : load();
        })}><span class="grow"><b>${esc(x.name)}</b><small>${esc(x.sido)}</small></span></button>`).join("")}</div>`;
      const inp = root.querySelector("#q");
      inp.focus({ preventScroll: true });
      inp.oninput = () => { ferry.q = inp.value; const pos = inp.selectionStart; draw(); const i2 = root.querySelector("#q"); i2.focus(); i2.setSelectionRange(pos, pos); };
      return;
    }
    if (!ferry.dep || !ferry.arr) { root.innerHTML = h + note("출발·도착 항구를 골라 주세요"); return; }
    if (failed) { root.innerHTML = h + note("운항 정보를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return; }
    if (!list) { root.innerHTML = h + note("운항 정보를 불러오는 중..."); return; }
    if (!list.length) { root.innerHTML = h + note(`${ferry.dep.name} → ${ferry.arr.name} 그날 운항이 없어요`); return; }
    const t = seoulNow(), now = ferry.tomorrow ? -1 : t.h * 60 + t.mi;
    const r = tripList(root, list, now, (x) => `<span class="grow"><b class="trip">${clock(x.depMin)}${x.arrMin != null ? "  →  " + clock(x.arrMin) : ""}</b>
      <small>${esc([x.arrMin != null ? duration(x.arrMin - x.depMin) : null, x.via > 0 ? `경유 ${x.via}곳` : null, x.route].filter(Boolean).join(" · "))}</small></span>
      <span class="right"><b class="sm">${esc(x.ship)}</b>${x.status ? `<small class="${/통제|안 함/.test(x.status) ? "red" : ""}">${esc(x.status)}</small>` : ""}</span>`);
    root.innerHTML = h + r.html + note("한국해양교통안전공단 운항 정보예요. 날씨에 따라 바뀔 수 있으니 출항 전 선사에 확인해 주세요.");
    r.after();
  }
  draw();
  const offReturn = onReturn(() => { if (alive && failed) load(); });
  return () => { alive = false; offReturn(); };
}
