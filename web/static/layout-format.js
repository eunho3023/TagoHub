/* 노선 배치도 좌표 파일(bus_route_layouts.tsv) 읽기·쓰기
   형식: 노선ID \t 노드ID:x:y,노드ID:x:y,…
   앱의 RouteDiagram.parseOfficial 과 같은 규칙이다. 브라우저와 Node 양쪽에서 쓴다. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.LayoutFormat = api;
})(typeof self !== 'undefined' ? self : this, () => {
  const sameRoute = (id, routeId) => !routeId || id === routeId || id === String(routeId).replace(/^SEB/, '');

  /** TSV 한 줄 · JSON 을 { nodeId: [x, y] } 로 바꾼다. 못 읽으면 null */
  function parseLayoutText(text, routeId) {
    const raw = String(text || '').trim();
    if (!raw) return null;

    if (raw.startsWith('{') || raw.startsWith('[')) {
      try {
        const j = JSON.parse(raw);
        const src = j && j.cells ? j.cells : j;
        const out = {};
        if (Array.isArray(src)) {
          src.forEach((c) => {
            if (Array.isArray(c) && c.length >= 3) out[String(c[0])] = [Number(c[1]), Number(c[2])];
          });
        } else if (src && typeof src === 'object') {
          Object.entries(src).forEach(([node, p]) => {
            if (Array.isArray(p) && p.length >= 2) out[node] = [Number(p[0]), Number(p[1])];
          });
        }
        const ok = Object.entries(out).filter(([, p]) => Number.isFinite(p[0]) && Number.isFinite(p[1]));
        return ok.length ? out : null;
      } catch { /* JSON 이 아니면 TSV 로 본다 */ }
    }

    for (const ln of raw.split(/\r?\n/)) {
      const c = ln.split('\t');
      if (c.length < 2) continue;
      if (!sameRoute(c[0].trim(), routeId)) continue;
      const out = {};
      for (const e of c[1].split(',')) {
        const p = e.split(':');
        if (p.length !== 3) continue;
        const x = Number(p[1]), y = Number(p[2]);
        if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
        out[p[0].trim()] = [Math.round(x), Math.round(y)];
      }
      if (Object.keys(out).length) return out;
    }
    return null;
  }

  /** { nodeId: [x, y] } + 정류장 순서를 TSV 한 줄로 만든다 */
  function toTsv(routeId, cells, order) {
    const ids = order && order.length ? order.filter((id) => cells[id]) : Object.keys(cells || {});
    const body = ids.map((id) => `${id}:${Math.round(cells[id][0])}:${Math.round(cells[id][1])}`).join(',');
    if (!body) return '';
    return `${String(routeId || '').replace(/^SEB/, '')}\t${body}`;
  }

  return { parseLayoutText, toTsv };
});
