// 공영자전거 대여소 점 (서울 따릉이 + 대전 타슈·세종 어울링·창원 누비자 등 전국 공영자전거). 자료는 서버 /w/topis/bike (src/seoultraffic.js), 그리기는 topis.js 교통 층
/** 대여소는 5천 곳이 넘어서 더 확대했을 때만, 화면 가운데에서 가까운 것부터 이만큼 */
export const BIKE_ZOOM = 15;
export const BIKE_MAX = 300;

/** 대여소 점 (글자 = 빌릴 수 있는 대수, 0대면 회색). 화면 범위 b 안, 가운데에서 가까운 순 */
export function bikeDots(list, b, max = BIKE_MAX) {
  if (!b) return [];
  const cla = (b.s + b.n) / 2, clo = (b.w + b.e) / 2;
  return list.filter((x) => x.lat >= b.s && x.lat <= b.n && x.lng >= b.w && x.lng <= b.e)
    .map((x) => [x, Math.hypot(x.lat - cla, (x.lng - clo) * 0.8)]).sort((p, q) => p[1] - q[1]).slice(0, max)
    .map(([x]) => ({ id: "bike:" + x.id, lat: x.lat, lng: x.lng, color: x.bikes > 0 ? "#2e7d32" : "#9e9e9e", ring: "#fff", r: 8, label: String(x.bikes) }));
}
