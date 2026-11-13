/* 노선 모양 파일(bus_route_shapes.tsv) 읽기·쓰기
   형식: 노선ID \t 첫점(위도,경도) \t 이어지는 점들의 1e-5도 변화량(위도,경도 쌍)
   앱의 RouteShapes.parse 와 같은 규칙이다. 브라우저와 Node 양쪽에서 쓴다. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ShapeFormat = api;
})(typeof self !== 'undefined' ? self : this, () => {
  const sameRoute = (id, routeId) => !routeId || id === routeId || id === String(routeId).replace(/^SEB/, '');

  /** TSV 한 줄(또는 여러 줄) · JSON · "위도,경도" 목록을 점 배열로 바꾼다. 못 읽으면 null */
  function parseShapeText(text, routeId) {
    const raw = String(text || '');
    try {
      const j = JSON.parse(raw);
      const arr = Array.isArray(j) ? j : j && j.pts;
      if (Array.isArray(arr)) {
        const pts = arr.map((p) => [Number(p[0]), Number(p[1])]).filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]));
        if (pts.length) return pts;
      }
    } catch { /* JSON 이 아니면 아래 형식으로 본다 */ }

    const lines = raw.split(/\r?\n/).filter((l) => l.trim());
    for (const ln of lines) {
      const c = ln.split('\t');
      if (c.length >= 3) {
        const id = c[0].trim();
        if (!sameRoute(id, routeId)) continue;
        const first = c[1].split(',').map(Number);
        if (first.length !== 2 || !Number.isFinite(first[0]) || !Number.isFinite(first[1])) continue;
        const out = [[first[0], first[1]]];
        let la = Math.round(first[0] * 1e5), ln2 = Math.round(first[1] * 1e5);
        const d = c[2].split(',').map(Number);
        for (let i = 0; i + 1 < d.length; i += 2) {
          if (!Number.isFinite(d[i]) || !Number.isFinite(d[i + 1])) break;
          la += d[i]; ln2 += d[i + 1];
          out.push([la / 1e5, ln2 / 1e5]);
        }
        if (out.length >= 2) return out;
      }
    }

    // "위도,경도" 한 줄씩 적은 목록
    const pairs = lines.map((l) => l.split(',').map(Number));
    if (pairs.length >= 2 && pairs.every((p) => p.length === 2 && Number.isFinite(p[0]) && Math.abs(p[0]) <= 90)) {
      return pairs;
    }
    return null;
  }

  /** 점 배열을 TSV 한 줄로 만든다 (앱 자산과 같은 형식) */
  function toTsv(routeId, pts) {
    if (!pts || !pts.length) return '';
    const id = String(routeId || '').replace(/^SEB/, '');
    const la0 = Math.round(pts[0][0] * 1e5), ln0 = Math.round(pts[0][1] * 1e5);
    let la = la0, ln = ln0;
    const d = [];
    for (let i = 1; i < pts.length; i++) {
      const a = Math.round(pts[i][0] * 1e5), n = Math.round(pts[i][1] * 1e5);
      d.push(a - la, n - ln);
      la = a; ln = n;
    }
    return `${id}\t${(la0 / 1e5).toFixed(5)},${(ln0 / 1e5).toFixed(5)}\t${d.join(',')}`;
  }

  return { parseShapeText, toTsv };
});
