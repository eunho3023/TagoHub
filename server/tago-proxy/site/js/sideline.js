// 노선·소통 선을 오른쪽 통행에 맞춰 비켜 그리기 (화면 없음: bus.js·topis.js 가 쓰고, 테스트도 불러옴). 앱 data/PathMath.kt 와 같은 규칙
/**
 * 오른쪽으로 비키는 실제 거리 한도 (m). 화면 4.5px 를 그대로 쓰면 확대했을 때 몇~십몇 m 가 되어
 * 좁은 골목을 도는 노선이 지도 도로 밖으로 나갔음 (2026-11-21). 2차로 도로의 한 차로 가운데쯤
 */
export const MAX_SIDE_M = 3;
/** 반대 방향으로 지나는 같은 길을 찾는 거리 (m) */
const TWO_WAY_M = 12;
/**
 * 꼭짓점마다 "이 자리를 반대 방향으로도 지나는지" (같은 길을 오가는 구간만 오른쪽으로 비켜 두 줄로, 나머지는 도로 한가운데).
 * [others]: 같은 노선의 다른 선(상·하행을 따로 그릴 때). 자기 선에서는 앞뒤 3구간 안은 보지 않음
 */
export function twoWayMask(line, others = []) {
  const n = line.length, out = new Array(n).fill(false);
  if (n < 2) return out;
  const lat0 = line[0][0], kx = 111320 * Math.cos((lat0 * Math.PI) / 180), ky = 110540, lng0 = line[0][1];
  const xy = (p) => [(p[1] - lng0) * kx, (p[0] - lat0) * ky];
  const cell = TWO_WAY_M, grid = new Map();
  const segs = [];
  const addLine = (l, own) => {
    const P = l.map(xy);
    for (let i = 0; i < P.length - 1; i++) {
      const [ax, ay] = P[i], [bx, by] = P[i + 1], len = Math.hypot(bx - ax, by - ay);
      if (len < 0.5) continue;
      const k = segs.length;
      segs.push({ ax, ay, bx, by, ux: (bx - ax) / len, uy: (by - ay) / len, i: own ? i : -1e9 });
      const x0 = Math.floor(Math.min(ax, bx) / cell), x1 = Math.floor(Math.max(ax, bx) / cell), y0 = Math.floor(Math.min(ay, by) / cell), y1 = Math.floor(Math.max(ay, by) / cell);
      for (let gx = x0; gx <= x1; gx++) for (let gy = y0; gy <= y1; gy++) { const g = gx + ":" + gy; if (!grid.has(g)) grid.set(g, []); grid.get(g).push(k); }
    }
    return P;
  };
  const P = addLine(line, true);
  for (const o of others) if (o && o.length >= 2) addLine(o, false);
  for (let i = 0; i < n; i++) {
    const a = Math.min(i, n - 2), dx = P[a + 1][0] - P[a][0], dy = P[a + 1][1] - P[a][1], dl = Math.hypot(dx, dy);
    if (dl < 1e-6) continue;
    const ux = dx / dl, uy = dy / dl, [px, py] = P[i];
    const gx = Math.floor(px / cell), gy = Math.floor(py / cell);
    search: for (let x = gx - 1; x <= gx + 1; x++) for (let y = gy - 1; y <= gy + 1; y++) {
      for (const k of grid.get(x + ":" + y) || []) {
        const sg = segs[k];
        if (Math.abs(sg.i - i) <= 3) continue;
        if (ux * sg.ux + uy * sg.uy > -0.7) continue;   // 반대 방향(135도 넘게)만
        const vx = sg.bx - sg.ax, vy = sg.by - sg.ay, t = Math.max(0, Math.min(1, ((px - sg.ax) * vx + (py - sg.ay) * vy) / (vx * vx + vy * vy)));
        if (Math.hypot(px - sg.ax - t * vx, py - sg.ay - t * vy) <= TWO_WAY_M) { out[i] = true; break search; }
      }
    }
  }
  return out;
}
/** 점 p 를 방향 deg(북쪽 0도, 시계 방향)의 오른쪽으로 m 미터 */
export function shiftRight(p, deg, m) {
  const a = ((deg + 90) * Math.PI) / 180;
  return [p[0] + (m * Math.cos(a)) / 110540, p[1] + (m * Math.sin(a)) / (111320 * Math.cos((p[0] * Math.PI) / 180))];
}
/**
 * 선을 진행 방향 오른쪽으로 m 미터 평행 이동 (폰 앱 core/OffsetLine 과 같은 방법). 꼭짓점마다 다르게 하려면 m 에 배열.
 * 꺾이는 점은 양쪽 조각 법선의 평균 방향으로 옮기고, 아주 뾰족한 곳은 2m 까지로 제한
 */
export function offsetRight(line, m) {
  const n = line.length;
  if (n < 2 || !m || (Array.isArray(m) && !m.some((v) => v))) return line;
  const mAt = Array.isArray(m) ? (i) => m[i] || 0 : () => m;
  const lat0 = line[0][0], kx = 111320 * Math.cos((lat0 * Math.PI) / 180), ky = 110540;
  // 평면 (x 동쪽, y 북쪽). 진행 방향 (dx, dy) 의 오른쪽 법선은 (dy, -dx)
  const xs = line.map((p) => (p[1] - line[0][1]) * kx), ys = line.map((p) => (p[0] - lat0) * ky);
  const nx = [], ny = [];
  let lx = 0, ly = 0;
  for (let i = 0; i < n - 1; i++) {
    const dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i], len = Math.hypot(dx, dy);
    if (len > 1e-3) { lx = dy / len; ly = -dx / len; }
    nx.push(lx); ny.push(ly);
  }
  const first = nx.findIndex((v, i) => v !== 0 || ny[i] !== 0);
  if (first < 0) return line;
  for (let i = 0; i < first; i++) { nx[i] = nx[first]; ny[i] = ny[first]; }
  return line.map((p, i) => {
    const a = i === 0 ? 0 : i - 1, b = i === n - 1 ? n - 2 : i;
    let mx = nx[a] + nx[b], my = ny[a] + ny[b];
    const ml = Math.hypot(mx, my);
    if (ml < 1e-3) { mx = nx[b]; my = ny[b]; } else { mx /= ml; my /= ml; }
    const cos = Math.max(0.5, mx * nx[b] + my * ny[b]);
    const d = mAt(i);
    return [lat0 + (ys[i] + (my * d) / cos) / ky, line[0][1] + (xs[i] + (mx * d) / cos) / kx];
  });
}
