// 버스 화면: 정류장 찾기, 정류장(실시간 도착), 노선(지도·버스 위치·정류장 목록), 지도 탭.
import { esc, busColor, naturalCompare, isoDay, meters, seoulNow, livePoll, onReturn, tryLater, subwayColor } from "./core.js";
import { act, header, note, sectionHead, bindInput, icon } from "./ui.js";
import { go, isWide } from "./nav.js";
import { MapView } from "./map.js";
import { Favorites, Recent } from "./store.js";
import * as B from "./busdata.js";
import { Alight } from "./alight.js";
import * as Edits from "./edits.js";
import { tidyRoad } from "./roadline.js";
import * as Ahead from "./busahead.js";
import { MAX_SIDE_M, twoWayMask, offsetRight, shiftRight } from "./sideline.js";
import { trafficLayer } from "./topis.js";
export { offsetRight };

// 버스 위치는 5초마다 (지도에서 최대한 실시간으로, 그 사이는 늦은 만큼 앞으로 움직여 그림)
const REFRESH_MS = 15000, BUS_POS_MS = 5000;
/** 못 받아서 다시 묻는 동안(livePoll) 받은 지 이만큼 안 된 자료는 그대로 보여 줌 */
const KEEP_LIVE_MS = 120000;
const fresh = (x) => x?.state === "ok" && Date.now() - x.at < KEEP_LIVE_MS;
/** 처음 열 때 지역 도착(전주시 등)을 이만큼 기다리고, 안 오면 TAGO 를 먼저 보여 줌 */
const QUICK_LOCAL_MS = 3000;
const stopParams = (s) => ({ lat: s.lat, lng: s.lng });
/** 화면 사이에 정류장 정보를 넘김 (주소에는 id·좌표만) */
const known = new Map();
export const remember = (s) => { known.set(s.id, s); return s; };

function stopLine(s, sub) {
  remember(s);
  return `<button class="row" ${act(() => go("stop", s.id, stopParams(s)))}>
    <span class="stopmark"></span>
    <span class="grow"><b>${esc(s.name)}</b><small>${esc(sub ?? (s.no ? "정류장 번호 " + s.no : ""))}</small></span>
  </button>`;
}

// ── 정류장 찾기 (대중교통 → 버스) ──
const search = { q: "", result: [] };
export function busSearch(root, chipsHtml) {
  root.innerHTML = header("타볼까", "버스 정류장 실시간 도착", { action: { label: "지도", run: () => go("map") } }) + chipsHtml +
    `<div class="searchbox"><input id="q" type="search" placeholder="정류장 이름" value="${esc(search.q)}" autocomplete="off"></div><div id="out"></div>`;
  const out = root.querySelector("#out");
  let seq = 0;
  const draw = async () => {
    const q = search.q.trim();
    if (!q) {
      const fav = Favorites.stops.items, rec = Recent.stops.items;
      out.innerHTML = note("정류장 이름을 입력해 주세요") +
        (fav.length ? sectionHead("즐겨찾기") + `<div class="list">${fav.map((s) => stopLine(s)).join("")}</div>` : "") +
        (rec.length ? sectionHead("최근 본 정류장", () => { Recent.stops.clear(); draw(); }) + `<div class="list">${rec.map((s) => stopLine(s)).join("")}</div>` : "");
      return;
    }
    const my = ++seq;
    out.innerHTML = note("찾는 중...");
    const r = await B.searchStops(q).catch(() => null);
    if (my !== seq) return;
    lastFailed = !r;
    if (!r) { out.innerHTML = note("정류장 목록을 받지 못했어요"); return; }
    if (!r.length) { out.innerHTML = note("찾는 정류장이 없어요"); return; }
    out.innerHTML = `<div class="list">${r.map((s) => stopLine(s, "")).join("")}</div>`;
    // 시군 이름과 방면(지나는 노선의 다음 정류장)은 나중에 채움
    const rows = out.querySelectorAll(".row small");
    const info = r.map(() => ({ c: null, t: null, n: null }));
    const fill = (i) => { if (rows[i] && my === seq) rows[i].textContent = [info[i].c, r[i].no ? "정류장 번호 " + r[i].no : null, info[i].n, info[i].t ? info[i].t + " 방면" : null].filter(Boolean).join(" · "); };
    r.forEach((s, i) => B.cityName(s.city).then((c) => { info[i].c = c; fill(i); }));
    // 서는 노선 번호 몇 개 (비슷한 이름이 많을 때 고르기 쉽게, busdata.routeNoLabels). 위 12곳
    B.routeNoLabels(r.slice(0, 12)).then((ls) => ls.forEach((l, i) => { if (l) { info[i].n = l; fill(i); } })).catch(() => {});
    r.slice(0, 15).forEach((s, i) => {
      if (!s.routes.length) return;
      B.towardOf(s, s.routes.slice(0, 4).map((id) => ({ id, city: s.city }))).then((t) => { info[i].t = t; fill(i); }).catch(() => {});
    });
  };
  let lastFailed = false;
  const inp = bindInput(root, "#q", (v) => { search.q = v; draw(); });
  if (!isWide()) inp?.focus({ preventScroll: true });
  draw();
  // 못 받은 채로 다른 앱에 갔다 돌아오거나 인터넷이 다시 켜지면 다시 받음 (onReturn)
  return onReturn(() => { if (lastFailed) draw(); });
}

// ── 정류장 화면 ──
/** 노선 요약과 실시간 도착을 합친 줄 (실시간 있는 노선 먼저, 없으면 번호순) */
function mergeRows(sums, arrivals, stopName, infos) {
  const byId = new Map();
  for (const a of arrivals) { const k = a.routeId || a.routeNo; if (!byId.has(k)) byId.set(k, []); byId.get(k).push(a); }
  const rows = sums.map((s) => ({ id: s.id, no: s.no, type: s.type, sum: s, arr: (byId.get(s.id) || arrivals.filter((a) => !a.routeId && a.routeNo === s.no)) }));
  const knownIds = new Set(sums.map((s) => s.id));
  for (const [k, l] of byId) if (!knownIds.has(k) && !rows.some((r) => r.arr.some((a) => a.routeId === k))) rows.push({ id: k, no: l[0].routeNo, type: l[0].routeType, sum: null, arr: l });
  rows.forEach((r) => { r.city = r.arr.find((a) => a.city)?.city || r.sum?.city || null; });
  // 노선 기점 정류장이면 서 있는 버스는 "출발 대기" (기점·종점은 자료 파일 노선 요약, 없으면 받아 둔 노선 정보)
  rows.forEach((r) => { const s = r.sum?.start ? r.sum : infos?.get(r.id); if (s) r.arr = B.markWaiting(stopName, s.start, s.end, r.arr); });
  rows.forEach((r) => r.arr.sort((a, b) => (a.arrSeconds ?? 1e9) - (b.arrSeconds ?? 1e9)));
  return rows.sort((x, y) => (x.arr.length ? 0 : 1) - (y.arr.length ? 0 : 1) || (x.arr[0]?.arrSeconds ?? 1e9) - (y.arr[0]?.arrSeconds ?? 1e9) || naturalCompare(x.no, y.no));
}

export function stopScreen(root, p) {
  let stop = known.get(p.id);
  let alive = true, live = null, sums = null, city = null, toward = null;
  // 노선 목록이 오면 바로 줄을 그리고, 실시간 도착은 오는 대로 채움 (노선 목록이 늦으면 도착이 온 뒤 3초까지 기다림)
  let waited = false, liveWait = null;
  const timers = [];
  const ensure = async () => {
    if (!stop && p.lat) {
      try { stop = await B.stopAt(p.id, +p.lat, +p.lng); }
      catch (e) { root.innerHTML = header("정류장", null, { back: true }) + note("정류장 정보를 받지 못했어요. " + tryLater()); return null; }
    }
    if (!stop) { root.innerHTML = header("정류장", null, { back: true }) + note("정류장을 찾지 못했어요"); return false; }
    remember(stop);
    Recent.addStop(stop);
    return true;
  };
  let rows = [];
  /** 한 줄 오른쪽 (남은 시간은 매초 줄어듦) */
  const rightOf = (r, elapsed, day) => {
    const f = r.arr.find((a) => !B.passed(a, elapsed));
    if (!f) {
      const s = r.sum && (r.sum.first || r.sum.last || r.sum.interval) ? r.sum : infos.get(r.id) || r.sum;
      // "도착 정보 없음" 대신 첫차·막차·배차로: 운행종료 + 첫차까지 남은 시간, 운행 중이면 다음 출발 어림 (앱과 같음)
      const sn = seoulNow();
      const nl = s && live && live.state === "ok" ? B.noLive(s.first, s.last, B.intervalOn(s, day), sn.h * 60 + sn.mi) : null;
      if (nl) return `<span class="muted">${esc(nl.main)}</span><small>${esc(nl.sub)}</small>`;
      const plan = B.planText(s, day);
      return `<span class="muted">${live && live.state === "ok" ? "도착 정보 없음" : ""}</span>${plan ? `<small>${esc(plan)}</small>` : ""}`;
    }
    const parts = B.arrivalParts(f, elapsed);
    const soon = (f.arrSeconds ?? 0) - elapsed <= 180;
    const next = r.arr.filter((a) => !B.passed(a, elapsed))[1];
    const last = lastSoon(r);
    return `<b class="eta ${soon ? "soon" : ""}">${esc(parts[0])}</b>${parts.length > 1 ? `<small>${esc(parts.slice(1).join(" · "))}</small>` : ""}` +
      (next ? `<small>다음 ${esc(B.arrivalParts(next, elapsed).join(" · "))}</small>` : "") + (last ? `<small class="eta soon">${esc(last)}</small>` : "");
  };
  const elapsedNow = () => (live ? Math.max(0, Math.floor((Date.now() - live.at) / 1000)) : 0);
  /** 기점 막차가 한 시간 안이면 "막차 22:30 · 40분 남음" (기점 출발 시각 기준) */
  const lastSoon = (r) => {
    const s = r.sum && r.sum.last ? r.sum : infos.get(r.id);
    if (!s || !s.last || !s.first || s.first === s.last) return null;
    const sn = seoulNow();
    let n = sn.h * 60 + sn.mi, l = B.toMinutes(s.last), f = B.toMinutes(s.first);
    if (l == null || f == null) return null;
    if (l < f) { l += 1440; if (n < f) n += 1440; }
    return l - n >= 0 && l - n <= 60 ? `막차 ${s.last} · ${l - n < 1 ? "곧" : l - n + "분 남음"}` : null;
  };
  /** 매초: 남은 시간 글자만 바꿈 (줄을 다시 만들면 누르는 중인 것이 사라짐) */
  const tickTimes = () => {
    if (!alive) return;
    const el = elapsedNow(), day = isoDay();
    rows.forEach((r, i) => {
      const sp = root.querySelector(`[data-r="${i}"]`);
      const html = rightOf(r, el, day);
      if (sp && sp.dataset.h !== html) { sp.innerHTML = html; sp.dataset.h = html; }
    });
  };
  // 노선 줄 아래 글: 기점 → 종점 (자료에 없으면 노선을 받아 채움, 경기는 "○○ 방면")
  const ends = new Map();
  const infos = new Map(), asked = new Set();
  const endsOf = (r) => {
    // 기점 → 종점이나 첫차·막차·배차가 없는 노선(자료 파일에 없는 노선)은 노선 정보를 한 번 받아 채움
    const hasPlan = r.sum && (r.sum.first || r.sum.last || r.sum.interval);
    const own = r.sum && [r.sum.start, r.sum.end].filter(Boolean).join(" → ");
    // 기점·종점이 없거나, 지금 오는 버스가 없어 첫차·막차를 보여 줄 줄만 (노선마다 한 번씩. 예전엔 한도를 아끼려고 한 화면에 15개까지만)
    if (r.id && (!own || (live && !hasPlan && !r.arr.length)) && !asked.has(r.id)) {
      asked.add(r.id);
      B.routeInfo(r.id, r.city || (/^\d+$/.test(stop.city) ? stop.city : null))
        .then((x) => {
          if (!x || !alive) return;
          infos.set(r.id, x);
          const t = [x.start, x.end].filter(Boolean).join(" → ");
          if (t) ends.set(r.id, t);
          draw();
        }).catch(() => {});
    }
    if (own) return own;
    if (ends.get(r.id)) return ends.get(r.id);
    const towards = r.arr.find((a) => a.towards)?.towards;
    return towards ? towards + " 방면" : "";
  };
  let refresh = null, downNote = null;
  const draw = () => {
    if (!alive || !stop) return;
    const elapsed = elapsedNow();
    const arr = (live?.list || []).filter((a) => !B.passed(a, elapsed));
    // 도착이 먼저 오고 노선 목록이 늦으면 3초 뒤엔 도착만으로라도 그림
    if (live && sums == null && !liveWait) liveWait = setTimeout(() => { waited = true; draw(); }, 3000);
    const both = waited || sums != null;
    // 저상버스만 보기: 차 종류를 주는 지역에서만 (아무 도착도 차 종류가 없으면 거르지 않음)
    const knowsLow = (live?.list || []).some((a) => B.isLowArrival(a) != null);
    const lowOnly = B.LowFloor.on() && knowsLow;
    const list0 = (live?.list || []).filter((a) => !lowOnly || B.isLowArrival(a));
    rows = both ? mergeRows(sums || [], list0, stop.name, infos) : [];
    let status = "";
    if (!both) status = "불러오는 중...";
    else if (!live) status = "실시간 도착을 받는 중...";
    else if (live.state === "unsupported") status = "이 정류장의 실시간 도착은 웹 버전에서 볼 수 없어요. 폰 앱에서 볼 수 있어요.";
    else if (live.state === "retry") status = "실시간 도착을 다시 받는 중...";
    else if (live.state === "failed") status = downNote ? downNote + ". 받는 대로 저절로 보여 드려요." : "실시간 도착을 받지 못했어요. " + tryLater();
    else if (live.partial && downNote) status = downNote + ". 일부 도착이 빠졌을 수 있어요.";
    else if (!rows.length) status = "이 정류장에 서는 버스 정보가 없어요";
    else if (!arr.length) status = "지금 도착 예정인 버스가 없어요";
    const sub = [city, stop.no ? "정류장 번호 " + stop.no : null, toward ? toward + " 방면" : null].filter(Boolean).join(" · ");
    root.innerHTML = header(stop.name, sub || null, {
      back: true,
      star: { on: Favorites.hasStop(stop), toggle: () => { Favorites.toggleStop(stop); draw(); } },
      action: isWide() ? null : { label: "위치", run: () => go("map", stop.id, { lat: stop.lat, lng: stop.lng }) },
    }) + (status ? note(status) : "") + `<button class="link pad" ${act(() => refresh && refresh())}>↻ 새로고침</button><button class="link pad" ${act(() => go("stoptable", stop.id, stopParams(stop)))}>🕘 정류장 시간표</button><button class="link pad lowbtn ${B.LowFloor.on() ? "on" : ""}" ${act(() => { B.LowFloor.set(!B.LowFloor.on()); draw(); })}>♿ 저상버스만${B.LowFloor.on() && live && !knowsLow ? " (이 지역은 차 종류를 몰라요)" : ""}</button>${Edits.editing() ? `<button class="link pad" ${act(() => go("fixstops", stop.id, { lat: stop.lat, lng: stop.lng }))}>📍 위치 고치기</button>` : ""}<div class="list">${rows.map((r, i) => {
      const ends = endsOf(r);
      return `<button class="row route" ${act(() => go("route", r.id, { from: stop.id, city: r.city || (/^\d+$/.test(stop.city) ? stop.city : null), no: r.no, type: r.type }))}>
        <span class="grow">${r.type ? `<span class="badge rtype" style="background:${busColor(r.type)}">${esc(String(r.type).replace(/버스$/, ""))}</span>` : ""}<span class="badge big" style="background:${busColor(r.type)}">${esc(r.no)}</span><small>${esc(ends)}</small></span>
        <span class="right" data-r="${i}"></span></button>`;
    }).join("")}</div>`;
    tickTimes();
  };
  let poll = null, offRetry = null;
  const start = async () => {
    root.innerHTML = header("정류장", null, { back: true }) + note("불러오는 중...");
    const ok = await ensure();
    if (!alive) return;
    // 연결 실패(null)면 다른 앱에서 돌아오거나 인터넷이 다시 켜질 때 처음부터 다시
    if (ok === null) { offRetry = onReturn(() => { offRetry(); offRetry = null; start(); }); return; }
    if (!ok) return;
    draw();
    timers.push(setTimeout(() => { waited = true; draw(); }, 6000));
    B.cityName(stop.city).then((c) => { city = c; draw(); });
    // 저장해 둔 노선 목록은 바로, 서버에서 달라진 목록이 오면 바꿔 그림
    B.stopRoutes(stop, { onNewer: (s) => { if (alive) { sums = s; draw(); } } }).then((s) => {
      sums = s; draw();
      // 어느 방면인지: 지나는 노선의 다음 정류장
      B.towardOf(stop, s).then((t) => { if (t && alive) { toward = t; draw(); } }).catch(() => {});
    }).catch(() => { sums = []; draw(); });
    // 못 받았을 때(폰에서 다른 앱에 갔다 돌아오면 통신이 깨어나기 전에 물어 실패하곤 했음): livePoll 이 2·4·6초 뒤 다시 묻고,
    // 그동안은 받은 지 2분이 안 된 도착을 그대로 보여 줌 (없으면 "다시 받는 중"). 세 번 더 실패해야 "받지 못했어요".
    // 다른 앱에서 돌아오면 15초를 기다리지 않고 바로 새로 받음
    let tickSeq = 0;
    // 못 받았을 때 원인: 이 정류장이 쓰는 자료 중 서버 확인(30분마다)에서 막혀 있는 것 ("TAGO 버스 도착 (전국) 자료가 14:05부터 잘 안 와요")
    const explain = (got) => {
      if (got.state !== "failed" && !got.partial) { if (downNote) { downNote = null; draw(); } return; }
      B.downFor(stop).then((d) => {
        if (!alive) return;
        downNote = d.length ? d.map((x) => `${x.label} 자료가 ${new Date(x.since).toTimeString().slice(0, 5)}부터 잘 안 와요`).join(" · ") : null;
        draw();
      }).catch(() => {});
    };
    const tick = async ({ lastTry = true } = {}) => {
      const my = ++tickSeq;
      // 처음 열 때만: 지역 도착(전주시 등)이 3초 안에 안 오면 TAGO 를 먼저 보여 주고, 지역 도착이 오면 바꿈
      let got = await B.arrivals(stop, { quickMs: live?.state === "ok" ? null : QUICK_LOCAL_MS });
      if (!alive) return;
      if (got.state !== "failed" || lastTry) explain(got);
      got.later?.then(async (g2) => {
        if (!alive || my !== tickSeq || g2.state !== "ok") return;
        live = g2; draw();
        const fixed = g2.list.length ? await B.refineArrivals(stop, g2.list) : g2.list;
        if (alive && live === g2 && fixed !== g2.list) { live = { ...g2, list: fixed }; draw(); }
      });
      if (got.state === "failed" && !lastTry) {
        if (!fresh(live)) { live = { state: "retry", list: [], at: Date.now() }; draw(); }
        return false;
      }
      // 직전엔 도착이 있었는데 갑자기 하나도 없으면(공공데이터가 잠깐 빈 응답을 줄 때) 3초 뒤 한 번 더 물음
      if (got.state === "ok" && !got.list.length && live?.list?.length) {
        await new Promise((r) => setTimeout(r, 3000));
        if (!alive) return;
        const again = await B.arrivals(stop);
        if (!alive) return;
        if (again.state === "ok" && again.list.length) got = again;
      }
      // 처음: 받은 도착 정보를 바로 보여 주고, 버스 위치로 고친 것이 오면 바꿈 (위치 확인에 1~3초 걸림)
      // 새로고침: 고친 것을 1.5초까지 기다렸다가 보여 줌 (지나간 버스가 잠깐 다시 보이는 깜빡임 없게)
      const refine = got.state === "ok" && got.list.length ? B.refineArrivals(stop, got.list) : null;
      if (refine && live) {
        const fixed = await Promise.race([refine, new Promise((r) => setTimeout(() => r(null), 1500))]);
        if (!alive) return;
        if (fixed) { live = { ...got, list: fixed }; draw(); return true; }
      }
      live = got; draw();
      if (refine) refine.then((fixed) => { if (alive && live === got && fixed !== got.list) { live = { ...got, list: fixed }; draw(); } });
      return got.state !== "failed";
    };
    poll = livePoll(tick, REFRESH_MS);
    // 새로고침 단추: 도착 정보만 다시 (3초 안에 여러 번 누르면 한 번만)
    let lastRefresh = 0;
    refresh = () => {
      if (Date.now() - lastRefresh < 3000) return;
      lastRefresh = Date.now();
      // 받지 못했어요가 떠 있으면 누르자마자 "다시 받는 중"으로 (그대로 남아 안 눌린 것 같았음)
      if (live?.state === "failed") { live = { state: "retry", list: [], at: Date.now() }; draw(); }
      poll.now();
    };
    timers.push(setInterval(tickTimes, 1000));
  };
  start();
  return () => { alive = false; timers.forEach(clearInterval); poll?.stop(); offRetry?.(); };
}

// ── 정류장 시간표: 지난 운행 기록(최근 6주)으로 요일별 "몇 시에 몇 번 버스가 오는지" ──
const DAY_NAMES = ["월", "화", "수", "목", "금", "토", "일"];
const hhmm = (m) => String(Math.floor(m / 60) % 24).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0");
export function stopTable(root, p) {
  let alive = true, day = isoDay(), log = null, sums = null, failed = false, stop = known.get(p.id);
  const plans = new Map();   // 노선 id → {sum, offset} (예상 시간표 재료)
  const draw = () => {
    if (!alive) return;
    const head = header(stop ? stop.name : "정류장", "정류장 시간표 · 지난 운행 기록 + 예상", { back: true }) +
      `<div class="seg">${DAY_NAMES.map((n, i) => `<button class="${day === i + 1 ? "on" : ""}" ${act(() => { day = i + 1; draw(); })}>${n}</button>`).join("")}</div>`;
    if (!log || !sums) { root.innerHTML = head + note("불러오는 중..."); return; }
    const noOf = new Map(sums.map((s) => [s.id, s]));
    // 노선마다 이 요일 시간표 → 여러 날(2일 넘게) 같은 무렵에 기록된 운행만
    const rows = [], recorded = new Set();
    let recDays = 0;
    for (const [rid, mins] of Object.entries(log)) {
      const s = noOf.get(rid);
      if (!s) continue;
      const t = B.timetable(mins, day);
      recDays = Math.max(recDays, t.days);
      for (const sl of t.slots) if (sl.days >= 2) { rows.push({ m: sl.expected, early: sl.earliest, late: sl.latest, days: sl.days, of: t.days, s }); recorded.add(rid); }
    }
    // 기록이 모자란 노선은 첫차·막차·배차 간격과 기점에서 걸리는 시간으로 계산한 예상 시각 (~ 표시)
    let estCount = 0, officialCount = 0;
    for (const s of sums) {
      if (recorded.has(s.id) || !plans.get(s.id)) continue;
      const { sum, offset, downOffset } = plans.get(s.id);
      const ts = B.plannedTimes(sum, offset, day, downOffset);
      if (ts.length) { if (sum.deps) officialCount++; else estCount++; }
      for (const m of ts) rows.push({ m, est: true, s, sum });
    }
    rows.sort((a, b) => a.m - b.m || naturalCompare(a.s.no, b.s.no));
    const today = day === isoDay(), sn = seoulNow(), nowMin = sn.h * 60 + sn.mi;
    let html = head + note(`기록: 최근 6주 동안 버스가 이 정류장을 지난 시각 중 여러 날 같은 무렵에 온 운행 (${DAY_NAMES[day - 1]}요일 기록 ${recDays}일).` +
      (officialCount ? ` ~ 표시 중 노선 ${officialCount}개는 지자체 공식 배차시간표(기점 출발 시각)에 이 정류장까지 걸리는 시간을 더한 시각이에요.` : "") +
      (estCount ? ` ~ 표시 중 노선 ${estCount}개는 첫차·막차·배차 간격으로 계산한 예상 시각이라 실제와 다를 수 있어요.` : ""));
    if (failed) html += note("지난 운행 기록을 받지 못해 예상 시각만 보여 줘요.");
    if (!rows.length) {
      html += note("아직 이 요일 기록이 모자라고, 계산할 첫차·막차 정보도 없어요.");
      html += sectionHead("노선별 첫차·막차·배차") + `<div class="list">${sums.map((s) => {
        const plan = B.planText(s, day);
        return `<div class="row"><span class="badge" style="background:${busColor(s.type)}">${esc(s.no)}</span><span class="grow"><small>${esc(plan || "정보 없음")}</small></span></div>`;
      }).join("")}</div>`;
      root.innerHTML = html;
      return;
    }
    // 시간대별로 묶어 보여 줌, 오늘이면 지금부터 오는 것을 먼저 표시
    const byHour = new Map();
    for (const r of rows) { const h = Math.floor(r.m / 60) % 24; if (!byHour.has(h)) byHour.set(h, []); byHour.get(h).push(r); }
    html += `<div class="list">${[...byHour.entries()].map(([h, list]) => `<div class="row col"><b>${String(h).padStart(2, "0")}시</b><span class="ttimes">${list.map((r) => {
      const tip = r.est && r.sum.deps ? `${r.sum.source || "공식 배차시간표"} 출발 시각 + 이 정류장까지 걸리는 시간 (예상)` : r.est ? `예상 · 기점 첫차 ${r.sum.first || "?"} 막차 ${r.sum.last || "?"}${B.intervalOn(r.sum, day) > 0 ? " 배차 " + B.intervalOn(r.sum, day) + "분" : ""}` : `${hhmm(r.early)}~${hhmm(r.late)} · ${r.days}일 기록`;
      return `<span class="tt ${today && r.m < nowMin ? "muted" : ""}" title="${esc(tip)}"><b>${r.est ? "~" : ""}${hhmm(r.m)}</b> <span class="badge" style="background:${busColor(r.s.type)}">${esc(r.s.no)}</span></span>`;
    }).join(" ")}</span></div>`).join("")}</div>`;
    root.innerHTML = html;
    if (today) root.querySelector(`.tt:not(.muted)`)?.scrollIntoView({ block: "center" });
  };
  (async () => {
    draw();
    if (!stop && p.lat) stop = await B.stopAt(p.id, +p.lat, +p.lng).catch(() => null);
    if (!stop) { if (alive) root.innerHTML = header("정류장 시간표", null, { back: true }) + note("정류장을 찾지 못했어요"); return; }
    draw();
    const [l, s] = await Promise.all([B.stopLog(stop).catch(() => null), B.stopRoutes(stop).catch(() => [])]);
    if (l == null) failed = true;
    log = l || {}; sums = s;
    draw();
    // 예상 시간표 재료: 노선 정류장 목록(기점에서 걸리는 시간)과 첫차·막차 (모든 노선, 받는 대로 다시 그림. 예전엔 15개까지만)
    await Promise.all(sums.map((rt) => B.plannedFor(stop, rt).then((x) => { if (x) plans.set(rt.id, x); }).catch(() => null)));
    draw();
  })();
  return () => { alive = false; };
}

// ── 노선 화면 ──
/** 점 p 를 선분 a-b 에 내린 자리와 거리(m). 좁은 범위라 평면으로 계산 */
function project(p, a, b) {
  const kx = 111320 * Math.cos((p[0] * Math.PI) / 180), ky = 110540;
  const ax = (a[1] - p[1]) * kx, ay = (a[0] - p[0]) * ky, bx = (b[1] - p[1]) * kx, by = (b[0] - p[0]) * ky;
  const dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
  const t = len ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len)) : 0;
  const x = ax + t * dx, y = ay + t * dy;
  return { d: Math.hypot(x, y), t, pt: [a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])] };
}
/** 도로 선의 처음부터 각 꼭짓점까지 거리(m) */
function roadCum(road) {
  const cum = [0];
  for (let i = 0; i < road.length - 1; i++) {
    const kx = 111320 * Math.cos((road[i][0] * Math.PI) / 180);
    cum.push(cum[i] + Math.hypot((road[i + 1][1] - road[i][1]) * kx, (road[i + 1][0] - road[i][0]) * 110540));
  }
  return cum;
}
/** 노선 선 굵기(px)와, 오른쪽 통행에 맞춰 진행 방향 오른쪽으로 비키는 거리(px: 굵기 절반 + 조금 → 오가는 두 줄이 겹치지 않음) */
const LINE_W = 7, LINE_SIDE_PX = 4.5;
/** 선 단순화 (tol 미터 안의 꼭짓점은 뺌, Douglas-Peucker). 축소했을 때 촘촘한 꼭짓점 때문에 비킨 선이 톱니처럼 되지 않게 */
export function simplifyLine(line, tol) {
  if (!line || line.length < 3 || !(tol > 0)) return line;
  const keep = new Uint8Array(line.length);
  keep[0] = keep[line.length - 1] = 1;
  const stack = [[0, line.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    let far = -1, fd = tol;
    for (let i = a + 1; i < b; i++) { const d = project(line[i], line[a], line[b]).d; if (d > fd) { fd = d; far = i; } }
    if (far >= 0) { keep[far] = 1; stack.push([a, far], [far, b]); }
  }
  return line.filter((_, i) => keep[i]);
}
// 정류장 점을 노선 선 위로 옮기는 최대 거리 (좌표가 골목 쪽으로 잘못 찍힌 정류장도 큰길 선 위로, 서버 shape.js OFF_COORD_M 과 같게)
const STOP_SNAP_M = 1000;
/** 노선 선 위 방향 화살표 자리 (화면 안, 약 [gapPx] px 마다, 최대 300개) → 지도 점 [{chevron}] */
function lineArrows(line, gapPx = 90) {
  const mpp = MapView.metersPerPx(), b = MapView.bounds();
  if (!line || line.length < 2 || !mpp || !b) return [];
  const step = gapPx * mpp, padLat = (b.n - b.s) * 0.1, padLng = (b.e - b.w) * 0.1;
  const inView = (p) => p[0] >= b.s - padLat && p[0] <= b.n + padLat && p[1] >= b.w - padLng && p[1] <= b.e + padLng;
  const out = [];
  let next = step / 2;
  let walked = 0;
  for (let i = 0; i < line.length - 1 && out.length < 300; i++) {
    const a = line[i], c = line[i + 1];
    const len = Math.hypot((c[0] - a[0]) * 111000, (c[1] - a[1]) * 111000 * Math.cos((a[0] * Math.PI) / 180));
    if (!len) continue;
    while (next <= walked + len && out.length < 300) {
      const t = (next - walked) / len, p = [a[0] + t * (c[0] - a[0]), a[1] + t * (c[1] - a[1])];
      if (inView(p)) out.push({ id: "a:" + out.length, lat: p[0], lng: p[1], chevron: bearingAt(line, i) });
      next += step;
    }
    walked += len;
  }
  return out;
}
/** 도로 선 seg 번째 조각의 진행 방향 (북쪽 0도, 시계 방향) */
function bearingAt(road, seg) {
  const a = road[Math.max(0, Math.min(seg, road.length - 2))], b = road[Math.max(1, Math.min(seg + 1, road.length - 1))];
  const y = (b[1] - a[1]) * Math.cos((a[0] * Math.PI) / 180), x = b[0] - a[0];
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}
/** 정류장을 노선 순서대로 도로 선 위로 (뒤로 돌아가지 않게, 가장 가까운 곳과 15m 안이면 먼저 나오는 쪽). 멀면 원래 자리 */
export function snapStops(stops, road) { return snapStopsFull(stops, road).map((x) => x && x.pt); }
/** [{pt, seg, t}] (선 위로 못 옮긴 정류장은 seg 없음) */
function snapStopsFull(stops, road) {
  if (!road || road.length < 2) return stops.map((s) => (s.lat == null ? null : { pt: [s.lat, s.lng] }));
  let cur = 0;
  return stops.map((s) => {
    if (s.lat == null) return null;
    const p = [s.lat, s.lng];
    const hits = [];
    for (let i = cur; i < road.length - 1; i++) hits.push([i, project(p, road[i], road[i + 1])]);
    if (!hits.length) return { pt: p };
    const best = Math.min(...hits.map(([, h]) => h.d));
    // 정류장 점은 항상 노선 선 위로 (1km 넘게 떨어진 것만 자료가 틀린 것으로 보고 원래 자리)
    if (best > STOP_SNAP_M) return { pt: p };
    const [i, h] = hits.find(([, h2]) => h2.d <= best + 15);
    cur = i;
    return { pt: h.pt, seg: i, t: h.t };
  });
}
/** 버스 이름표: 차량번호 전체 + 차 종류(아는 지역만: 저상·일반·굴절·2층) */
export const busLabel = (b) => [str0(b.vehicleNo), b.kind].filter(Boolean).join(" · ") || null;
/** 지도 입체 버스 아래 이름표: "저상 · 12가3456" (앱 busCaption 과 같게: 차 종류 먼저, 모르면 차량번호만) */
export const busCaption = (b) => [b.kind, str0(b.vehicleNo)].filter(Boolean).join(" · ") || null;
const str0 = (v) => (v == null ? "" : String(v).trim());

export function routeScreen(root, p, ctx) {
  let r = null, pos = null, road = null, snapped = null, alive = true, fitted = false, focusSeq = null, linesOf = null, refreshing = false;
  // 화면에 그린 노선 선 (도로 선을 진행 방향 오른쪽으로 비킨 것)과 그때의 m/px·비킨 거리(m)
  let drawn = null, drawnMpp = 0, sideM = 0, roadTwoWay = null, twoWayOf = null;
  const timers = [];
  // 지도 종류(일반·위성)·레이어 단추 (지도 탭과 같은 topis.js): 노선 화면에서도 소통·자전거·주차장·편의시설·주유소 등을 켬
  let traffic = null, layerCard = null, cardSeq = 0;
  // 노선 모양을 못 받았으면(서버가 아직 못 만듦) 정류장을 곧게 이어 그리고, 잠시 뒤 다시 받음
  let shapeMissing = false;
  // 노선을 보는 중에도 지도 탭처럼 다른 정류장(줌 15부터)·지하철역(줌 12부터)을 함께 (app.js nearbyMarks). 노선 정류장은 빼고 노선 점이 위에
  let near = { stops: [], stations: [] }, nearTimer = null;
  const loadNear = () => {
    clearTimeout(nearTimer);
    nearTimer = setTimeout(async () => { const got = await nearbyMarks().catch(() => null); if (got && alive) { near = got; drawMap(); } }, 300);
  };
  timers.push(() => clearTimeout(nearTimer));
  /** 다른 정류장·역 점을 누르면 지도 아래 카드 (지도 탭 카드와 같은 단추) */
  const nearCard = (d) => {
    if (!layerCard) return false;
    let h = "", onBtn = null;
    if (d.id.startsWith("os:")) {
      const st = near.stops.find((x) => "os:" + x.id === d.id);
      if (!st) return false;
      h = `<div><b>${esc(st.name)}</b><small>${st.no ? "정류장 번호 " + esc(st.no) : ""}</small></div><button class="btn" data-go="1">도착 정보</button>`;
      onBtn = () => go("stop", st.id, { lat: st.lat, lng: st.lng });
    } else if (d.id.startsWith("ost:")) {
      const g = near.stations[+d.id.slice(4)];
      if (!g) return false;
      h = `<div><b>${esc(g.name)}역</b><small>${esc(g.lines.join(" · "))}</small></div>` + g.lines.map((l, i) => `<button class="btn" data-line="${i}">${esc(l)}</button>`).join("");
      onBtn = (b) => openStation(g, g.lines[+b.dataset.line]);
    } else return false;
    layerCard.innerHTML = h + `<button class="link" data-x="1">닫기</button>`;
    layerCard.style.display = "flex";
    layerCard.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => { if (b.dataset.x) layerCard.style.display = "none"; else onBtn(b); }));
    return true;
  };
  /** 노선 정류장 좌표 (선 다듬기에서 실제로 들르는 곳을 남기려고) */
  const stopPts = () => (r ? r.stops.filter((s) => s.lat != null).map((s) => [s.lat, s.lng, s.name, s.id]) : []);
  // 버스는 받은 실제 GPS 좌표에서 그림 (노선 선 위로 옮기거나 노선을 따라 움직이지 않음:
  // 노선 모양이 실제와 다른 곳에서 버스가 엉뚱한 길로 가 보였음). 대신 API 가 늦은 만큼(15~30초) 마지막 진행 방향으로 곧장
  // 앞으로 당겨 그림 (busahead.js: 앞 정류장은 넘지 않고, 멈춘 버스는 그 자리). 새 좌표가 오면 보이던 자리에서 짧게 미끄러져 옮김
  const track = new Map(); // 차량 번호 → {a: busahead 기록, from, at, idx, back}
  const heading = B.headingTracker();   // 지도 버스 진행 방향 화살표
  const JUMP_M = 1500, MOVE_M = 8, BACK_CONFIRM = 3, GLIDE_MS = 2000;
  const meters = Ahead.meters;
  const stopIdx = (b) => { let i = b.nodeId ? r.stops.findIndex((s) => s.id === b.nodeId) : -1; if (i < 0 && b.ord != null) i = r.stops.findIndex((s) => s.seq === b.ord); return i; };
  let aheadStopsOf = null, aheadStops = [];
  const stopLL = () => { if (aheadStopsOf !== r.stops) { aheadStopsOf = r.stops; aheadStops = r.stops.filter((s) => s.lat != null).map((s) => [s.lat, s.lng]); } return aheadStops; };
  /** 지금 보일 자리: 앞으로 당긴 자리, 새 좌표를 받은 직후엔 보이던 자리에서 그쪽으로 미끄러짐 */
  const glide = (t, now = Date.now()) => {
    const q = Ahead.predict(t.a, stopLL(), now);
    const k = t.from ? Math.max(0, Math.min(1, (now - t.at) / GLIDE_MS)) : 1;
    return k >= 1 ? q : [t.from[0] + (q[0] - t.from[0]) * k, t.from[1] + (q[1] - t.from[1]) * k];
  };
  /** 새 버스 위치를 받으면 */
  const learn = () => {
    if (!r || !pos) return;
    const now = Date.now(), seen = new Set();
    for (const b of pos.buses || []) {
      if (b.lat == null || !b.vehicleNo) continue;
      seen.add(b.vehicleNo);
      const p = [b.lat, b.lng], idx = stopIdx(b), prev = track.get(b.vehicleNo);
      if (!prev || meters(prev.a.raw, p) > JUMP_M) { track.set(b.vehicleNo, { a: Ahead.update(null, p, now, b.dataAt), from: null, at: now, idx, back: 0 }); continue; }
      const moved = meters(prev.a.raw, p) >= MOVE_M;
      // 늦게 온 옛 좌표(정류장 순번이 줄어듦)는 무시. 세 번 연속이거나 노선 절반 넘게 줄면(새 운행) 받아들임
      if (moved && idx >= 0 && prev.idx >= 0 && idx < prev.idx && prev.idx - idx < r.stops.length / 2 && prev.back + 1 < BACK_CONFIRM) { prev.back++; continue; }
      const a = Ahead.update(prev.a, p, now, b.dataAt, MOVE_M);
      if (a === prev.a) continue; // 자료가 아직 안 바뀜
      track.set(b.vehicleNo, { a, from: glide(prev, now), at: now, idx: moved ? idx : prev.idx, back: 0 });
    }
    for (const k of [...track.keys()]) if (!seen.has(k)) track.delete(k);
  };
  /** 지금 보일 자리 */
  const busXY = (b) => { const t = track.get(b.vehicleNo); return t ? glide(t) : [b.lat, b.lng]; };
  /** 그리는 자리가 움직이는 중인지 (미끄러지는 중이거나 앞으로 당기는 중) */
  const moving = (x, now) => (x.from && now - x.at < GLIDE_MS + 1000) || (!x.a.still && x.a.v > 0 && x.a.deg != null && now - x.a.at < Ahead.MAX_LEAD_MS);
  /** 도로 선 위 점을 그 자리 진행 방향 오른쪽으로 (그린 선과 같은 거리) */
  const toRight = (pt, seg) => (road && sideM && seg != null && roadTwoWay?.[seg] ? shiftRight(pt, bearingAt(road, seg), sideM) : pt);
  root.innerHTML = header("노선", null, { back: true }) + note("불러오는 중...");
  const drawMap = () => {
    if (!r) return;
    const color = busColor(r.type);
    const pts = r.stops.filter((s) => s.lat != null).map((s) => [s.lat, s.lng]);
    // 도로 선은 오른쪽 통행에 맞춰 진행 방향 오른쪽으로 선 굵기 절반쯤 비켜 그림 → 같은 길을 오가는 노선은 두 줄로 나란히 (폰 앱과 같음)
    const mpp = MapView.metersPerPx() || 0;
    if (linesOf !== (road || pts) || (road && Math.abs(mpp - drawnMpp) > drawnMpp * 0.05)) {
      linesOf = road || pts; drawnMpp = mpp;
      sideM = road && mpp ? Math.min(mpp * LINE_SIDE_PX, MAX_SIDE_M) : 0;
      // 같은 길을 오가는 구간만 오른쪽으로 (한 바퀴 도는 골목 구간은 도로 한가운데)
      if (road && twoWayOf !== road) { twoWayOf = road; roadTwoWay = twoWayMask(road); }
      const simp = road ? simplifyLine(road, mpp * 0.7) : null;
      drawn = sideM && simp ? offsetRight(simp, twoWayMask(simp).map((t) => (t ? sideM : 0))) : linesOf;
    }
    // 레이어(소통 선)는 노선 선 아래에. 같은 선은 지도가 그대로 둠 (map.js)
    MapView.lines([...(traffic ? traffic.lines() : []), { pts: drawn, color, width: LINE_W }]);
    const z = MapView.zoom(), z0 = z;
    // 다른 정류장·지하철역 (맨 아래), 레이어 점, 노선 선 위 진행 방향 화살표 (정류장 점이 아니라 선에, 화면에서 약 90px 마다)
    const mine = new Set(r.stops.map((s) => s.id && B.legacyId(s.id)).filter(Boolean));
    const others = thinDots([
      ...near.stations.map((g, i) => { const at = stationAt(g); return { id: "ost:" + i, lat: at[0], lng: at[1], color: subwayColor(g.lines[0]), ring: "#fff", r: 7, label: z0 >= 14 ? g.name + "역" : null }; }),
      ...near.stops.filter((s) => !mine.has(B.legacyId(s.id))).map((s) => ({ id: "os:" + s.id, lat: s.lat, lng: s.lng, color: BUS_STOP_COLOR, ring: "#fff", r: 7, bus: true })),
    ], MapView.metersPerPx());
    const dots = [...others, ...(traffic ? traffic.dots() : []), ...lineArrows(drawn)];
    // 정류장·버스 점은 도로 선 위에 (선을 받은 뒤)
    if (road && snapped?.road !== road) { snapped = { road, full: snapStopsFull(r.stops, road), cum: roadCum(road) }; }
    // 지도 고치기로 손으로 옮긴 정류장은 고친 자리 그대로 (앱 RoutePin.fixed; 선 위로 붙이면 고친 것이 안 보였음)
    const at = (i) => (r.stops[i].id && (Edits.stopFix(r.stops[i].id) || Edits.stopFix(B.legacyId(r.stops[i].id))) && r.stops[i].lat != null ? [r.stops[i].lat, r.stops[i].lng] : road && snapped ? (snapped.full[i] ? toRight(snapped.full[i].pt, snapped.full[i].seg) : null) : r.stops[i].lat == null ? null : [r.stops[i].lat, r.stops[i].lng]);
    r.stops.forEach((s, i) => {
      const xy = at(i);
      if (!xy) return;
      const here = p.from && s.id && B.legacyId(s.id) === B.legacyId(p.from), focused = focusSeq === s.seq;
      if (z >= 14 || here || focused) dots.push({ id: "s:" + s.seq, lat: xy[0], lng: xy[1], color: here || focused ? "#D7262E" : color, ring: "#fff", r: here || focused ? 8 : 6, label: focused ? s.name : null });
    });
    for (const b of pos?.buses || []) {
      if (b.lat == null) continue;
      const xy = busXY(b);
      if (B.LowFloor.on() && b.kind && b.kind !== "저상") continue;
      dots.push({ id: "b:" + b.vehicleNo, lat: xy[0], lng: xy[1], color, ring: "#fff", r: 8, bus3d: { no: r.no !== "노선" ? r.no : p.no || "", caption: busCaption(b), dir: heading(b.vehicleNo, b.lat, b.lng) } });
    }
    // 좌표가 없는 지역(경기 등): 버스가 있는 정류장 자리에 (폰 앱과 같음)
    for (const [i, list] of B.busAtStops(r.stops, (pos?.buses || []).filter((b) => b.lat == null))) {
      const xy = at(i);
      if (!xy) continue;
      list.forEach((b, k) => dots.push({ id: "b:" + b.vehicleNo, lat: xy[0] + k * 0.00008, lng: xy[1], color, ring: "#fff", r: 8, bus3d: { no: r.no !== "노선" ? r.no : p.no || "", caption: busCaption(b) } }));
    }
    MapView.dots(B.declutterCaptions(dots, MapView.zoom(), MapView.metersPerPx()), (d) => {
      // 레이어 점(돌발·자전거·주차장·편의시설·주유소·휴게소·CCTV): 지도 아래 카드 (topis.js card)
      if (/^(acc|bike|park|pl|cctv|rest|gas):/.test(d.id) && traffic && layerCard) {
        const asked = ++cardSeq;
        const show = (h) => { if (!h || asked !== cardSeq || !layerCard) return; layerCard.innerHTML = h + `<button class="link">닫기</button>`; layerCard.style.display = "flex"; layerCard.querySelector("button.link:last-child").onclick = () => { layerCard.style.display = "none"; }; };
        show(traffic.card(d.id, show));
        return;
      }
      if (nearCard(d)) return;
      if (layerCard) layerCard.style.display = "none";
      const seq = d.id.startsWith("s:") ? +d.id.slice(2) : null;
      const idx = seq != null ? r.stops.findIndex((s) => s.seq === seq) : [...B.busAtStops(r.stops, pos?.buses || []).entries()].find(([, l]) => l.some((b) => "b:" + b.vehicleNo === d.id))?.[0];
      root.querySelector(`[data-i="${idx}"]`)?.scrollIntoView({ behavior: "smooth", block: "center" });
    });
    if (!fitted && pts.length) { MapView.fit(pts); fitted = true; }
  };
  const drawList = () => {
    if (!r || !alive) return;
    const color = busColor(r.type);
    const at = B.busAtStops(r.stops, pos?.buses || []);
    const status = !pos ? "버스 위치를 받는 중..." : refreshing && pos.state !== "ok" ? "버스 위치를 다시 받는 중..." : pos.state === "unsupported" ? "이 노선의 실시간 버스 위치는 웹 버전에서 볼 수 없어요" :
      pos.state === "failed" ? "버스 위치를 받지 못했어요" : pos.buses.length ? `운행 중 ${pos.buses.length}대 · 5초마다 새로` :
      // 운행 시간 안인데 버스가 없음: 위치를 보내지 않는 노선일 수 있음 (여수 365-5 초도처럼 버스가 다녀도 위치 자료가 아예 안 옴)
      r.sum && B.inService(r.sum.first, r.sum.last, B.kstMinute()) ? `실시간 위치가 들어오지 않아요 · 시간표상 운행 시간이에요 (${B.planText({ first: r.sum.first, last: r.sum.last }, 0).startsWith("하루") ? B.planText({ first: r.sum.first, last: r.sum.last }, 0) : r.sum.first + "~" + r.sum.last})` :
      "지금 운행 중인 버스가 없어요";
    const s = r.sum;
    const plan = B.planText(s, isoDay());
    const list = root.querySelector("#stops");
    // 들어온 정류장까지 오는 버스: "3정류장 전 · 5분 후" (앞 두 대, 걸리는 시간은 정류장 시간표와 같은 거리 어림)
    let come = "";
    const fi = p.from ? r.stops.findIndex((st) => st.id && B.legacyId(st.id) === B.legacyId(p.from)) : -1;
    if (fi >= 0 && pos && pos.buses) {
      const ids = new Set([r.stops[fi].id]);
      const near = pos.buses.map((b) => stopIdx(b)).filter((i) => i >= 0 && i <= fi)
        .map((i) => ({ n: fi - i, m: i === fi ? 0 : B.offsetMinutes(r.stops, ids, i) ?? 0 }))
        .sort((x, y) => x.m - y.m).slice(0, 2);
      // 기점(첫 정류장)에 서 있는 버스는 출발을 기다리는 중: "도착"이 아니라 "출발 대기"
      const txt = (a) => (a.n === 0 ? (fi === 0 ? "출발 대기" : "도착") : `${a.n}정류장 전 · ${a.m < 1 ? "곧 도착" : Math.ceil(a.m) + "분 후"}`);
      come = `${r.stops[fi].name}까지 · ` + (near.length ? near.map((a, k) => (k ? "다음 " : "") + txt(a)).join(" / ") : "오는 버스 없음");
    }
    root.querySelector("#status").innerHTML = `<b>${esc(status)}</b>${come ? `<small><b>${esc(come)}</b></small>` : ""}${plan ? `<small>${esc(plan)}</small>` : ""}` +
      (shapeMissing ? `<small>노선 길 모양을 아직 받지 못해 지도에는 정류장 사이를 곧게 이었어요. 잠시 뒤 다시 받아요</small>` : "");
    list.innerHTML = r.stops.map((st, i) => {
      const turn = i > 0 && st.upDown && r.stops[i - 1].upDown && st.upDown !== r.stops[i - 1].upDown ? `<div class="turn">회차</div>` : "";
      const here = p.from && st.id && B.legacyId(st.id) === B.legacyId(p.from);
      const buses = at.get(i) || [];
      const alightOn = st.lat != null && Alight.isTarget(st.name, st.lat, st.lng);
      return turn + `<div class="rstop" data-i="${i}">
        <button class="dotbtn" ${act(() => { if (st.lat != null) { const xy = snapped?.full[i]?.pt || [st.lat, st.lng]; focusSeq = st.seq; MapView.view(xy[0], xy[1], 17); drawMap(); } })}>
          <span class="rdot ${buses.length ? "bus" : ""}" style="--c:${color}"></span></button>
        <button class="grow rname ${here ? "here" : ""}" ${act(async () => {
          if (!st.id || st.lat == null) return;
          // 자료 조각에서 찾고, 없으면 노선 자료로 정류장을 만들어 엶 (전주 등)
          const s2 = (await B.stopAt(st.id, st.lat, st.lng).catch(() => null)) || { id: st.id, name: st.name, lat: st.lat, lng: st.lng, no: st.no || null, city: r.city, routes: [] };
          remember(s2); go("stop", s2.id, stopParams(s2));
        })}>${esc(st.name)}</button>
        ${buses.length ? `<span class="plate" style="background:${color}">${esc(buses.map((b) => (busLabel(b) || "버스") + (b.crowd ? " · " + b.crowd : "")).join(", "))}</span>` : ""}
        ${Alight.supported && st.lat != null ? `<button class="mini ${alightOn ? "on" : ""}" ${act(() => { alightOn ? Alight.stop() : Alight.start(st.name, st.lat, st.lng); drawList(); })}>${alightOn ? "알림 끄기" : "내릴 곳"}</button>` : ""}
      </div>`;
    }).join("");
  };
  let offRetry = null;
  const start = async () => {
    try { r = await B.route(p.id, p.city); } catch (e) { r = null; }
    // 자료 조각에 없는 노선은 번호·종류를 정류장 화면에서 넘겨받음
    if (r && r.no === "노선" && p.no) { r.no = p.no; if (!r.type && p.type) r.type = p.type; }
    if (!alive) return;
    if (!r) {
      root.innerHTML = header("노선", null, { back: true }) + note("노선 정보를 받지 못했어요. " + tryLater());
      // 다른 앱에서 돌아오거나 인터넷이 다시 켜지면 다시
      offRetry = onReturn(() => { offRetry(); offRetry = null; start(); });
      return;
    }
    root.innerHTML = header(r.no, [[r.start, r.end].filter(Boolean).join(" → "), r.region].filter(Boolean).join(" · "), { back: true }) +
      `<div class="map-slot"></div><div class="under-map"><div id="status" class="status"></div><button class="link pad" id="rrefresh">↻ 새로고침</button>${Edits.editing() ? `<button class="link pad" ${act(() => go("fix", r.id, { kind: "bus", city: p.city || r.city, no: r.no !== "노선" ? r.no : p.no, name: r.no }))}>✏️ 노선 모양 고치기</button>` : ""}<div id="rtimes"></div><div id="stops" class="list"></div></div>`;
    // 공식 배차시간표가 있는 노선(전주·울산·천안·창원·대구·제주·진주·구미·안동·거제): 오늘 기점 출발 시각 전체
    B.routeDeps(r.id, r.no !== "노선" ? r.no : p.no).then((deps) => {
      const el = alive && root.querySelector("#rtimes");
      const list = B.todayDeps(deps);
      if (!el || !list.length) return;
      const sn = seoulNow(), now = sn.h * 60 + sn.mi;
      const hm = (m) => String(Math.floor(m / 60) % 24).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0");
      el.innerHTML = `<details class="rtimes"><summary>오늘 기점 출발 시각 ${list.length}회 · ${esc(deps.source || "공식 배차시간표")}</summary><div class="tt">${list.map((m) => `<span class="${m < now ? "past" : ""}">${hm(m)}</span>`).join("")}</div></details>`;
    }).catch(() => {});
    let tickNow = () => {};
    // 새로고침: 누르자마자 "다시 받는 중"으로 바꾸고(받지 못했어요가 그대로 남아 안 눌린 것 같았음) 받는 대로 그림
    root.querySelector("#rrefresh")?.addEventListener("click", () => {
      refreshing = true; drawList();
      Promise.resolve(tickNow()).finally(() => { refreshing = false; if (alive) drawList(); });
    });
    ctx.useMap(root.querySelector(".map-slot"));
    layerCard = document.createElement("div");
    layerCard.className = "side-card";
    root.querySelector(".map-slot")?.appendChild(layerCard);
    traffic = trafficLayer(() => { if (alive) drawMap(); });
    timers.push(() => { traffic?.destroy(); traffic = null; layerCard?.remove(); layerCard = null; });
    drawList(); drawMap();
    // 저장해 둔 모양이 있으면 바로 그리고, 서버에서 다른 모양이 오면 바꿔 그림.
    // 못 받으면(서버가 카카오 길찾기로 아직 못 만듦 → 정류장을 곧게 이음) 30초·1분 30초·3분 뒤 다시
    const gotShape = (s) => {
      if (!alive) return;
      shapeMissing = !(s && s.length >= 2);
      road = shapeMissing ? null : tidyRoad(s, stopPts()); snapped = null; linesOf = null; drawList(); drawMap();
    };
    const loadShape = (tries) => B.shape(r.id, r.city, r.stops, r.no !== "노선" ? r.no : p.no, tries ? {} : { onNewer: gotShape })
      .catch(() => null)
      .then((s) => {
        if (!alive) return;
        if (s && s.length >= 2) return gotShape(s);
        if (!road) gotShape(null);
        if (tries < 3) { const t = setTimeout(() => loadShape(tries + 1), [30000, 90000, 180000][tries]); timers.push(() => clearTimeout(t)); }
      });
    loadShape(0);
    const offIdle = MapView.onIdle(() => { drawMap(); loadNear(); });
    timers.push(() => offIdle());
    MapView.ready.then(loadNear);
    // 지도 빈 곳을 누르면 카드 닫기
    MapView.onTap(() => { if (layerCard) layerCard.style.display = "none"; });
    timers.push(() => MapView.onTap(null));
    // 누가 노선 모양·정류장 위치를 고치면 바로 다시 그림
    const offEdit = Edits.onChange(async () => {
      if (!alive) return;
      const r2 = await B.route(p.id, p.city).catch(() => null);
      if (r2 && alive) r.stops = r2.stops;
      const s2 = await B.shape(r.id, r.city, r.stops, r.no !== "노선" ? r.no : p.no).catch(() => null);
      if (!alive) return;
      road = tidyRoad(s2, stopPts()); snapped = null; linesOf = null; drawList(); drawMap();
    });
    timers.push(() => offEdit());
    // 버스 위치는 10초마다 받고(livePoll: 다른 앱에서 돌아오면 바로, 못 받으면 2·4·6초 뒤 다시), 새 좌표로 옮기는 동안 1초마다 다시 그림.
    // 다시 묻는 동안은 받은 지 2분이 안 된 위치를 그대로 둠 (예전엔 한 번 실패하면 버스가 다 사라지고 "받지 못했어요")
    async function tick({ lastTry = true } = {}) {
      const got = await B.positions(r);
      if (!alive) return;
      if (got.state === "failed" && !lastTry && fresh(pos)) return false;
      pos = got.state === "ok" ? { ...got, at: Date.now() } : got;
      learn(); drawList(); drawMap();
      return got.state !== "failed";
    }
    const poll = livePoll(tick, BUS_POS_MS);
    tickNow = () => poll.now();
    const anim = setInterval(() => { const now = Date.now(); if (alive && [...track.values()].some((x) => moving(x, now))) drawMap(); }, 1000);
    timers.push(() => poll.stop(), () => clearInterval(anim));
  };
  start();
  return () => { alive = false; timers.forEach((f) => f()); offRetry?.(); };
}

// ── 지도 탭 (좁은 화면: 지도 + 아래 카드 / 넓은 화면: 오른쪽 지도) ──
import { stopsMap, nearbyMarks, openStation, stationAt, BUS_STOP_COLOR } from "./app.js";
import { thinDots } from "./mapdiff.js";
export function mapTab(root, p, ctx) {
  const focus = p.lat ? { id: p.id, lat: +p.lat, lng: +p.lng } : null;
  if (ctx.wide) {
    root.innerHTML = header("지도", "오른쪽 지도에서 정류장을 누르세요", { back: !!focus }) + note("지도를 확대하면 정류장이 보여요. 정류장을 누르면 아래에 카드가 나오고, 카드의 \"도착 정보\"를 누르면 실시간 도착이 보여요.");
  } else {
    root.innerHTML = header("지도", "확대하면 정류장이 보여요", { back: !!focus }) + `<div class="map-slot full"></div>`;
  }
  const slot = root.querySelector(".map-slot");
  ctx.useMap(slot);
  if (focus) MapView.view(focus.lat, focus.lng, 17);
  return stopsMap(ctx.wide ? document.getElementById("side") : slot, focus);
}
