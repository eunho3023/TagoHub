// 주차장 점 (전국주차장정보표준데이터 1만 7천여 곳). 자료는 parking/<칸>.json (pipeline/build_parking.mjs, 위도·경도 0.2도 칸), 그리기는 topis.js 교통 층
/** 많아서 더 확대했을 때만, 화면 가운데에서 가까운 것부터 이만큼 */
export const PARKING_ZOOM = 16;
export const PARKING_MAX = 200;
export const PARKING_COLOR = "#1565c0";

/** 칸 이름 (pipeline/build_parking.mjs 와 같음) */
export const parkingTile = (lat, lng) => `${Math.floor(lat * 5)}_${Math.floor(lng * 5)}`;

/** 화면 범위 b 에 걸친 칸 이름들 */
export function tilesIn(b) {
  const out = [];
  for (let a = Math.floor(b.s * 5); a <= Math.floor(b.n * 5); a++)
    for (let o = Math.floor(b.w * 5); o <= Math.floor(b.e * 5); o++) out.push(`${a}_${o}`);
  return out;
}

/** 칸 자료 [[이름, 위도, 경도, 면 수, 구분, 요금, 운영시간, 전화]] → {id, name, lat, lng, spaces, kind, fee, hours, phone} */
export function parkingList(tile, rows) {
  return (Array.isArray(rows) ? rows : []).map(([name, lat, lng, spaces, kind, fee, hours, phone], i) => ({
    id: `${tile}:${i}`, name, lat, lng, spaces, kind, fee, hours, phone,
  }));
}

/** 주차장 점 (글자 P). 화면 범위 b 안, 가운데에서 가까운 순 */
export function parkingDots(list, b, max = PARKING_MAX) {
  if (!b) return [];
  const cla = (b.s + b.n) / 2, clo = (b.w + b.e) / 2;
  return list.filter((x) => x.lat >= b.s && x.lat <= b.n && x.lng >= b.w && x.lng <= b.e)
    .map((x) => [x, Math.hypot(x.lat - cla, (x.lng - clo) * 0.8)]).sort((p, q) => p[1] - q[1]).slice(0, max)
    .map(([x]) => ({ id: "park:" + x.id, lat: x.lat, lng: x.lng, color: PARKING_COLOR, ring: "#fff", r: 8, label: "P" }));
}

/** 카드 둘째·셋째 줄: "공영 노외 · 169면" / "5분 250원 · 24시간 · 02-…" */
export function parkingLines(x) {
  return [
    [x.kind, x.spaces > 0 ? `${x.spaces}면` : ""].filter(Boolean).join(" · "),
    [x.fee, x.hours, x.phone].filter(Boolean).join(" · "),
  ].filter(Boolean);
}

let indexP = null;
const tileP = new Map();
/** 화면 범위 b 의 주차장 (있는 칸만 받음, 받은 칸은 기억) */
export async function parkingIn(b) {
  if (!indexP) indexP = fetch("parking/index.json").then((r) => (r.ok ? r.json() : { tiles: [] })).catch(() => { indexP = null; return { tiles: [] }; });
  const have = new Set((await indexP).tiles || []);
  const want = tilesIn(b).filter((t) => have.has(t)).slice(0, 12);
  const lists = await Promise.all(want.map((t) => {
    if (!tileP.has(t)) tileP.set(t, fetch(`parking/${t}.json`).then((r) => (r.ok ? r.json() : [])).then((rows) => parkingList(t, rows)).catch(() => { tileP.delete(t); return []; }));
    return tileP.get(t);
  }));
  return lists.flat();
}
