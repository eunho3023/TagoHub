// 기차(서버에 올린 한국철도공사 시간표 + 국토교통부 종류·요금)와 고속·시외버스(국토교통부) 시간표.
import { getText, xmlRows, esc, seoulNow, addDays, ymd, isoDay } from "./core.js";
import { act, header, note } from "./ui.js";
import { clock } from "./subway.js";

const duration = (min) => (min >= 60 ? `${Math.floor(min / 60)}시간${min % 60 ? " " + (min % 60) + "분" : ""}` : min + "분");
const won = (n) => n.toLocaleString("ko-KR") + "원";
const WEEK = "월화수목금토일";

/** 오늘·내일 칸 + 날짜 */
function dayRow(st, redraw) {
  const t = st.tomorrow ? addDays(seoulNow(), 1) : seoulNow();
  return `<div class="toolrow"><div class="segs">${[["오늘", false], ["내일", true]].map(([l, v]) => `<button class="${st.tomorrow === v ? "on" : ""}" ${act(() => { st.tomorrow = v; redraw(); })}>${l}</button>`).join("")}</div>
    <small>${t.m}월 ${t.d}일 (${WEEK[isoDay(t) - 1]})</small></div>`;
}
/** 출발·도착 칸 */
function boxes(st, depName, arrName, redraw) {
  const b = (k, title, name) => `<button class="pbox ${st.editing === k ? "on" : ""}" ${act(() => { st.editing = k; st.q = ""; redraw(); })}><small>${title}</small><b>${esc(name)}</b></button>`;
  return `<div class="pboxes">${b("dep", "출발", depName)}<button class="swap" ${act(() => { [st.dep, st.arr] = [st.arr, st.dep]; redraw(); })}>⇄</button>${b("arr", "도착", arrName)}</div>`;
}
/** 편 목록: 지난 것은 흐리게, 다음 편으로 내려감 */
function tripList(root, list, now, row) {
  const next = Math.max(0, list.findIndex((t) => t.depMin >= now));
  const left = list.filter((t) => t.depMin >= now).length;
  const html = `<p class="note">${list.length}편${now >= 0 ? ` · 남은 ${left}편` : ""}</p><div class="list">${list.map((t, i) => `<div class="row ${t.depMin < now ? "gone" : ""}" data-i="${i}">${row(t)}</div>`).join("")}</div>`;
  return { html, after: () => root.querySelector(`[data-i="${next}"]`)?.scrollIntoView({ block: "start" }) };
}

// ── 기차 ──
const norm = (n) => { const t = n.replace(/\(.*?\)/g, "").replace(/\s+/g, ""); return t === "여수엑스포" || t === "여수EXPO" ? "여수EXPO" : t === "평택지제" ? "지제" : t; };
let trainsP = null, stationsP = null;
function trains() {
  if (!trainsP) trainsP = getText("data/files/train_timetable.tsv").then((t) => t.split("\n").map((l) => {
    const f = l.split("\t");
    if (f.length < 4 || !f[0].trim()) return null;
    const stops = f[3].split(";").map((s) => { const i = s.lastIndexOf("@"); const n = s.slice(0, i), m = parseInt(s.slice(i + 1), 10); return i > 0 && n.trim() && Number.isFinite(m) ? [n, m] : null; }).filter(Boolean);
    return stops.length < 2 ? null : { no: f[0].trim(), kind: f[1].trim(), remark: f[2].trim(), stops, keys: stops.map((s) => norm(s[0])) };
  }).filter(Boolean));
  return trainsP;
}
function trainStations() {
  if (!stationsP) stationsP = Promise.all([trains(), getText("data/files/train_stations.tsv")]).then(([tr, t]) => {
    const known = new Set(tr.flatMap((x) => x.keys)), seen = new Set();
    return t.split("\n").map((l) => l.split("\t")).filter((f) => f.length >= 3 && f[0].startsWith("NAT"))
      .map((f) => ({ id: f[0], name: f[1].trim(), region: f[2].trim() })).filter((s) => known.has(norm(s.name)) && !seen.has(norm(s.name)) && seen.add(norm(s.name)));
  });
  return stationsP;
}
function runsOn(remark, d) {
  const r = remark.replace(/^실적/, "");
  if (r === "평일") return d < 6;
  if (r === "토") return d === 6;
  if (r === "일") return d === 7;
  return !!r && [...r].every((c) => WEEK.includes(c)) && r.includes(WEEK[d - 1]);
}
async function trainInfo(depId, arrId, day) {
  try {
    const rows = xmlRows(await getText("w/dg/1613000/TrainInfo/GetStrtpntAlocFndTrainInfo", { depPlaceId: depId, arrPlaceId: arrId, depPlandTime: day }));
    return new Map(rows.filter((r) => r.trainno).map((r) => [r.trainno.replace(/^0+/, "") || "0", [r.traingradename || null, parseInt(r.adultcharge, 10) > 0 ? parseInt(r.adultcharge, 10) : null]]));
  } catch (e) { return new Map(); }
}

const train = { dep: "서울", arr: "부산", tomorrow: false, editing: null, q: "" };
export function trainScreen(root, chipsHtml) {
  let alive = true, trips = null, failed = false, stations = [];
  trainStations().then((s) => { stations = s; if (train.editing) draw(); }).catch(() => {});
  const load = async () => {
    trips = null; failed = false; draw();
    const t = train.tomorrow ? addDays(seoulNow(), 1) : seoulNow(), d = isoDay(t);
    try {
      const dk = norm(train.dep), ak = norm(train.arr), seen = new Set();
      trips = (await trains()).map((x) => {
        if (!runsOn(x.remark, d)) return null;
        const i = x.keys.indexOf(dk), j = x.keys.indexOf(ak);
        return i < 0 || j <= i ? null : { no: x.no, kind: x.kind || null, depMin: x.stops[i][1], arrMin: x.stops[j][1], fare: null };
      }).filter((x) => x && !seen.has(x.no + "|" + x.depMin) && seen.add(x.no + "|" + x.depMin)).sort((a, b) => a.depMin - b.depMin);
    } catch (e) { failed = true; trips = []; }
    draw();
    const st2 = await trainStations().catch(() => []);
    const ds = st2.find((s) => norm(s.name) === norm(train.dep)), as = st2.find((s) => norm(s.name) === norm(train.arr));
    if (ds && as && trips.length) {
      const info = await trainInfo(ds.id, as.id, ymd(t));
      if (info.size && alive) { trips = trips.map((x) => { const i = info.get(x.no.replace(/^0+/, "") || "0"); return i ? { ...x, kind: i[0] || x.kind, fare: i[1] ?? x.fare } : x; }); draw(); }
    }
  };
  function draw() {
    if (!alive) return;
    let h = header("모두의 지도", "기차 시간표") + chipsHtml + `<div class="searchtop">${boxes(train, train.dep, train.arr, () => { if (!train.editing) load(); else draw(); })}${dayRow(train, load)}</div>`;
    if (train.editing) {
      const q = norm(train.q);
      const shown = stations.filter((s) => !q || norm(s.name).includes(q)).sort((a, b) => (norm(a.name).startsWith(q) ? 0 : 1) - (norm(b.name).startsWith(q) ? 0 : 1) || a.name.length - b.name.length).slice(0, 80);
      root.innerHTML = h + `<div class="searchbox"><input id="q" type="search" placeholder="${train.editing === "dep" ? "출발역" : "도착역"} 이름" value="${esc(train.q)}" autocomplete="off"></div>
        <div class="list">${shown.map((s) => `<button class="row" ${act(() => { train[train.editing] = s.name; train.editing = null; load(); })}><span class="grow"><b>${esc(s.name)}</b><small>${esc(s.region)}</small></span></button>`).join("")}</div>`;
      const inp = root.querySelector("#q");
      inp.focus({ preventScroll: true });
      inp.oninput = () => { train.q = inp.value; const pos = inp.selectionStart; draw(); const i2 = root.querySelector("#q"); i2.focus(); i2.setSelectionRange(pos, pos); };
      return;
    }
    if (!trips) { root.innerHTML = h + note("시간표를 불러오는 중..."); return; }
    if (failed) { root.innerHTML = h + note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return; }
    if (!trips.length) { root.innerHTML = h + note(`${train.dep} → ${train.arr} 그날 열차가 시간표에 없어요`); return; }
    const t = seoulNow(), now = train.tomorrow ? -1 : t.h * 60 + t.mi;
    const r = tripList(root, trips, now, (x) => `<span class="grow"><b class="trip">${clock(x.depMin)}  →  ${clock(x.arrMin)}</b><small>${duration(x.arrMin - x.depMin)}</small></span>
      <span class="right"><b class="sm">${esc([x.kind, x.no].filter(Boolean).join(" "))}</b>${x.fare ? `<small>${won(x.fare)}</small>` : ""}</span>`);
    root.innerHTML = h + r.html;
    r.after();
  }
  load();
  return () => { alive = false; };
}

// ── 고속·시외버스 ──
let termP = null;
function terminals() {
  if (!termP) termP = getText("data/files/coach_terminals.tsv").then((t) => {
    const seen = new Set();
    return t.split("\n").map((l) => l.split("\t")).filter((f) => f.length >= 3 && f[0].startsWith("NA")).map((f) => ({ id: f[0], name: f[1].trim(), region: f[2].trim() })).filter((x) => !seen.has(x.id) && seen.add(x.id));
  });
  return termP;
}
const express = (t) => t.id.startsWith("NAEK");
async function suburbTerminals(q) {
  try { return xmlRows(await getText("w/dg/1613000/SuburbsBusInfo/GetSuberbsBusTrminlList", { terminalNm: q.trim() })).filter((r) => r.terminalid).map((r) => ({ id: r.terminalid, name: r.terminalnm || "", region: r.cityname || "" })); }
  catch (e) { return []; }
}
const planMinute = (s) => { const d = (s || "").replace(/\D/g, ""); return d.length >= 12 ? +d.slice(8, 10) * 60 + +d.slice(10, 12) : null; };
async function coachTrips(dep, arr, day) {
  const path = express(dep) && express(arr) ? "w/dg/1613000/ExpBusInfo/GetStrtpntAlocFndExpbusInfo" : !express(dep) && !express(arr) ? "w/dg/1613000/SuburbsBusInfo/GetStrtpntAlocFndSuberbsBusInfo" : null;
  if (!path) return [];
  const rows = xmlRows(await getText(path, { depTerminalId: dep.id, arrTerminalId: arr.id, depPlandTime: day }));
  const seen = new Set();
  return rows.map((r) => {
    const d = planMinute(r.depplandtime); if (d == null) return null;
    let a = planMinute(r.arrplandtime); if (a != null && a < d) a += 1440;
    return { depMin: d, arrMin: a, grade: (r.gradenm || "").trim() || "일반", fare: parseInt(r.charge, 10) > 0 ? parseInt(r.charge, 10) : null };
  }).filter((x) => x && !seen.has(x.depMin + "|" + x.grade) && seen.add(x.depMin + "|" + x.grade)).sort((a, b) => a.depMin - b.depMin);
}

const coach = { dep: null, arr: null, tomorrow: false, editing: "dep", q: "" };
export function coachScreen(root, chipsHtml) {
  let alive = true, trips = null, failed = false, list = [], extra = [], seq = 0;
  terminals().then((l) => { list = l; if (coach.editing) draw(); }).catch(() => {});
  const load = async () => {
    trips = null; failed = false; draw();
    if (!coach.dep || !coach.arr) return;
    const t = coach.tomorrow ? addDays(seoulNow(), 1) : seoulNow();
    try { trips = await coachTrips(coach.dep, coach.arr, ymd(t)); } catch (e) { failed = true; trips = []; }
    draw();
  };
  function draw() {
    if (!alive) return;
    let h = header("모두의 지도", "고속·시외버스 시간표") + chipsHtml + `<div class="searchtop">${boxes(coach, coach.dep?.name || "터미널 고르기", coach.arr?.name || "터미널 고르기", () => { if (!coach.editing) load(); else draw(); })}${dayRow(coach, load)}</div>`;
    if (coach.editing) {
      const q = coach.q.replace(/ /g, ""), other = coach.editing === "arr" ? coach.dep : coach.arr, seen = new Set();
      const shown = [...list, ...extra].filter((x) => !seen.has(x.id) && seen.add(x.id))
        .filter((x) => !q || x.name.replace(/ /g, "").includes(q) || x.region.includes(q)).filter((x) => !other || express(x) === express(other))
        .sort((a, b) => (a.name.startsWith(q) ? 0 : 1) - (b.name.startsWith(q) ? 0 : 1) || (express(a) ? 0 : 1) - (express(b) ? 0 : 1) || a.name.length - b.name.length).slice(0, 80);
      root.innerHTML = h + `<div class="searchbox"><input id="q" type="search" placeholder="${coach.editing === "dep" ? "출발" : "도착"} 터미널 이름" value="${esc(coach.q)}" autocomplete="off"></div>
        <div class="list">${shown.map((x) => `<button class="row" ${act(() => {
          if (coach.editing === "dep") { coach.dep = x; if (coach.arr && express(coach.arr) !== express(x)) coach.arr = null; coach.editing = coach.arr ? null : "arr"; }
          else { coach.arr = x; coach.editing = null; }
          coach.q = ""; extra = []; coach.editing ? draw() : load();
        })}><span class="grow"><b>${esc(x.name)}</b><small>${esc(x.region)}</small></span><small class="blue">${express(x) ? "고속" : "시외"}</small></button>`).join("")}</div>`;
      const inp = root.querySelector("#q");
      inp.focus({ preventScroll: true });
      inp.oninput = () => {
        coach.q = inp.value; const pos = inp.selectionStart; draw(); const i2 = root.querySelector("#q"); i2.focus(); i2.setSelectionRange(pos, pos);
        const my = ++seq, qq = coach.q.trim();
        if (qq.length >= 2) setTimeout(async () => { if (my !== seq) return; extra = await suburbTerminals(qq); if (my === seq && coach.editing) { const p2 = root.querySelector("#q")?.selectionStart; draw(); const i3 = root.querySelector("#q"); i3?.focus(); i3?.setSelectionRange(p2, p2); } }, 400);
      };
      return;
    }
    if (!coach.dep || !coach.arr) { root.innerHTML = h + note("출발·도착 터미널을 골라 주세요"); return; }
    if (failed) { root.innerHTML = h + note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return; }
    if (!trips) { root.innerHTML = h + note("시간표를 불러오는 중..."); return; }
    if (!trips.length) { root.innerHTML = h + note(`${coach.dep.name} → ${coach.arr.name} 그날 버스가 없어요`); return; }
    const t = seoulNow(), now = coach.tomorrow ? -1 : t.h * 60 + t.mi;
    const r = tripList(root, trips, now, (x) => `<span class="grow"><b class="trip">${clock(x.depMin)}${x.arrMin != null ? "  →  " + clock(x.arrMin) : ""}</b>${x.arrMin != null ? `<small>${duration(x.arrMin - x.depMin)}</small>` : ""}</span>
      <span class="right"><b class="sm">${esc(x.grade)}</b>${x.fare ? `<small>${won(x.fare)}</small>` : ""}</span>`);
    root.innerHTML = h + r.html;
    r.after();
  }
  coach.editing ? draw() : load();
  return () => { alive = false; };
}
