// 공공데이터포털 표준데이터 중 길에서 쓸 만한 편의시설 → server/tago-proxy/site/amenity/<종류>/<칸>.json (+ index.json)
// 웹(site/js/amenity.js)·앱(data/Amenity.kt)이 화면에 걸친 칸만 받음. 칸 = 위도·경도 0.2도 ("<floor(위도*5)>_<floor(경도*5)>"),
// 곳 수가 적은 종류는 칸을 나누지 않고 all.json 하나 (index.json 의 tiles 가 ["all"]).
// 한 곳 = [이름, 위도, 경도, 첫째 줄, 둘째 줄]  (카드에 그대로 씀)
// 로그인·인증키 필요 없음 (포털 화면의 JSON '다운로드' 단추가 쓰는 주소. build_parking.mjs 와 같은 방식).
//
//     node pipeline/build_amenities.mjs            (전부)
//     node pipeline/build_amenities.mjs toilet     (하나만)
import { mkdirSync, rmSync, writeFileSync } from "node:fs";

const BASE = "https://www.data.go.kr";
const UA = { "User-Agent": "Mozilla/5.0" };
const ROOT = new URL("../server/tago-proxy/site/amenity/", import.meta.url);

const t = (s) => String(s ?? "").replace(/\s+/g, " ").trim();
const yes = (s) => /^y$/i.test(t(s));
const num = (s) => (Number.isFinite(+t(s)) ? +t(s) : 0);
/** "0900"·"9:00" → "09:00" (아니면 "") */
export const hm = (s) => {
  const v = t(s).replace(/^(\d{1,2})(\d{2})$/, "$1:$2");
  return /^\d{1,2}:\d{2}$/.test(v) ? v.padStart(5, "0") : "";
};
/** 시작·끝 → "24시간" / "09:00~18:00" / "" */
export const span = (o, c) => {
  o = hm(o); c = hm(c);
  if (!o || !c) return "";
  // 00:00~00:00 은 "안 함"·"모름" 으로 적힌 곳이 많아 비움
  if (o === "00:00" && (c === "23:59" || c === "24:00")) return "24시간";
  return o === c ? "" : `${o}~${c}`;
};
const line = (...xs) => xs.map(t).filter(Boolean).join(" · ");
const dash = (s) => (/^(-|없음|데이터없음|해당없음)$/.test(t(s)) ? "" : t(s));

/** 종류마다: 표준데이터 번호, 칸 나눔 여부, 한 줄 → [이름, 첫째 줄, 둘째 줄] */
export const KINDS = {
  // 공중화장실 (3만 3천여 곳. 포털 파일이 2020년 무렵 자료지만 자리는 잘 안 바뀜)
  toilet: {
    pk: "15012892", tile: true,
    row: (r) => {
      const open = dash(r.OPEN_TIME).replace(/^00:00\s*[~-]\s*24:00$/, "24시간").slice(0, 30);
      const h = num(r.MEN_HANDICAP_TOILET_BOWL_NUMBER) + num(r.LADIES_HANDICAP_TOILET_BOWL_NUMBER) > 0;
      const kid = num(r.MEN_CHILDREN_TOILET_BOWL_NUMBER) + num(r.LADIES_CHILDREN_TOILET_BOWL_NUMBER) > 0;
      return [t(r.TOILET_NM) || "화장실",
        line(r.TOILET_TYPE, open, yes(r.UNISEX_TOILET_YN) ? "남녀공용" : ""),
        line(h ? "장애인용 있음" : "", kid ? "어린이용 있음" : "", dash(r.DIPERS_EXCHG_POSI) ? "기저귀교환대" : "", yes(r.EMG_BELL_YN) ? "비상벨" : "", r.PHONE_NUMBER)];
    },
  },
  // 전동휠체어 급속충전기 (4천 4백여 곳)
  wheel: {
    pk: "15034533", tile: true,
    row: (r) => {
      const wk = span(r.WEEKDAY_OPER_OPEN_HHMM, r.WEEKDAY_OPER_COLSE_HHMM), sat = span(r.SAT_OPER_OPER_OPEN_HHMM, r.SAT_OPER_CLOSE_HHMM);
      return [t(r.FCLTY_NM) || "전동휠체어 충전기",
        line(dash(r.INSTL_LC_DESC).slice(0, 30), num(r.SMTM_USE_CO) > 0 ? `동시 ${num(r.SMTM_USE_CO)}대` : "", yes(r.AIR_INJECTOR_YN) ? "공기주입" : "", yes(r.MOBLPHON_CHRSTN_YN) ? "휴대폰 충전" : ""),
        line(wk && `평일 ${wk}`, sat && sat !== wk ? `토 ${sat}` : "", r.INSTITUTION_PHONE_NUMBER)];
    },
  },
  // 졸음쉼터 (고속도로·국도 250여 곳)
  drowsy: {
    pk: "15028203", tile: false,
    row: (r) => [`${t(r.SHLTR_NM).replace(/졸음쉼터$/, "").trim() || "졸음"} 졸음쉼터`,
      line([t(r.ROAD_ROUTE_NM), dash(r.ROAD_ROUTE_DRC)].filter(Boolean).join(" "), num(r.PRKPLCE_CO) > 0 ? `${num(r.PRKPLCE_CO)}면` : "", yes(r.TOILET_YN) ? "화장실" : ""),
      line(dash(r.ETC_CVNTL).slice(0, 30), r.PHONE_NUMBER)],
  },
  // 교통약자 이동지원센터 (특별교통수단 예약 전화·앱, 170여 곳)
  mobility: {
    pk: "15028207", tile: false,
    row: (r) => {
      const rc = span(r.WEEKDAY_RCEPT_OPEN_HHMM, r.WEEKDAY_RCEPT_COLSE_HHMM), op = span(r.WEEKDAY_OPER_OPEN_HHMM, r.WEEKDAY_OPER_COLSE_HHMM);
      return [t(r.TFCWKER_MVMN_CNTER_NM) || "교통약자 이동지원센터",
        line(t(r.RCEPT_PHONE_NUMBER) && `예약 ${t(r.RCEPT_PHONE_NUMBER)}`, dash(r.APP_SVC_NM) && `앱 ${t(r.APP_SVC_NM)}`, rc && `접수 ${rc}`, op && `운행 ${op}`),
        line(dash(r.USE_CHARGE).slice(0, 40), num(r.CAR_HOLD_CO) > 0 ? `차량 ${num(r.CAR_HOLD_CO)}대` : "")];
    },
  },
  // 자동차 정비업체 (3만 6천여 곳, 2026-09 기준). 영업 중(영업상태 01)인 곳만
  repair: {
    pk: "15028204", tile: true,
    keep: (r) => +t(r.BSN_STTUS) === 1 && !t(r.CLSBIZ_DATE),   // "01"·"1" 둘 다 영업 (2 휴업·3 폐업)
    row: (r) => [t(r.INSPOFC_NM) || "자동차 정비소",
      line(t(r.RDNMADR) || t(r.LNMADR)).slice(0, 40),
      line(span(r.OPER_OPEN_HM, r.OPER_CLOSE_HM), r.PHONE_NUMBER)],
  },
  // 버스전용차로 (140여 구간, 시작점에 점). 지정 해제된 구간은 뺌
  buslane: {
    pk: "15028197", tile: false,
    keep: (r) => !/^n$/i.test(t(r.APPN_RELIS_YN)),
    pos: (r) => [r.ROAD_BEGIN_LATITUDE, r.ROAD_BEGIN_LONGITUDE],
    row: (r) => {
      const wk = laneHours(r.WEEKDAY_OPER_OPEN_HHMM, r.WEEKDAY_OPER_COLSE_HHMM);
      return [`${t(r.ROAD_ROUTE_NM) || "버스"} 버스전용차로`,
        line([t(r.ROAD_BEGIN_NM), t(r.ROAD_END_NM)].filter(Boolean).join(" → "), num(r.TOT_ET) > 0 ? `${num(r.TOT_ET)}km` : ""),
        line(wk && `평일 ${wk}`, r.PHONE_NUMBER)];
    },
  },
  // 일방통행도로 (2,581구간, 2025~2026 기준). 시작점에 점, 카드에 가는 방향·길이·폭
  oneway: {
    pk: "15028199", tile: true,
    pos: (r) => [r.START_LATITUDE, r.START_LONGITUDE],
    row: (r) => {
      const dir = heading(+r.START_LATITUDE, +r.START_LONGITUDE, +r.END_LATITUDE, +r.END_LONGITUDE);
      return [`${t(r.ROAD_NM) || "도로"} 일방통행`,
        line(dir && `${dir}으로 한 방향`, num(r.ROAD_ET) > 0 ? `길이 ${num(r.ROAD_ET)}m` : "", num(r.ROAD_BT) > 0 ? `폭 ${num(r.ROAD_BT)}m` : "", num(r.CARTRK_CO) > 0 ? `${num(r.CARTRK_CO)}차로` : ""),
        line(dash(r.APPN_RESN).slice(0, 30), r.PHONE_NUMBER)];
    },
  },
  // 횡단보도 (5만 8천여 곳, 대부분 2026 기준). 신호등·음향신호기·고원식 등
  crosswalk: {
    pk: "15028201", tile: true,
    row: (r) => [`${t(r.ROAD_NM) || dash(r.RDNMADR) || "횡단보도"}`.slice(0, 30) + " 횡단보도",
      line(yes(r.TFCLGHT_YN) ? "보행자 신호등" : "신호등 없음", yes(r.SOND_SGNGNR_YN) ? "음향신호기" : "", yes(r.FNCTNG_SGNGNR_YN) ? "누름 버튼" : "",
        yes(r.HIGHLAND_YN) ? "고원식" : "", yes(r.BCYCL_CRSLK_CMBNAT_YN) ? "자전거 겸용" : "", yes(r.BRLL_BLCK_YN) ? "점자블록" : ""),
      line(num(r.CARTRK_CO) > 0 ? `${num(r.CARTRK_CO)}차로` : "", num(r.ET) > 0 ? `길이 ${num(r.ET)}m` : "", num(r.GREEN_SGNGNR_TIME) > 0 ? `녹색 ${num(r.GREEN_SGNGNR_TIME)}초` : "")],
  },
  // 대중교통 환승센터 (40곳)
  transfer: {
    pk: "15034541", tile: false,
    row: (r) => [t(r.TRNSITLC_CNTER_NM) || "환승센터",
      line(dash(r.CTYRLROAD_STATN_NM) && `${t(r.CTYRLROAD_STATN_NM)}역`, dash(r.HSDL_STATN_NM) && `${t(r.HSDL_STATN_NM)}역`, dash(r.PVTRML_NM), dash(r.BUS_STTN_NM) && `${t(r.BUS_STTN_NM)} 정류장`),
      line(num(r.GNRL_PRKCMPRT) > 0 ? `주차 ${num(r.GNRL_PRKCMPRT)}면` : "", yes(r.NO_HANDICAP_ENVRN_YN) ? "무장애" : "", dash(r.CVNTL_TYPE).slice(0, 30))],
  },
};

/** 시작점 → 끝점 방향 (8방위, 너무 가까우면 "") */
export function heading(la1, lo1, la2, lo2) {
  if (![la1, lo1, la2, lo2].every(Number.isFinite)) return "";
  const dy = (la2 - la1) * 111000, dx = (lo2 - lo1) * 111000 * Math.cos((la1 * Math.PI) / 180);
  if (Math.hypot(dx, dy) < 5) return "";
  const deg = ((Math.atan2(dx, dy) * 180) / Math.PI + 360) % 360;
  return ["북쪽", "북동쪽", "동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽"][Math.round(deg / 45) % 8];
}

/** 버스전용차로 운영시간: "출근시각 07:00 + 퇴근시각 17:00" / "출근시각 09:00 + 퇴근시각 19:00" → "07:00~09:00, 17:00~19:00". 00:00~00:00·같은 시각은 "종일" */
export function laneHours(o, c) {
  const a = t(o).match(/\d{1,2}:\d{2}/g) || [], b = t(c).match(/\d{1,2}:\d{2}/g) || [];
  if (!a.length || a.length !== b.length) return "";
  const p = a.map((x, i) => [hm(x), hm(b[i])]);
  if (p.every(([x, y]) => x === y || (x === "00:00" && (y === "23:59" || y === "24:00")))) return "종일";
  return p.map(([x, y]) => `${x}~${y}`).join(", ");
}

/** 표준데이터 한 줄 → 한 곳 (좌표가 이상하거나 운영 안 하면 null) */
export function placeRow(kind, r) {
  const k = KINDS[kind];
  if (k.keep && !k.keep(r)) return null;
  const [la, lo] = k.pos ? k.pos(r) : [r.LATITUDE, r.LONGITUDE];
  const lat = Math.round(+la * 1e6) / 1e6, lng = Math.round(+lo * 1e6) / 1e6;
  if (!(lat > 33 && lat < 39 && lng > 124 && lng < 132)) return null;
  if (r.OPER_YN != null && /^n$/i.test(t(r.OPER_YN))) return null;
  const [name, a, b] = KINDS[kind].row(r);
  return [name, lat, lng, a, b];
}

/** 칸 이름 (0.2도) */
export const placeTile = (lat, lng) => `${Math.floor(lat * 5)}_${Math.floor(lng * 5)}`;

const get = async (u) => {
  const r = await fetch(u, { headers: UA });
  if (!r.ok) throw new Error(`${r.status} ${u}`);
  return r.json();
};
async function download(pk) {
  const head = await get(`${BASE}/download/columList.json?pk=${pk}&ext=JSON`);
  const vo = head.tableVO, total = +head.totalCount, per = 10000;
  const cols = Array.isArray(vo.colNmList) ? vo.colNmList : [vo.colNmList];
  const rows = [];
  // 안내 건수(totalCount)가 실제보다 적게 오는 자료가 있어(횡단보도: 50,000 → 실제 58,852) 쪽이 덜 찰 때까지 받음
  for (let page = 1; page <= 50; page++) {
    const q = new URLSearchParams();
    for (const c of cols) q.append("colNmList", c);
    q.set("totalCount", total); q.set("svcTableNm", vo.svcTableNm); q.set("perPage", per); q.set("page", page); q.set("publicDataPk", pk);
    const got = await get(`${BASE}/download/standard.json?${q}`);
    rows.push(...got);
    if (got.length < per) break;
  }
  return rows;
}

if (process.argv[1]?.replace(/\\/g, "/").endsWith("build_amenities.mjs")) {
  const pick = process.argv.slice(2);
  for (const kind of Object.keys(KINDS).filter((k) => !pick.length || pick.includes(k))) {
    const rows = await download(KINDS[kind].pk);
    let ref = "";
    const tiles = new Map(), seen = new Set();
    for (const r of rows) {
      const p = placeRow(kind, r);
      if (!p) continue;
      const k = `${p[0]}|${p[1]}|${p[2]}`;
      if (seen.has(k)) continue;
      seen.add(k);
      const tile = KINDS[kind].tile ? placeTile(p[1], p[2]) : "all";
      if (!tiles.has(tile)) tiles.set(tile, []);
      tiles.get(tile).push(p);
      const d = t(r.REFERENCE_DATE);
      if (d > ref) ref = d;
    }
    const dir = new URL(`${kind}/`, ROOT);
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    for (const [tile, list] of tiles) writeFileSync(new URL(`${tile}.json`, dir), JSON.stringify(list));
    writeFileSync(new URL("index.json", dir), JSON.stringify({ ref, count: seen.size, tiles: [...tiles.keys()].sort() }));
    console.log(`${kind}: ${rows.length}건 → ${seen.size}곳, 칸 ${tiles.size}개 (기준일 ${ref})`);
  }
}
