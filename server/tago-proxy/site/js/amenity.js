// 편의시설 점 (공공데이터포털 표준데이터: 공중화장실·전동휠체어 급속충전기·졸음쉼터·교통약자 이동지원센터·환승센터·자동차 정비소·버스전용차로 시작점·일방통행 시작점·횡단보도).
// 자료는 amenity/<종류>/<칸>.json (pipeline/build_amenities.mjs, 위도·경도 0.2도 칸, 적은 종류는 all.json 하나), 그리기는 topis.js 교통 층
/** 종류마다 글자·색·보이기 시작하는 확대 */
export const PLACE_KINDS = {
  toilet: { label: "화", color: "#00838f", zoom: 16 },
  wheel: { label: "휠", color: "#6a1b9a", zoom: 15 },
  drowsy: { label: "졸", color: "#ef6c00", zoom: 10 },
  mobility: { label: "약", color: "#ad1457", zoom: 10 },
  transfer: { label: "환", color: "#1565c0", zoom: 11 },
  repair: { label: "정", color: "#5d4037", zoom: 15 },
  buslane: { label: "전", color: "#0277bd", zoom: 11 },
  oneway: { label: "일", color: "#c62828", zoom: 16 },
  crosswalk: { label: "횡", color: "#455a64", zoom: 17 },
};
/** 레이어 고르기에 보이는 이름 (topis.js) */
export const PLACE_NAMES = { toilet: "공중화장실", wheel: "전동휠체어 충전기", drowsy: "졸음쉼터", mobility: "교통약자 이동지원센터", transfer: "환승센터",
  repair: "자동차 정비소", buslane: "버스전용차로", oneway: "일방통행", crosswalk: "횡단보도" };
/** 한 종류에서 한 번에 보여 줄 수 (화면 가운데에서 가까운 것부터) */
export const PLACE_MAX = 120;
const MAX_TILES = 12;

/** 화면 범위 b 에 걸친 칸 이름들, 가운데 칸부터 */
export function placeTilesIn(b) {
  const cla = (b.s + b.n) / 2 * 5, clo = (b.w + b.e) / 2 * 5;
  const out = [];
  for (let a = Math.floor(b.s * 5); a <= Math.floor(b.n * 5); a++)
    for (let o = Math.floor(b.w * 5); o <= Math.floor(b.e * 5); o++) out.push([`${a}_${o}`, Math.hypot(a + 0.5 - cla, o + 0.5 - clo)]);
  return out.sort((x, y) => x[1] - y[1]).map((x) => x[0]);
}

/** 칸 자료 [[이름, 위도, 경도, 첫째 줄, 둘째 줄]] → {id, kind, name, lat, lng, lines} */
export function placeList(kind, tile, rows) {
  return (Array.isArray(rows) ? rows : []).map(([name, lat, lng, a, b], i) => ({
    id: `${kind}:${tile}:${i}`, kind, name, lat, lng, lines: [a, b].filter(Boolean),
  }));
}

/** 점 (글자 하나). 화면 범위 b 안, 가운데에서 가까운 순 */
export function placeDots(list, b, max = PLACE_MAX) {
  if (!b) return [];
  const cla = (b.s + b.n) / 2, clo = (b.w + b.e) / 2;
  return list.filter((x) => x.lat >= b.s && x.lat <= b.n && x.lng >= b.w && x.lng <= b.e)
    .map((x) => [x, Math.hypot(x.lat - cla, (x.lng - clo) * 0.8)]).sort((p, q) => p[1] - q[1]).slice(0, max)
    .map(([x]) => ({ id: "pl:" + x.id, lat: x.lat, lng: x.lng, color: PLACE_KINDS[x.kind].color, ring: "#fff", r: 8, label: PLACE_KINDS[x.kind].label }));
}

const indexP = new Map();
const tileP = new Map();
/** 화면 범위 b 의 한 종류 (있는 칸만 받음, 받은 칸은 기억) */
export async function placesIn(kind, b) {
  if (!indexP.has(kind)) indexP.set(kind, fetch(`amenity/${kind}/index.json`).then((r) => (r.ok ? r.json() : { tiles: [] })).catch(() => { indexP.delete(kind); return { tiles: [] }; }));
  const have = (await indexP.get(kind)).tiles || [];
  const want = have.includes("all") ? ["all"] : placeTilesIn(b).filter((t) => have.includes(t)).slice(0, MAX_TILES);
  const lists = await Promise.all(want.map((t) => {
    const key = `${kind}/${t}`;
    if (!tileP.has(key)) tileP.set(key, fetch(`amenity/${key}.json`).then((r) => (r.ok ? r.json() : [])).then((rows) => placeList(kind, t, rows)).catch(() => { tileP.delete(key); return []; }));
    return tileP.get(key);
  }));
  return lists.flat();
}
