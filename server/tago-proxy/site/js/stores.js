// 주변 가게 지도 층: 소상공인시장진흥공단 상가(상권)정보 (서버 /w/pub/store, 2026-11-24 승인). 편의점·카페·음식점·약국·병원.
// 지도 가운데(약 50m 칸)에서 가까운 15곳 (서버가 300m·800m·1.5km 로 넓혀 가며 찾음). 같은 칸은 한 번만 물음
import { getJson, esc, distText } from "./core.js";

/** 서버 분류 열쇠(앱 분류 단추와 같은 카카오식 코드) → 이름·글자·색 */
export const STORE_KINDS = {
  CS2: { name: "편의점", label: "편", color: "#7b1fa2" },
  CE7: { name: "카페", label: "카", color: "#6d4c41" },
  FD6: { name: "음식점", label: "식", color: "#d84315" },
  PM9: { name: "약국", label: "약", color: "#2e7d32" },
  HP8: { name: "병원", label: "병", color: "#1565c0" },
};
export const STORE_ZOOM = 16;
const memo = new Map();
const cell = (v) => (Math.round(v * 2000) / 2000).toFixed(4);

/** 화면 가운데 근처 [kind] 가게 [{id, name, address, lat, lng, category, dist}] */
export function storesAt(kind, b) {
  if (!STORE_KINDS[kind] || !b) return Promise.resolve([]);
  const lat = cell((b.s + b.n) / 2), lng = cell((b.w + b.e) / 2), k = kind + "|" + lat + "|" + lng;
  if (!memo.has(k)) {
    if (memo.size > 300) memo.clear();
    memo.set(k, getJson("w/pub/store", { cat: kind, lat, lng })
      .then((r) => (r.places || []).filter((x) => Number.isFinite(x.lat) && Number.isFinite(x.lng))
        .map((x) => ({ ...x, kind, id: kind + ":" + x.lat.toFixed(5) + "," + x.lng.toFixed(5) })))
      .catch(() => { memo.delete(k); return []; }));
  }
  return memo.get(k);
}
export function storeDots(list) {
  return (list || []).map((x) => ({ id: "st:" + x.id, lat: x.lat, lng: x.lng, color: STORE_KINDS[x.kind].color, ring: "#fff", r: 8, label: STORE_KINDS[x.kind].label }));
}
export function storeCard(x) {
  const sub = [x.category || STORE_KINDS[x.kind].name, x.address].filter(Boolean).join(" · ");
  return `<div><b>${esc(x.name)}</b>${sub ? `<small>${esc(sub)}</small>` : ""}<small>소상공인시장진흥공단 상가정보${x.dist != null ? " · 지도 가운데에서 " + distText(x.dist) : ""}</small></div>`;
}
