// 지도 그림 맞추기 (화면 없음: map.js 가 쓰고, 테스트도 불러옴)
// ── 그린 것 저장해 두고 다시 쓰기 ──
// 예전엔 지도를 옮기거나 자료가 올 때마다 점(정류장 최대 400개·자전거·주차장·편의시설)과 선(서울 소통 최대 180개 × 2겹)을
// 전부 지우고 새로 만들어서, 그동안 카카오 지도 그림(타일)이 늦게 뜨고 서울처럼 점이 많은 곳에서 버벅였음.
// 이제 모양이 같은 것은 그대로 두고, 새로 생긴 것만 만들고 없어진 것만 지움
/** 점 열쇠: 모양을 정하는 값 (같으면 그대로 둠) */
export const dotKey = (d) => [d.id, d.lat, d.lng, d.color, d.ring, d.r, d.label, d.labelUp ? 1 : 0, d.dir, d.arrow, d.chevron].join("|");
/** 선 열쇠: 색·굵기·점선·모든 꼭짓점 */
export const lineKey = (l) => [l.color, l.width, l.dash ? 1 : 0, l.pts.map((p) => p[0].toFixed(6) + "," + p[1].toFixed(6)).join(";")].join("|");
/** 지난번 [prev] (열쇠 → 그린 것) 와 이번 [items] 를 맞춤: 같은 열쇠는 다시 쓰고, 새것만 [make], 남은 것은 [remove]. 이번 것을 돌려줌 */
export function reconcile(prev, items, keyOf, make, remove) {
  const next = new Map();
  for (const it of items) {
    const k = keyOf(it);
    if (next.has(k)) continue;
    const old = prev.get(k);
    if (old !== undefined) { next.set(k, old); prev.delete(k); } else next.set(k, make(it));
  }
  for (const o of prev.values()) remove(o);
  return next;
}
