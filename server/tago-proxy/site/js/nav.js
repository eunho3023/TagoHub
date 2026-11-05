// 화면 이동 (주소 #/이름/id?값) — 여러 화면 모듈이 같이 씀
export const wideQ = matchMedia("(min-width: 860px)");
export const isWide = () => wideQ.matches;

/** 화면 이동: go("stop", id, {lat, lng}) */
export function go(name, id, params) {
  const q = params ? "?" + new URLSearchParams(Object.entries(params).filter(([, v]) => v != null)).toString() : "";
  location.hash = "#/" + name + (id != null ? "/" + encodeURIComponent(id) : "") + q;
}
