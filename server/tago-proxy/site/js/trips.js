// 기차(서버에 올린 한국철도공사 시간표 + 국토교통부 종류·요금)와 고속·시외버스(국토교통부) 시간표.
// 앱 TimetablePane 처럼: 출발·도착은 빈칸에서 시작(직접 고른 것만), 아래 날짜와 "시간표 조회" 버튼, 그 아래 최근 조회.
// 조회하면 구간 요약·수정, 날짜 막대(전날·다음날·달력), 편 목록.
import { getText, xmlRows, esc, seoulNow, addDays, ymd, isoDay, pad2, onReturn, cachedText, storeGet, storeSet } from "./core.js";
import { act, header, note, sectionHead } from "./ui.js";
import { clock } from "./subway.js";

export const duration = (min) => (min >= 60 ? `${Math.floor(min / 60)}시간${min % 60 ? " " + (min % 60) + "분" : ""}` : min + "분");
const won = (n) => n.toLocaleString("ko-KR") + "원";
const WEEK = "월화수목금토일";

/**
 * 시·도 이름을 정식 이름으로 ("강원" → "강원특별자치도", "전북" → "전북특별자치도", "인천" → "인천광역시").
 * 광주광역시와 전라남도는 전남광주통합특별시로 합쳐짐 (한국해양교통안전공단 여객선 자료도 이 이름). 모르는 이름은 그대로
 */
const SIDO = [
  [/^서울/, "서울특별시"], [/^부산/, "부산광역시"], [/^대구/, "대구광역시"], [/^인천/, "인천광역시"], [/^(광주|전남광주|전라남|전남)/, "전남광주통합특별시"],
  [/^대전/, "대전광역시"], [/^울산/, "울산광역시"], [/^세종/, "세종특별자치시"], [/^경기/, "경기도"], [/^강원/, "강원특별자치도"],
  [/^(충청북|충북)/, "충청북도"], [/^(충청남|충남)/, "충청남도"], [/^(전라북|전북)/, "전북특별자치도"], [/^(경상북|경북)/, "경상북도"],
  [/^(경상남|경남)/, "경상남도"], [/^제주/, "제주특별자치도"],
];
export function fullSido(s) {
  const t = String(s || "").trim();
  const hit = t && SIDO.find(([re]) => re.test(t));
  return hit ? hit[1] : t;
}
/** 검색: 정식 이름과 줄인 이름(충남·전남·경북…) 둘 다로 찾게 */
const SHORT = { 충청북도: "충북", 충청남도: "충남", 경상북도: "경북", 경상남도: "경남", 전북특별자치도: "전북 전라북도", 전남광주통합특별시: "전남 광주 전라남도" };
export const sidoMatch = (full, q) => !q || full.includes(q) || (SHORT[full] || "").includes(q);

/** 최근 조회한 구간 (앱 RecentRoutes 처럼 수단마다 3개, 이 브라우저에 저장). 조회 버튼을 누른 구간만 남김 */
export const RecentRoutes = {
  load(key) {
    try { const v = JSON.parse(storeGet("recent." + key) || "[]"); return Array.isArray(v) ? v.filter((r) => r && r.dep && r.arr) : []; } catch (e) { return []; }
  },
  add(key, dep, arr) {
    const id = (x) => JSON.stringify(x);
    const next = [{ dep, arr }, ...this.load(key).filter((r) => !(id(r.dep) === id(dep) && id(r.arr) === id(arr)))].slice(0, 3);
    storeSet("recent." + key, JSON.stringify(next));
  },
};

/** 고른 날 (st.day: 오늘부터 며칠 뒤) */
export const dayOf = (st) => addDays(seoulNow(), st.day || 0);
const isoDate = (t) => `${t.y}-${pad2(t.m)}-${pad2(t.d)}`;
const dayLabel = (st) => { const t = dayOf(st); return `${t.y}.${pad2(t.m)}.${pad2(t.d)} (${WEEK[isoDay(t) - 1]})${st.day === 0 ? " · 오늘" : st.day === 1 ? " · 내일" : ""}`; };
/** 날짜 막대: 전날 · 날짜(누르면 달력) · 다음날. max: 오늘부터 며칠 뒤까지 고를 수 있는지 */
export function dateBar(st, max, redraw) {
  const lo = seoulNow(), hi = addDays(lo, max);
  return `<div class="datebar">
    <button class="daystep" aria-label="전날" ${act(() => { st.day = Math.max(0, st.day - 1); redraw(); })} ${st.day <= 0 ? "disabled" : ""}>‹</button>
    <label class="datepick"><b>${dayLabel(st)}</b><input type="date" data-date min="${isoDate(lo)}" max="${isoDate(hi)}" value="${isoDate(dayOf(st))}" aria-label="날짜 고르기"></label>
    <button class="daystep" aria-label="다음날" ${act(() => { st.day = Math.min(max, st.day + 1); redraw(); })} ${st.day >= max ? "disabled" : ""}>›</button></div>`;
}
/** 날짜 막대의 달력 칸 연결 (그린 뒤에 부름) */
export function bindDate(root, st, max, redraw) {
  const inp = root.querySelector("[data-date]");
  if (!inp) return;
  inp.onclick = () => { try { inp.showPicker(); } catch (e) { /* 옛 브라우저는 그냥 칸 */ } };
  inp.onchange = () => {
    const [y, m, d] = inp.value.split("-").map(Number);
    if (!y) return;
    const t = seoulNow(), off = Math.round((Date.UTC(y, m - 1, d) - Date.UTC(t.y, t.m - 1, t.d)) / 86400000);
    st.day = Math.max(0, Math.min(max, off));
    redraw();
  };
}
/** 출발·도착 칸 */
export function boxes(st, depName, arrName, redraw) {
  const b = (k, title, name) => `<button class="pbox ${st.editing === k ? "on" : ""} ${name ? "" : "empty"}" ${act(() => { st.editing = k; st.q = ""; redraw(); })}><small>${title}</small><b>${esc(name || title + " 고르기")}</b></button>`;
  return `<div class="pboxes">${b("dep", "출발", depName)}<button class="swap" aria-label="출발·도착 바꾸기" ${act(() => { [st.dep, st.arr] = [st.arr, st.dep]; redraw(); })}>⇄</button>${b("arr", "도착", arrName)}</div>`;
}
/**
 * 조회 전 화면 (앱처럼): 출발·도착 칸, 날짜, 큰 "시간표 조회" 버튼, 최근 조회.
 * o: {key, depName, arrName, label(최근 구간 이름), max, redraw, search, button}
 */
export function searchForm(st, o) {
  const ready = !!(st.dep && st.arr);
  const recents = RecentRoutes.load(o.key);
  return `<div class="searchtop">${boxes(st, o.depName, o.arrName, o.redraw)}</div>` +
    `<div class="searchform">${sectionHead("가는 날")}${dateBar(st, o.max, o.redraw)}
      <button class="btn searchgo" ${act(() => { if (ready) o.search(); })} ${ready ? "" : "disabled"}>${ready ? o.button || "시간표 조회" : "출발과 도착을 먼저 골라 주세요"}</button></div>` +
    (recents.length ? sectionHead("최근 조회") + `<div class="list">${recents.map((r) =>
      `<button class="row" ${act(() => { st.dep = r.dep; st.arr = r.arr; o.search(); })}><b class="grow">${esc(o.label(r.dep))} → ${esc(o.label(r.arr))}</b></button>`).join("")}</div>` : "");
}
/** 조회 후 위: 구간 요약 + 수정, 날짜 막대 */
export function resultTop(st, depName, arrName, max, redraw) {
  return `<div class="searchtop"><div class="toolrow routehead"><b class="grow">${esc(depName)} → ${esc(arrName)}</b><button class="pillbtn" ${act(() => { st.searched = false; redraw(); })}>수정</button></div>${dateBar(st, max, redraw)}</div>`;
}
/** 고르는 중 위: 출발·도착 칸만 */
export const pickTop = (st, depName, arrName, redraw) => `<div class="searchtop">${boxes(st, depName, arrName, redraw)}</div>`;
/** 처음 상태: 아무것도 고르지 않음 (예전엔 기차 서울→부산·항공 김포→제주가 미리 들어가 있었음) */
export const blank = () => ({ dep: null, arr: null, day: 0, editing: null, q: "", searched: false });

/** 편 목록: 지난 것은 흐리게, 다음 편으로 내려감 */
export function tripList(root, list, now, row) {
  const next = Math.max(0, list.findIndex((t) => t.depMin >= now));
  const left = list.filter((t) => t.depMin >= now).length;
  const html = `<p class="note">${list.length}편${now >= 0 ? ` · 남은 ${left}편` : ""}</p><div class="list">${list.map((t, i) => `<div class="row ${t.depMin < now ? "gone" : ""}" data-i="${i}">${row(t)}</div>`).join("")}</div>`;
  return { html, after: () => root.querySelector(`[data-i="${next}"]`)?.scrollIntoView({ block: "start" }) };
}
/** 지금 시각(분), 오늘이 아니면 -1 (지난 편 흐리게 하지 않음) */
export const nowMin = (st) => { if (st.day) return -1; const t = seoulNow(); return t.h * 60 + t.mi; };

// ── 기차 ──
const norm = (n) => { const t = n.replace(/\(.*?\)/g, "").replace(/\s+/g, ""); return t === "여수엑스포" || t === "여수EXPO" ? "여수EXPO" : t === "평택지제" ? "지제" : t; };
let trainsP = null, stationsP = null;
function trains() {
  if (!trainsP) trainsP = cachedText("data/files/train_timetable.tsv").then((t) => t.split("\n").map((l) => {
    const f = l.split("\t");
    if (f.length < 4 || !f[0].trim()) return null;
    const stops = f[3].split(";").map((s) => { const i = s.lastIndexOf("@"); const n = s.slice(0, i), m = parseInt(s.slice(i + 1), 10); return i > 0 && n.trim() && Number.isFinite(m) ? [n, m] : null; }).filter(Boolean);
    return stops.length < 2 ? null : { no: f[0].trim(), kind: f[1].trim(), remark: f[2].trim(), stops, keys: stops.map((s) => norm(s[0])) };
  }).filter(Boolean));
  return trainsP;
}
function trainStations() {
  if (!stationsP) stationsP = Promise.all([trains(), cachedText("data/files/train_stations.tsv")]).then(([tr, t]) => {
    const known = new Set(tr.flatMap((x) => x.keys)), seen = new Set();
    return t.split("\n").map((l) => l.split("\t")).filter((f) => f.length >= 3 && f[0].startsWith("NAT"))
      .map((f) => ({ id: f[0], name: f[1].trim(), region: fullSido(f[2]) })).filter((s) => known.has(norm(s.name)) && !seen.has(norm(s.name)) && seen.add(norm(s.name)));
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
/** from 에서 to 로 가는 그날 직통 열차 (출발 순) */
function directTrips(all, d, from, to) {
  const fk = norm(from), tk = norm(to), seen = new Set();
  return all.map((x) => {
    if (!runsOn(x.remark, d)) return null;
    const i = x.keys.indexOf(fk), j = x.keys.indexOf(tk);
    return i < 0 || j <= i ? null : { no: x.no, kind: x.kind || null, depMin: x.stops[i][1], arrMin: x.stops[j][1], fare: null };
  }).filter((x) => x && !seen.has(x.no + "|" + x.depMin) && seen.add(x.no + "|" + x.depMin)).sort((a, b) => a.depMin - b.depMin);
}
/** 환승역 후보 (앱 TrainTransfers.HUBS 와 같음): 노선이 갈라지는 큰 역 */
const HUBS = ["서울", "용산", "영등포", "수원", "평택", "천안아산", "오송", "조치원", "대전", "서대전", "김천구미", "동대구", "신경주",
  "울산", "부산", "밀양", "마산", "익산", "정읍", "광주송정", "목포", "순천", "여수EXPO", "행신", "청량리", "원주", "제천", "영주", "강릉"];
/** 갈아타는 데 필요한 최소 시간(분), 너무 오래 기다리는 환승은 뺌 */
const MIN_WAIT = 10, MAX_WAIT = 180;
/**
 * 1회 환승 경로: 환승역마다 (출발 → 환승역) 첫 열차에 가장 빨리 도착하는 (환승역 → 도착) 둘째 열차를 짝지음.
 * 같은 첫 열차는 하나만, 더 늦게 떠나면서 더 일찍 도착하는 다른 경로가 있으면 뺌. 출발 순, 많으면 하루에 고르게 20개
 */
export function trainTransfers(all, d, dep, arr) {
  const out = [];
  for (const hub of HUBS) {
    const hk = norm(hub);
    if (hk === norm(dep) || hk === norm(arr)) continue;
    const first = directTrips(all, d, dep, hub);
    if (!first.length) continue;
    const second = directTrips(all, d, hub, arr);
    if (!second.length) continue;
    for (const a of first) {
      let best = null;
      for (const b of second) {
        const w = b.depMin - a.arrMin;
        if (w >= MIN_WAIT && w <= MAX_WAIT && b.no !== a.no && (!best || b.arrMin < best.arrMin)) best = b;
      }
      if (best) out.push({ hub, wait: best.depMin - a.arrMin, legs: [a, best], depMin: a.depMin, arrMin: best.arrMin });
    }
  }
  const per = new Map();
  for (const t of out.sort((x, y) => x.arrMin - y.arrMin || y.depMin - x.depMin)) { const k = t.legs[0].no + "|" + t.legs[0].depMin; if (!per.has(k)) per.set(k, t); }
  const all2 = [...per.values()];
  const kept = all2.filter((t) => !all2.some((o) => o !== t && o.depMin >= t.depMin && o.arrMin <= t.arrMin && (o.depMin > t.depMin || o.arrMin < t.arrMin))).sort((a, b) => a.depMin - b.depMin);
  if (kept.length <= 20) return kept;
  const step = kept.length / 20;
  return Array.from({ length: 20 }, (_, i) => kept[Math.floor(i * step)]);
}

const train = blank();
export function trainScreen(root, chipsHtml) {
  const MAX = 30;
  let alive = true, trips = null, failed = false, stations = [], transfers = null, showTransfers = false;
  trainStations().then((s) => { stations = s; if (train.editing) draw(); }).catch(() => {});
  const search = () => { train.searched = true; train.editing = null; RecentRoutes.add("train", train.dep, train.arr); load(); };
  const load = async () => {
    trips = null; failed = false; transfers = null; showTransfers = false;
    draw();
    if (!train.dep || !train.arr) return;
    const t = dayOf(train), d = isoDay(t);
    let all = [];
    try { all = await trains(); trips = directTrips(all, d, train.dep, train.arr); } catch (e) { failed = true; trips = []; }
    // 직통이 없으면 환승 경로를 바로 찾아 줌 (앱과 같음)
    if (!failed && !trips.length) { transfers = trainTransfers(all, d, train.dep, train.arr); showTransfers = true; }
    draw();
    const st2 = await trainStations().catch(() => []);
    const ds = st2.find((s) => norm(s.name) === norm(train.dep)), as = st2.find((s) => norm(s.name) === norm(train.arr));
    if (ds && as && trips.length) {
      const info = await trainInfo(ds.id, as.id, ymd(t));
      if (info.size && alive) { trips = trips.map((x) => { const i = info.get(x.no.replace(/^0+/, "") || "0"); return i ? { ...x, kind: i[0] || x.kind, fare: i[1] ?? x.fare } : x; }); draw(); }
    }
  };
  const redraw = () => { if (train.searched && !train.editing) load(); else draw(); };
  const transferHtml = () => {
    if (!showTransfers) return trips.length ? `<button class="link pad" ${act(async () => { showTransfers = true; transfers = trainTransfers(await trains(), isoDay(dayOf(train)), train.dep, train.arr); draw(); })}>환승해서 가는 방법도 보기</button>` : "";
    if (!transfers) return note("환승 경로를 찾는 중...");
    if (!transfers.length) return note("환승 1회로 갈 수 있는 열차를 찾지 못했어요. 2회 이상 환승이 필요하거나 그날 운행이 없을 수 있어요.");
    const now = nowMin(train), leg = (x) => `${[x.kind, x.no].filter(Boolean).join(" ")} ${clock(x.depMin)}→${clock(x.arrMin)}`;
    return sectionHead("환승해서 가기 · 1회 환승") + `<div class="list">${transfers.map((x) => `<div class="row ${x.depMin < now ? "gone" : ""}"><span class="grow"><b class="trip">${clock(x.depMin)}  →  ${clock(x.arrMin)}</b>
      <small>${duration(x.arrMin - x.depMin)} · ${esc(x.hub)} 환승 (${x.wait}분 기다림)</small><small>${esc(leg(x.legs[0]))} · ${esc(leg(x.legs[1]))}</small></span>
      ${x.legs.every((l) => l.kind) ? `<span class="right"><b class="sm">${esc(x.legs.map((l) => l.kind).join(" → "))}</b></span>` : ""}</div>`).join("")}</div>`;
  };
  function draw() {
    if (!alive) return;
    let h = header("타볼까", "기차 시간표") + chipsHtml;
    if (train.editing) {
      const q = norm(train.q);
      const shown = stations.filter((s) => !q || norm(s.name).includes(q) || sidoMatch(s.region, q)).sort((a, b) => (norm(a.name).startsWith(q) ? 0 : 1) - (norm(b.name).startsWith(q) ? 0 : 1) || a.name.length - b.name.length).slice(0, 80);
      root.innerHTML = h + pickTop(train, train.dep, train.arr, redraw) + `<div class="searchbox"><input id="q" type="search" placeholder="${train.editing === "dep" ? "출발역" : "도착역"} 이름" value="${esc(train.q)}" autocomplete="off"></div>
        <div class="list">${shown.map((s) => `<button class="row" ${act(() => {
          train[train.editing] = s.name;
          // 출발을 고르고 도착이 비었으면 도착 고르기로 (조회는 버튼으로)
          train.editing = train.editing === "dep" && !train.arr ? "arr" : null; train.q = "";
          if (train.editing) draw(); else redraw();
        })}><span class="grow"><b>${esc(s.name)}</b><small>${esc(s.region)}</small></span></button>`).join("")}</div>`;
      const inp = root.querySelector("#q");
      inp.focus({ preventScroll: true });
      inp.oninput = () => { train.q = inp.value; const pos = inp.selectionStart; draw(); const i2 = root.querySelector("#q"); i2.focus(); i2.setSelectionRange(pos, pos); };
      return;
    }
    if (!train.searched) {
      root.innerHTML = h + searchForm(train, { key: "train", depName: train.dep, arrName: train.arr, label: (x) => x, max: MAX, redraw, search });
      bindDate(root, train, MAX, redraw);
      return;
    }
    h += resultTop(train, train.dep, train.arr, MAX, redraw);
    const done = (html) => { root.innerHTML = h + html; bindDate(root, train, MAX, redraw); };
    if (!trips) return done(note("시간표를 불러오는 중..."));
    if (failed) return done(note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."));
    if (!trips.length) return done(note(`${train.dep} → ${train.arr} 그날 직통 열차가 시간표에 없어요`) + transferHtml());
    const r = tripList(root, trips, nowMin(train), (x) => `<span class="grow"><b class="trip">${clock(x.depMin)}  →  ${clock(x.arrMin)}</b><small>${duration(x.arrMin - x.depMin)}</small></span>
      <span class="right"><b class="sm">${esc([x.kind, x.no].filter(Boolean).join(" "))}</b>${x.fare ? `<small>${won(x.fare)}</small>` : ""}</span>`);
    done(r.html + transferHtml());
    if (!showTransfers) r.after();
  }
  train.searched && train.dep && train.arr ? load() : draw();
  // 못 받은 채로 다른 앱에 갔다 돌아오거나 인터넷이 다시 켜지면 다시 받음 (onReturn)
  const offReturn = onReturn(() => { if (alive && failed) load(); });
  return () => { alive = false; offReturn(); };
}

// ── 고속·시외버스 ──
let termP = null;
function terminals() {
  if (!termP) termP = cachedText("data/files/coach_terminals.tsv").then((t) => {
    const seen = new Set();
    return t.split("\n").map((l) => l.split("\t")).filter((f) => f.length >= 3 && f[0].startsWith("NA")).map((f) => ({ id: f[0], name: f[1].trim(), region: fullSido(f[2]) })).filter((x) => !seen.has(x.id) && seen.add(x.id));
  });
  return termP;
}
const express = (t) => t.id.startsWith("NAEK");
async function suburbTerminals(q) {
  try { return xmlRows(await getText("w/dg/1613000/SuburbsBusInfo/GetSuberbsBusTrminlList", { terminalNm: q.trim() })).filter((r) => r.terminalid).map((r) => ({ id: r.terminalid, name: r.terminalnm || "", region: fullSido(r.cityname) })); }
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

const coach = blank();
export function coachScreen(root, chipsHtml) {
  const MAX = 30;
  let alive = true, trips = null, failed = false, list = [], extra = [], seq = 0;
  terminals().then((l) => { list = l; if (coach.editing) draw(); }).catch(() => {});
  const search = () => { coach.searched = true; coach.editing = null; RecentRoutes.add("coach", coach.dep, coach.arr); load(); };
  const load = async () => {
    trips = null; failed = false; draw();
    if (!coach.dep || !coach.arr) return;
    try { trips = await coachTrips(coach.dep, coach.arr, ymd(dayOf(coach))); } catch (e) { failed = true; trips = []; }
    draw();
  };
  const redraw = () => { if (coach.searched && !coach.editing) load(); else draw(); };
  function draw() {
    if (!alive) return;
    let h = header("타볼까", "고속·시외버스 시간표") + chipsHtml;
    if (coach.editing) {
      const q = coach.q.replace(/ /g, ""), other = coach.editing === "arr" ? coach.dep : coach.arr, seen = new Set();
      const shown = [...list, ...extra].filter((x) => !seen.has(x.id) && seen.add(x.id))
        .filter((x) => !q || x.name.replace(/ /g, "").includes(q) || sidoMatch(x.region, q)).filter((x) => !other || express(x) === express(other))
        .sort((a, b) => (a.name.startsWith(q) ? 0 : 1) - (b.name.startsWith(q) ? 0 : 1) || (express(a) ? 0 : 1) - (express(b) ? 0 : 1) || a.name.length - b.name.length).slice(0, 80);
      root.innerHTML = h + pickTop(coach, coach.dep?.name, coach.arr?.name, redraw) + `<div class="searchbox"><input id="q" type="search" placeholder="${coach.editing === "dep" ? "출발" : "도착"} 터미널 이름" value="${esc(coach.q)}" autocomplete="off"></div>
        <div class="list">${shown.map((x) => `<button class="row" ${act(() => {
          if (coach.editing === "dep") { coach.dep = x; if (coach.arr && express(coach.arr) !== express(x)) coach.arr = null; coach.editing = coach.arr ? null : "arr"; }
          else { coach.arr = x; coach.editing = null; }
          coach.q = ""; extra = []; coach.editing ? draw() : redraw();
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
    if (!coach.searched) {
      root.innerHTML = h + searchForm(coach, { key: "coach", depName: coach.dep?.name, arrName: coach.arr?.name, label: (x) => x.name, max: MAX, redraw, search });
      bindDate(root, coach, MAX, redraw);
      return;
    }
    h += resultTop(coach, coach.dep.name, coach.arr.name, MAX, redraw);
    const done = (html) => { root.innerHTML = h + html; bindDate(root, coach, MAX, redraw); };
    if (failed) return done(note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."));
    if (!trips) return done(note("시간표를 불러오는 중..."));
    if (!trips.length) return done(note(`${coach.dep.name} → ${coach.arr.name} 그날 버스가 없어요`));
    const r = tripList(root, trips, nowMin(coach), (x) => `<span class="grow"><b class="trip">${clock(x.depMin)}${x.arrMin != null ? "  →  " + clock(x.arrMin) : ""}</b>${x.arrMin != null ? `<small>${duration(x.arrMin - x.depMin)}</small>` : ""}</span>
      <span class="right"><b class="sm">${esc(x.grade)}</b>${x.fare ? `<small>${won(x.fare)}</small>` : ""}</span>`);
    done(r.html);
    r.after();
  }
  coach.searched && coach.dep && coach.arr ? load() : draw();
  // 못 받은 채로 다른 앱에 갔다 돌아오거나 인터넷이 다시 켜지면 다시 받음 (onReturn)
  const offReturn = onReturn(() => { if (alive && failed) load(); });
  return () => { alive = false; offReturn(); };
}
