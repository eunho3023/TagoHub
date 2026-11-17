// 전국주차장정보표준데이터(공공데이터포털 표준데이터 15012896) → server/tago-proxy/site/parking/<칸>.json (+ index.json)
// 웹(site/js/parking.js)·앱(data/Parking.kt)이 화면에 걸친 칸만 받음. 칸 = 위도·경도 0.2도 ("<floor(위도*5)>_<floor(경도*5)>")
// 한 곳 = [이름, 위도, 경도, 주차면 수, 구분("공영 노외" 등), 요금 요약, 운영시간 요약, 전화]
// 로그인·인증키 필요 없음 (포털 화면의 JSON '다운로드' 단추가 쓰는 주소를 그대로 부름. pipeline/fetch_datago_std.py 와 같은 방식).
//
//     node pipeline/build_parking.mjs
import { mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";

const PK = "15012896";
const BASE = "https://www.data.go.kr";
const UA = { "User-Agent": "Mozilla/5.0" };
const DIR = new URL("../server/tago-proxy/site/parking/", import.meta.url);

const get = async (u) => {
  const r = await fetch(u, { headers: UA });
  if (!r.ok) throw new Error(`${r.status} ${u}`);
  return r.json();
};

/** 표준데이터 한 줄 → 한 곳 (좌표가 이상하면 null). 테스트하기 쉽게 따로 */
export function parkingRow(r) {
  const t = (s) => String(s ?? "").replace(/\s+/g, " ").trim();
  const lat = Math.round(+r.LATITUDE * 1e6) / 1e6, lng = Math.round(+r.LONGITUDE * 1e6) / 1e6;
  if (!(lat > 33 && lat < 39 && lng > 124 && lng < 132)) return null;
  const won = (v) => (+v > 0 ? `${(+v).toLocaleString("en-US")}원` : "");
  const min = (v) => (+v > 0 ? (+v % 60 === 0 && +v >= 60 ? `${+v / 60}시간` : `${+v}분`) : "");
  let fee = "";
  const info = t(r.PARKINGCHRGE_INFO);
  if (info === "무료") fee = "무료";
  else {
    // 잘못 적힌 값(2분에 5만 원 같은 것)은 뺌: 시간당 1만 원 넘으면 버림
    const sane = (fee, mins) => +fee > 0 && (+fee / Math.max(+mins || 60, 1)) * 60 <= 10000;
    const basic = sane(r.BASIC_CHARGE, r.BASIC_TIME) && `${min(r.BASIC_TIME)} ${won(r.BASIC_CHARGE)}`.trim();
    const add = sane(r.ADD_UNIT_CHARGE, r.ADD_UNIT_TIME) && `추가 ${min(r.ADD_UNIT_TIME)} ${won(r.ADD_UNIT_CHARGE)}`.replace(/\s+/g, " ");
    const day = won(r.DAY_CMMTKT) && `1일 ${won(r.DAY_CMMTKT)}`;
    fee = [basic, add, day].filter(Boolean).join(", ") || (info === "유료" ? "유료" : info);
  }
  const hm = (s) => {
    const v = t(s).replace(/^(\d{1,2})(\d{2})$/, "$1:$2");
    return /^\d{1,2}:\d{2}$/.test(v) ? v.padStart(5, "0") : "";
  };
  const o = hm(r.WEEKDAY_OPER_OPEN_HHMM), c = hm(r.WEEKDAY_OPER_COLSE_HHMM);
  const hours = o && c ? (o === "00:00" && (c === "23:59" || c === "24:00" || c === "00:00") ? "24시간" : `평일 ${o}~${c}`) : "";
  const kind = [t(r.PRKPLCE_SE), t(r.PRKPLCE_TYPE)].filter(Boolean).join(" ");
  return [t(r.PRKPLCE_NM) || "주차장", lat, lng, +r.PRKCMPRT || 0, kind, fee.slice(0, 60), hours, t(r.PHONE_NUMBER)];
}

/** 칸 이름 (0.2도) */
export const parkingTile = (lat, lng) => `${Math.floor(lat * 5)}_${Math.floor(lng * 5)}`;

if (import.meta.url === `file:///${process.argv[1].replace(/\\/g, "/").replace(/^\//, "")}` || process.argv[1]?.endsWith("build_parking.mjs")) {
  const head = await get(`${BASE}/download/columList.json?pk=${PK}&ext=JSON`);
  const vo = head.tableVO, total = +head.totalCount, per = 10000;
  const cols = Array.isArray(vo.colNmList) ? vo.colNmList : [vo.colNmList];
  const rows = [];
  for (let page = 1; (page - 1) * per < total; page++) {
    const q = new URLSearchParams();
    for (const c of cols) q.append("colNmList", c);
    q.set("totalCount", total); q.set("svcTableNm", vo.svcTableNm); q.set("perPage", per); q.set("page", page); q.set("publicDataPk", PK);
    rows.push(...(await get(`${BASE}/download/standard.json?${q}`)));
  }
  let ref = "";
  const tiles = new Map(), seen = new Set();
  for (const r of rows) {
    const p = parkingRow(r);
    if (!p) continue;
    const k = `${p[0]}|${p[1]}|${p[2]}`;
    if (seen.has(k)) continue;
    seen.add(k);
    const tile = parkingTile(p[1], p[2]);
    if (!tiles.has(tile)) tiles.set(tile, []);
    tiles.get(tile).push(p);
    const d = String(r.REFERENCE_DATE || "").trim();
    if (d > ref) ref = d;
  }
  mkdirSync(DIR, { recursive: true });
  for (const f of readdirSync(DIR)) rmSync(new URL(f, DIR));
  for (const [tile, list] of tiles) writeFileSync(new URL(`${tile}.json`, DIR), JSON.stringify(list));
  writeFileSync(new URL("index.json", DIR), JSON.stringify({ ref, count: seen.size, tiles: [...tiles.keys()].sort() }));
  console.log(`${rows.length}건 → ${seen.size}곳, 칸 ${tiles.size}개 (기준일 ${ref})`);
}
