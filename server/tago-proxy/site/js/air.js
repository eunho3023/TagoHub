// 항공: 국내선 시간표(서버에 올린 운항 파일) + 한국공항공사 실시간 운항, 공항 화면(실시간 혼잡도·출발·도착·기본 정보), 인천공항 운항.
import { getText, cached, esc, seoulNow, addDays, ymd, isoDay, pad2 } from "./core.js";
import { act, header, note, sectionHead, chips } from "./ui.js";
import { go } from "./nav.js";
import { clock } from "./subway.js";

const duration = (min) => (min >= 60 ? `${Math.floor(min / 60)}시간${min % 60 ? " " + (min % 60) + "분" : ""}` : min + "분");
const WEEK = "월화수목금토일";
const PREFERRED = ["GMP", "CJU", "PUS", "TAE", "CJJ", "KWJ", "MWX", "YNY", "USN", "RSU", "HIN", "KPO", "KUV", "WJU", "ICN"];
export const ICN = "ICN";
const LOT_AIRPORT = { GMP: "김포", PUS: "김해", CJU: "제주", TAE: "대구", KWJ: "광주", RSU: "여수", USN: "울산", KUV: "군산", WJU: "원주", CJJ: "청주", MWX: "무안", HIN: "사천", YNY: "양양" };

// ── 시간표 파일 ──
let dataP = null;
function load() {
  if (!dataP) dataP = Promise.all(["air_schedule.tsv", "air_airports.tsv", "air_airlines.tsv"].map((f) => getText("data/files/" + f))).then(([s, a, l]) => {
    const names = (t) => Object.fromEntries(t.split("\n").map((x) => x.split("\t")).filter((f) => f.length >= 2 && f[0].trim()).map((f) => [f[0].trim(), f[1].trim()]));
    const min = (t) => { const [h, m] = t.split(":").map((x) => parseInt(x, 10)); return Number.isFinite(h) && Number.isFinite(m) ? h * 60 + m : null; };
    const rows = s.split("\n").map((l) => l.split("\t")).filter((f) => f.length >= 10).map((f) => ({
      intl: f[0] === "I", airline: f[1], flight: f[2], dep: f[3], arr: f[4], depMin: min(f[5]), arrMin: min(f[6]),
      days: new Set([...f[7]].map((c) => WEEK.indexOf(c) + 1).filter((x) => x > 0)), start: f[8].trim(), end: f[9].trim(),
    })).filter((r) => r.depMin != null && r.arrMin != null && /^\d{4}-\d\d-\d\d$/.test(r.start));
    return { rows, airports: names(a), airlines: names(l) };
  }).catch((e) => { dataP = null; throw e; });
  return dataP;
}
function flights(rows, dep, arr, t) {
  const pair = rows.filter((r) => r.dep === dep && r.arr === arr);
  if (!pair.length) return { list: [], projected: false };
  const date = `${t.y}-${pad2(t.m)}-${pad2(t.d)}`, d = isoDay(t);
  const exact = pair.filter((r) => date >= r.start && date <= r.end && r.days.has(d));
  const lastEnd = rows.reduce((m, r) => (r.end > m ? r.end : m), "");
  let picked = exact, projected = false;
  if (!exact.length && date > lastEnd) {
    const e = new Date(lastEnd + "T00:00:00Z"); e.setUTCDate(e.getUTCDate() - 7);
    const tail = e.toISOString().slice(0, 10);
    picked = pair.filter((r) => r.end >= tail && r.days.has(d)); projected = true;
  }
  const seen = new Set();
  const list = picked.filter((r) => !seen.has(r.flight + "|" + r.depMin) && seen.add(r.flight + "|" + r.depMin)).sort((a, b) => a.depMin - b.depMin)
    .map((r) => ({ airline: r.airline, flight: r.flight, depMin: r.depMin, arrMin: r.arrMin < r.depMin ? r.arrMin + 1440 : r.arrMin }));
  return { list, projected: projected && list.length > 0 };
}

// ── 실시간 (서버 /w/kac/, /w/icn/) ──
function rows(body) {
  const b = body?.response?.body;
  const items = b?.items;
  const raw = items && !Array.isArray(items) ? items.item : items;
  return Array.isArray(raw) ? raw : raw ? [raw] : [];
}
const total = (body) => parseInt(body?.response?.body?.totalCount, 10) || 0;
const hhmm = (s) => (s && String(s).length >= 12 ? `${String(s).slice(8, 10)}:${String(s).slice(10, 12)}` : null);
const S = (v) => (v == null || v === "" || v === "null" ? null : String(v).trim());
export const normNo = (n) => n.replace(/[^A-Za-z0-9]/g, "").toUpperCase();

export async function status(code, day) {
  if (code === ICN) {
    const term = (t) => ({ P01: "T1", P02: "탑승동", P03: "T2" })[t] || null;
    const one = async (dep) => {
      const out = [];
      for (let page = 1; page <= 5; page++) {
        const body = JSON.parse(await getText("w/icn/" + (dep ? "dep" : "arr"), { date: day, pageNo: page }));
        const r = rows(body);
        out.push(...r.filter((m) => S(m.flightId)).map((m) => ({
          flight: S(m.flightId), airline: S(m.airline) || "", scheduled: hhmm(m.scheduleDatetime), estimated: hhmm(m.estimatedDatetime), remark: S(m.remark),
          departure: dep, other: S(m.airport) || "", gate: [term(m.terminalId), S(m.gateNumber) !== "-" ? S(m.gateNumber) : null].filter(Boolean).join(" ") || null,
          codeshare: String(m.codeshare || "").toLowerCase() === "slave",
        })));
        if (r.length < 1000 || out.length >= total(body)) break;
      }
      return out;
    };
    return (await Promise.all([one(true), one(false)])).flat();
  }
  if (!(code in LOT_AIRPORT) && code !== "KPO") return [];
  const out = [];
  for (let page = 1; page <= 10; page++) {
    const body = JSON.parse(await getText("w/kac/flight-aircraft-status/info", { air: code, date: day, pageNo: page }));
    const r = rows(body);
    out.push(...r.filter((m) => S(m.airFln)).map((m) => ({
      flight: S(m.airFln), airline: S(m.airlineKorean) || "", scheduled: hhmm(m.std), estimated: hhmm(m.etd), remark: S(m.rmkKor),
      departure: m.io === "O", other: S(m.city) || "", gate: S(m.gate), codeshare: m.cdsrYn === "Y",
    })));
    if (r.length < 100 || out.length >= total(body)) break;
  }
  return out;
}

async function live(code) {
  const safe = (p) => p.then((t) => rows(JSON.parse(t))).catch(() => []);
  const v = { GMP: "v1", CJU: "v1", PUS: "v2", CJJ: "v2", TAE: "v2" }[code];
  const [cong, proc, lots] = await Promise.all([
    v ? safe(getText("w/kac/airport-congestion/" + v)) : [], safe(getText("w/kac/airport-process-time/v1")), safe(getText("w/kac/parking-congestion/info")),
  ]);
  const c = cong.find((m) => m.IATA_APCD === code), p = proc.find((m) => m.IATA_APCD === code);
  const n = (x) => (x == null || x === "" ? null : parseInt(x, 10));
  return {
    time: S(c?.PRC_HR), levels: ["A", "B", "C"].map((k) => n(c?.[`CGDR_${k}_LVL`])), all: n(c?.CGDR_ALL_LVL),
    processTime: S(p?.PRC_HR), processOpen: p ? n(p.OPR_STS_CD) === 1 : null, processSec: n(p?.STY_TCT_AVG_ALL),
    lots: LOT_AIRPORT[code] ? lots.filter((m) => String(m.airportKor || "").startsWith(LOT_AIRPORT[code])).map((m) => [S(m.parkingAirportCodeName), S(m.parkingCongestion), S(m.parkingCongestionDegree)]).filter((x) => x[0]) : [],
  };
}

async function staticInfo(code) {
  const root = await cached("data/files/airport_info.json");
  for (const [short, m] of Object.entries(root.airports || {})) {
    if (m.code !== code) continue;
    return {
      name: m.name || short, address: S(m.address), phone: S(m.phone),
      transport: (m.transport || []).filter((t) => t.type).map((t) => [t.type, t.routes || ""]),
      lounges: (m.lounges || []).map((l) => [l.name, l.place, l.hours].filter(Boolean).join(" · ")).filter(Boolean),
      shops: (m.shops || []).length, delay: m.delay?.["국내"] || {}, cancel: m.cancel?.["국내"] || {},
    };
  }
  return null;
}
const levelLabel = (l) => ({ 1: "원활", 2: "보통", 3: "혼잡", 4: "매우 혼잡" })[l] || "정보 없음";
const levelColor = (l) => ({ 1: "#2E9E5B", 2: "#3B7DDD", 3: "#F08C00", 4: "#D7262E" })[l] || "#9E9E9E";

// ── 국내선 시간표 화면 (대중교통 → 항공) ──
const air = { dep: "GMP", arr: "CJU", tomorrow: false, editing: null };
export function airScreen(root, chipsHtml) {
  let alive = true, data = null, failed = false, liveMap = new Map();
  const name = (c) => { const n = data?.airports[c]; return n ? (n.endsWith("공항") ? n : n + "공항") : c; };
  const loadLive = async () => {
    liveMap = new Map();
    if (air.tomorrow) return;
    const st = await status(air.dep, ymd()).catch(() => []);
    liveMap = new Map(st.filter((s) => s.departure && s.other).map((s) => [normNo(s.flight), s]));
    if (alive) draw();
  };
  function draw() {
    if (!alive) return;
    let h = header("모두의 지도", "국내선 항공 시간표") + chipsHtml;
    if (failed) { root.innerHTML = h + note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return; }
    if (!data) { root.innerHTML = h + note("시간표를 불러오는 중..."); return; }
    const t = air.tomorrow ? addDays(seoulNow(), 1) : seoulNow();
    const box = (k, title) => `<button class="pbox ${air.editing === k ? "on" : ""}" ${act(() => { air.editing = k; draw(); })}><small>${title}</small><b>${esc(name(air[k]))}</b></button>`;
    h += `<div class="searchtop"><div class="pboxes">${box("dep", "출발")}<button class="swap" ${act(() => { [air.dep, air.arr] = [air.arr, air.dep]; draw(); loadLive(); })}>⇄</button>${box("arr", "도착")}</div>
      <div class="toolrow"><div class="segs">${[["오늘", false], ["내일", true]].map(([l, v]) => `<button class="${air.tomorrow === v ? "on" : ""}" ${act(() => { air.tomorrow = v; draw(); loadLive(); })}>${l}</button>`).join("")}</div><small>${t.m}월 ${t.d}일 (${WEEK[isoDay(t) - 1]})</small></div></div>`;
    if (air.editing) {
      const codes = [...new Set(data.rows.filter((r) => !r.intl).flatMap((r) => [r.dep, r.arr]))].sort((a, b) => (PREFERRED.indexOf(a) + 100) % 100 - (PREFERRED.indexOf(b) + 100) % 100);
      root.innerHTML = h + `<div class="list">${codes.map((c) => `<button class="row" ${act(() => { air[air.editing] = c; air.editing = null; draw(); loadLive(); })}><b class="grow">${esc(name(c))}</b><small>${c}</small></button>`).join("")}</div>`;
      return;
    }
    h += `<div class="links">${[air.dep, air.arr].map((c) => `<button class="link" ${act(() => go("airport", c, { name: name(c) }))}>${esc(name(c))} 정보</button>`).join("")}<button class="link" ${act(() => go("airport", ICN, { name: "인천국제공항" }))}>인천공항 운항</button></div>`;
    const r = flights(data.rows, air.dep, air.arr, t);
    if (!r.list.length) { root.innerHTML = h + note(`${name(air.dep)} → ${name(air.arr)} 그날 운항편이 없어요`); return; }
    const now = air.tomorrow ? -1 : seoulNow().h * 60 + seoulNow().mi;
    const next = Math.max(0, r.list.findIndex((f) => f.depMin >= now));
    h += `<p class="note">${r.list.length}편${air.tomorrow ? "" : ` · 남은 편 ${r.list.filter((f) => f.depMin >= now).length}`}${r.projected ? " · 시간표 기간이 지나 지난주 운항으로 예상" : ""}</p>
      <div class="list">${r.list.map((f, i) => {
        const s = liveMap.get(normNo(f.flight));
        const tt = s ? [s.remark, s.estimated && s.estimated !== s.scheduled ? "변경 " + s.estimated : null, s.gate ? "탑승구 " + s.gate : null].filter(Boolean).join(" · ") : "";
        return `<div class="row ${f.depMin < now ? "gone" : ""}" data-i="${i}"><span class="grow"><b class="trip">${clock(f.depMin)}  →  ${clock(f.arrMin)}</b><small>${duration(f.arrMin - f.depMin)}</small></span>
          <span class="right"><b class="sm">${esc(data.airlines[f.airline] || f.airline)}</b><small>${esc(f.flight)}</small>${tt ? `<small class="${/지연|결항|변경/.test(tt) ? "red" : "blue"}">${esc(tt)}</small>` : ""}</span></div>`;
      }).join("")}</div>`;
    const y = root.scrollTop;
    root.innerHTML = h;
    if (y) root.scrollTop = y; else root.querySelector(`[data-i="${next}"]`)?.scrollIntoView({ block: "start" });
  }
  load().then((d) => { data = d; draw(); }).catch(() => { failed = true; draw(); });
  loadLive();
  draw();
  return () => { alive = false; };
}

// ── 공항 화면 ──
export function airportScreen(root, p) {
  const code = p.id, icn = code === ICN;
  let alive = true, tab = icn ? 1 : 0, info, lv, fl;
  const today = ymd();
  function draw() {
    if (!alive) return;
    const tabs = ["실시간", "출발", "도착", "공항 정보"].map((l, i) => ({ label: l, i })).filter((t) => !icn || t.i === 1 || t.i === 2);
    let h = header(info?.name || p.name || code, info?.address || null, { back: true }) + chips(tabs.map((t) => ({ label: t.label, on: tab === t.i, run: () => { tab = t.i; draw(); } })));
    const line = (l, r, c) => `<div class="row"><span class="grow">${esc(l)}</span>${r ? `<b style="${c ? "color:" + c : ""}">${esc(r)}</b>` : ""}</div>`;
    if (tab === 0) {
      if (!lv) { root.innerHTML = h + note("실시간 정보를 불러오는 중..."); return; }
      if (lv.all != null || lv.levels.some((x) => x != null)) h += sectionHead("출국장 혼잡도" + (lv.time ? ` · ${lv.time}시 기준` : "")) + `<div class="list">${line("전체", levelLabel(lv.all), levelColor(lv.all))}${lv.levels.map((x, i) => (x != null ? line("ABC"[i] + " 구역", levelLabel(x), levelColor(x)) : "")).join("")}</div>`;
      if (lv.processSec != null || lv.processOpen != null) h += sectionHead("출국 소요시간" + (lv.processTime ? ` · ${lv.processTime}시 기준` : "")) + `<div class="list">${line("보안검색까지 평균", lv.processOpen === false ? "운영 안 함" : lv.processSec != null ? `약 ${Math.max(1, Math.round(lv.processSec / 60))}분` : null)}</div>`;
      if (lv.lots.length) h += sectionHead("주차장") + `<div class="list">${lv.lots.map(([n, a, b]) => line(n, [a, b].filter(Boolean).join(" · ") || null)).join("")}</div>`;
      if (lv.all == null && lv.levels.every((x) => x == null) && lv.processSec == null && !lv.lots.length) h += note("이 공항은 실시간 정보가 없어요");
      root.innerHTML = h;
      return;
    }
    if (tab === 3) {
      if (info === undefined) { root.innerHTML = h + note("공항 정보를 불러오는 중..."); return; }
      if (!info) { root.innerHTML = h + note("공항 정보가 없어요"); return; }
      h += sectionHead("기본 정보") + `<div class="list">${info.address ? line(info.address) : ""}${info.phone ? line("대표 전화", info.phone) : ""}${info.shops ? line("입점 매장", info.shops + "곳") : ""}</div>`;
      if (info.transport.length) h += sectionHead("교통편") + `<div class="list">${info.transport.map(([t, r]) => `<div class="row col"><b>${esc(t)}</b>${r ? `<small>${esc(r)}</small>` : ""}</div>`).join("")}</div>`;
      if (info.lounges.length) h += sectionHead("라운지") + `<div class="list">${info.lounges.map((l) => line(l)).join("")}</div>`;
      if (info.delay["지연율(퍼센트)"] || info.cancel["결항률(퍼센트)"]) h += sectionHead("국내선 지연·결항 (지난해)") + `<div class="list">${info.delay["지연율(퍼센트)"] ? line("지연율", info.delay["지연율(퍼센트)"] + "%") : ""}${info.cancel["결항률(퍼센트)"] ? line("결항률", info.cancel["결항률(퍼센트)"] + "%") : ""}</div>`;
      root.innerHTML = h;
      return;
    }
    if (!fl) { root.innerHTML = h + note("실시간 운항을 불러오는 중..."); return; }
    const dep = tab === 1, seen = new Set();
    const list = fl.filter((f) => f.departure === dep && !f.codeshare && !seen.has(f.flight + f.scheduled) && seen.add(f.flight + f.scheduled)).sort((a, b) => (a.scheduled || "99").localeCompare(b.scheduled || "99"));
    if (!list.length) { root.innerHTML = h + note("오늘 실시간 운항 정보가 없어요"); return; }
    const t = seoulNow(), from = pad2(Math.floor(Math.max(0, t.h * 60 + t.mi - 30) / 60)) + ":" + pad2(Math.max(0, t.h * 60 + t.mi - 30) % 60);
    let next = list.findIndex((f) => (f.scheduled || "") >= from); if (next < 0) next = list.length - 1;
    root.innerHTML = h + `<div class="list">${list.map((f, i) => {
      const changed = f.estimated && f.estimated !== f.scheduled;
      return `<div class="row" data-i="${i}"><span class="grow"><b class="trip ${changed ? "red" : ""}">${esc(changed ? f.estimated : f.scheduled || "")}${changed ? `<small class="inl"> 예정 ${esc(f.scheduled)}</small>` : ""}</b>
        <small>${esc([(dep ? "→ " : "← ") + f.other, f.airline, f.flight].join("  ·  "))}</small></span>
        <span class="right">${f.remark ? `<b class="sm ${/지연|결항/.test(f.remark) ? "red" : "blue"}">${esc(f.remark)}</b>` : ""}${dep && f.gate ? `<small>탑승구 ${esc(f.gate)}</small>` : ""}</span></div>`;
    }).join("")}</div>`;
    root.querySelector(`[data-i="${next}"]`)?.scrollIntoView({ block: "start" });
  }
  if (!icn) {
    staticInfo(code).then((x) => { info = x; draw(); }).catch(() => { info = null; draw(); });
    live(code).then((x) => { lv = x; if (tab === 0) draw(); });
  } else info = null;
  status(code, today).then((x) => { fl = x; if (tab === 1 || tab === 2) draw(); }).catch(() => { fl = []; draw(); });
  draw();
  return () => { alive = false; };
}
