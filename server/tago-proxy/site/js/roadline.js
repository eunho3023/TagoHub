// 노선 선 다듬기 (그리기 전에): 옆 골목에 들어갔다 나오는 가시·고리를 없애고(회차는 남김), 꺾이는 곳을 도로 폭 안에서 둥글게.
// 노선 모양은 정류장 몇 개씩 끊어 받은 도로 길을 이어 붙인 것이라, 조각 끝이 큰길 옆 정류장에 닿는 곳에서
// 큰길 → 정류장 → 다시 큰길로 되돌아오는 가시나, 교차로 안의 작은 고리가 생김.

const meters = (a, b) => Math.hypot((b[0] - a[0]) * 110540, (b[1] - a[1]) * 111320 * Math.cos((a[0] * Math.PI) / 180));
const bearing = (a, b) => Math.atan2((b[1] - a[1]) * Math.cos((a[0] * Math.PI) / 180), b[0] - a[0]);

/** a → b → c 에서 꺾이는 각 (0 = 곧게, π = 완전히 되돌아감) */
function turnAt(a, b, c) {
  let t = Math.abs(bearing(a, b) - bearing(b, c));
  return t > Math.PI ? 2 * Math.PI - t : t;
}
/** 두 방향 사이 각 (0~π) */
function angleBetween(x, y) {
  const t = Math.abs(x - y) % (2 * Math.PI);
  return t > Math.PI ? 2 * Math.PI - t : t;
}
/** line[i] 에서 길을 따라 [dist] m 앞(step 1)·뒤(step -1) 점 (끝까지 가도 모자라면 끝점) */
function pointAlong(line, i, dist, step) {
  let walked = 0;
  for (let k = i; k + step >= 0 && k + step < line.length; k += step) {
    walked += meters(line[k], line[k + step]);
    if (walked >= dist) return line[k + step];
  }
  return line[step > 0 ? line.length - 1 : 0];
}
/** 점 p 에서 선분 a-b 까지 거리(m) */
function toSeg(p, a, b) {
  const kx = 111320 * Math.cos((p[0] * Math.PI) / 180), ky = 110540;
  const ax = (a[1] - p[1]) * kx, ay = (a[0] - p[0]) * ky, bx = (b[1] - p[1]) * kx, by = (b[0] - p[0]) * ky;
  const dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy, t = l ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l)) : 0;
  return Math.hypot(ax + t * dx, ay + t * dy);
}
/** i~j 안에 교차로처럼 한 꼭짓점에서 70도 넘게 꺾이는 곳이 있음 (산길 굽이·회전교차로는 여러 꼭짓점에 걸쳐 완만하게 돎) */
function hasSharpTurn(line, i, j) {
  for (let k = Math.max(1, i); k <= j && k < line.length - 1; k++) {
    if (meters(line[k - 1], line[k]) < 3 || meters(line[k], line[k + 1]) < 3) continue;
    if (turnAt(line[k - 1], line[k], line[k + 1]) >= (70 * Math.PI) / 180) return true;
  }
  return false;
}
/**
 * i~j 에 회전교차로를 도는 길이 있음: 같은 쪽으로 꼭짓점마다 60도 안씩 이어서 돌아 모두 180도 넘게 돎
 * (들어가고 나오는 곳은 크게 꺾여도 됨. 골목 블록은 모서리마다 90도로 꺾여 끊기고, 가시는 끝에서 되돌아감)
 */
function roundabout(line, i, j, minTurn = Math.PI) {
  let run = 0, prev = null;
  for (let k = i; k < j; k++) {
    if (meters(line[k], line[k + 1]) < 1) continue;
    const b = bearing(line[k], line[k + 1]);
    if (prev != null) {
      let t = b - prev;
      while (t > Math.PI) t -= 2 * Math.PI;
      while (t < -Math.PI) t += 2 * Math.PI;
      if (Math.abs(t) > Math.PI / 3 || (run !== 0 && Math.sign(t) !== Math.sign(run) && Math.abs(t) > 0.05)) run = Math.abs(t) > Math.PI / 3 ? 0 : t;
      else run += t;
      if (Math.abs(run) >= minTurn) return true;
    }
    prev = b;
  }
  return false;
}
/** i~j 우회가 큰길 옆에 찍힌 정류장 때문에 생김: 그 길 30m 곁에, 곧게 편 큰길에서 70m 안인 정류장이 있음 */
function strayStop(line, i, j, stops) {
  for (const s of stops) {
    if (toSeg(s, line[i], line[j]) > 70) continue;
    for (let k = i; k < j; k++) if (toSeg(s, line[k], line[k + 1]) <= 30) return true;
  }
  return false;
}
/**
 * 노선 정류장 차례로 보면 i~j 에서 회차함: 길 30m 곁에
 *  - 바로 이어지는 두 정류장이 80m 안에 마주 봄 (예: 기린대로 병무청 → 길 건너 기린대로 병무청: 그 사이에서 돌아옴), 또는
 *  - 앞뒤 정류장이 거의 같은 자리인 회차 정류장 (예: 입석 → 용운입구 → 입석)
 * [stops] 는 노선 차례대로
 */
function turnStop(line, i, j, stops) {
  const near = (s) => { for (let k = i; k < j; k++) if (toSeg(s, line[k], line[k + 1]) <= 30) return true; return false; };
  for (let k = 0; k < stops.length; k++) {
    const s = stops[k], n = stops[k + 1];
    if (n && meters(s, n) <= 80 && near(s) && near(n)) return true;
    const p = stops[k - 1];
    if (p && n) {
      const ab = meters(p, s), bc = meters(s, n);
      if (ab >= 150 && bc >= 150 && meters(p, n) <= 0.3 * Math.min(ab, bc) && near(s)) return true;
    }
  }
  return false;
}
/** 버스가 실제로 큰길에서 들어가 들르는 정류장 이름 (터미널·차고지·종점·회차지) */
const VISIT_RE = /터미널|터미날|차고|종점|회차/;
/** i~j 길 30m 곁에 실제로 들르는 정류장(터미널 등)이 있음 → 곧게 펴지 않음 */
function servesStop(line, i, j, stops) {
  for (const s of stops) {
    if (!VISIT_RE.test(String(s[2] || ""))) continue;
    for (let k = i; k < j; k++) if (toSeg(s, line[k], line[k + 1]) <= 30) return true;
  }
  return false;
}
/**
 * 골목 들르기만 없애기 (회차·실제로 들르는 곳은 남김).
 * 노선 모양을 정류장 몇 개씩 끊어 받아 이어 붙인 곳에서, 큰길 옆에 찍힌 정류장 때문에 선이 옆 골목으로 빠짐:
 *  - 들렀다 같은 자리(25m 안, 반대 차로 포함)로 돌아오는 가시·고리 (길 300m 안)
 *  - 곧은 큰길에서 옆으로 빠졌다(80m 안) 같은 큰길로 같은 방향으로 다시 들어오는 짧은 우회 (길 400m 안, 교차로처럼 꺾이는 곳이 있고,
 *    큰길 70m 안에 찍힌 정류장 곁을 지남 — 정류장 없이 그냥 굽은 길·다리로 돌아가는 길은 그대로)
 * 를 곧게. 남기는 것:
 *  - 회차: 들어온 방향(30m 앞)과 나가는 방향(30m 뒤)이 135도 넘게 반대, 또는 노선 정류장 차례가 그 안에서 되돌아감
 *    (길 건너 같은 정류장에 이어 섬·회차 정류장) → 크기와 상관없이 그 안도 건드리지 않음
 *  - 회전교차로를 도는 길 (한쪽으로 고르게 180도 넘게 돎)
 *  - 실제로 들르는 곳: 이름에 터미널·차고지·종점·회차가 든 정류장 30m 곁을 지나는 길 — [stops] [[위도, 경도, 이름]...] 를 주면
 *    (큰길 옆에 찍힌 보통 정류장 때문에 생긴 골목 들르기는 정류장이 있어도 곧게: 버스는 큰길에서 섬)
 *  - 노선 처음·끝이라 방향을 모르는 곳
 */
export function despike(line, stops = []) {
  if (!line || line.length < 3) return line;
  const pts = dropJitter(line);
  const st = (stops || []).filter((s) => s && s[0] != null && s[1] != null);
  const out = [];
  let i = 0, keepTo = -1;
  while (i < pts.length) {
    if (!out.length || meters(out[out.length - 1], pts[i]) >= 0.5) out.push(pts[i]); // 나온 점이 들어간 점과 같으면 한 번만
    if (i <= keepTo || i === 0) { i++; continue; }
    const before = pointAlong(pts, i, 30, -1);
    const known = meters(before, pts[i]) >= 5;
    const hin = bearing(before, pts[i]);
    let walked = 0, far = 0, cut = -1, turn = -1;
    for (let j = i + 1; j < pts.length && known; j++) {
      walked += meters(pts[j - 1], pts[j]);
      if (walked > 400) break;
      const d = meters(pts[i], pts[j]);
      far = Math.max(far, d);
      const after = pointAlong(pts, j, 30, 1);
      if (meters(pts[j], after) < 5) break; // 노선 끝
      const hout = bearing(pts[j], after);
      if (d <= 25 && far >= d + 10 && walked >= d * 2.5 + 20 && walked <= 300) {
        if (angleBetween(hin, hout) >= (135 * Math.PI) / 180 || roundabout(pts, i, j) || turnStop(pts, i, j, st)) turn = j;
        else if (!servesStop(pts, i, j, st)) cut = j;
      } else if (d >= 40 && walked >= d * 1.3 + 30) {
        const ch = bearing(pts[i], pts[j]);
        if (angleBetween(hin, ch) > 0.44 || angleBetween(hout, ch) > 0.44) continue;
        let dev = 0;
        for (let k = i; k <= j && dev <= 80; k++) dev = Math.max(dev, toSeg(pts[k], pts[i], pts[j]));
        if (dev <= 80 && hasSharpTurn(pts, i, j) && !roundabout(pts, i, j) && strayStop(pts, i, j, st) && !servesStop(pts, i, j, st)) cut = j;
      }
    }
    if (turn >= 0 && turn >= cut) { keepTo = turn; i++; continue; } // 회차는 통째로 남김
    i = cut >= 0 ? cut : i + 1;
  }
  return out;
}
/** 곧은 길 펴기 기준 (앱 Shapes.straighten 과 같음) */
export const STRAIGHT = { maxDeg: 8, maxDevM: 16, jitterM: 3, bumpRatio: 8, maxBumpM: 150, maxGapM: 60, twistDegPerM: 6, roundDeg: 100, bigM: 8, sharpDeg: 25, bigFrac: 0.6, minM: 30, maxM: 300, maxStretch: 1.15 };
/**
 * i~j 사이 점이 i–j 선분에서 떨림(3m) 안이거나, 그보다 벗어난 곳이 짧고 들쭉날쭉한 혹: 16m 안으로 벗어나고,
 * 벗어난 구간(i→j 방향으로 잰 길이)이 가장 많이 벗어난 거리의 8배·150m 안, 꼭짓점 사이가 모두 60m 안(촘촘한 도시 길),
 * 꼭짓점마다 꺾인 각을 모두 더한 것이 벗어난 거리 1m 에 6도 넘음(갔다 돌아오며 여러 번 꺾임).
 * 산길·시골길의 진짜 굽이(S자·C자, 들어온·나간 방향이 같아도)는 완만하게 돌아서(1m 에 3~6도) 펴지 않음
 * (2026-11 전주 104 도시 떨림 1m 에 6~16도, 임실 201·202 호숫가·산길 굽이 2.6~5.8도)
 * 8m 넘게 벗어나면 더: 한 꼭짓점에서 25도 넘게 확 꺾이고(차로 바꾸기·교차로), 벗어난 구간이 i–j 의 60% 안
 * (전주 공식 노선 그림의 천변길·산길 굽이는 완만하게 꺾이거나 구간 거의 전체가 벗어남: 천변 23도, 상관 13도, 산길 82%)
 */
function bumpOnly(line, i, j, o) {
  const a = line[i], b = line[j];
  const kx = 111320 * Math.cos((a[0] * Math.PI) / 180), ky = 110540;
  const ux = (b[1] - a[1]) * kx, uy = (b[0] - a[0]) * ky, L = Math.hypot(ux, uy) || 1;
  let dev = 0, lo = Infinity, hi = -Infinity, gap = 0, twist = 0, sharp = 0, prev = bearing(a, b), first = true;
  for (let k = i; k < j; k++) {
    gap = Math.max(gap, meters(line[k], line[k + 1]));
    if (meters(line[k], line[k + 1]) < 2) continue;
    const h = bearing(line[k], line[k + 1]), t = angleBetween(prev, h);
    twist += t; if (!first) sharp = Math.max(sharp, t);
    prev = h; first = false;
  }
  for (let k = i + 1; k < j; k++) {
    const d = toSeg(line[k], a, b);
    if (d > o.maxDevM) return false;
    dev = Math.max(dev, d);
    if (d > o.jitterM) {
      const t = ((line[k][1] - a[1]) * kx * ux + (line[k][0] - a[0]) * ky * uy) / L;
      lo = Math.min(lo, t); hi = Math.max(hi, t);
    }
  }
  if (dev <= o.jitterM) return true;
  if (gap > o.maxGapM || hi - lo > Math.min(o.maxBumpM, dev * o.bumpRatio) || (twist * 180) / Math.PI < dev * o.twistDegPerM) return false;
  return dev <= o.bigM || ((sharp * 180) / Math.PI >= o.sharpDeg && hi - lo <= L * o.bigFrac);
}
/**
 * 곧은 길 펴기: 곧은 큰길에서 옆으로 잠깐(25m 안) 빠졌다 같은 방향으로 돌아오는 꺾임·떨림을 곧게
 * (카카오·OSRM 길이 큰길 옆 정류장이나 차로 바꾸기 때문에 3~20m 틀어졌다 돌아오는 것. 전주 104 거마평로 풍남중 앞 등).
 * i → j 를 곧게 잇는 조건: 들어온 방향(30m 앞)·나가는 방향(30m 뒤)·i→j 방향이 모두 8도 안, 곧은 거리 30~300m,
 * 사이 점이 모두 i–j 선분에서 25m 안, 길을 따라 잰 거리가 곧은 거리의 1.15배 안 (되돌아가는 고리·회차 아님).
 * 그대로 두는 것: 굽은 길·교차로(들어온·나간 방향이 다름), 회전교차로(같은 쪽으로 이어서 100도 넘게 돎: 반 바퀴만 돌고 나가는 작은 회전교차로도, 도아삼거리), 이름에 터미널·차고지·종점·회차가 든 정류장 곁, 노선 처음·끝
 */
export function straighten(line, stops = [], o = STRAIGHT) {
  if (!line || line.length < 3) return line;
  const st = (stops || []).filter((s) => s && s[0] != null && s[1] != null);
  const maxA = (o.maxDeg * Math.PI) / 180;
  const out = [line[0]];
  let i = 0;
  while (i < line.length - 1) {
    let best = -1;
    const before = pointAlong(line, i, 30, -1);
    if (i > 0 && meters(before, line[i]) >= 20) {
      const hin = bearing(before, line[i]);
      let walked = 0;
      for (let j = i + 1; j < line.length; j++) {
        walked += meters(line[j - 1], line[j]);
        if (walked > o.maxM * o.maxStretch) break;
        const d = meters(line[i], line[j]);
        if (j < i + 2 || d < o.minM || d > o.maxM || walked > d * o.maxStretch) continue;
        const after = pointAlong(line, j, 30, 1);
        if (meters(line[j], after) < 20) break; // 노선 끝
        const ch = bearing(line[i], line[j]);
        if (angleBetween(hin, ch) > maxA || angleBetween(bearing(line[j], after), ch) > maxA) continue;
        if (bumpOnly(line, i, j, o) && !roundabout(line, i, j, (o.roundDeg * Math.PI) / 180) && !servesStop(line, i, j, st)) best = j;
      }
    }
    if (best > 0) { out.push(line[best]); i = best; } else { i++; out.push(line[i]); }
  }
  return out;
}

/** 같은 점(0.5m 안) 겹침과, 5m 안에서 앞뒤로 떨리는 점(150도 넘게 되돌아감)을 뺌 (한 번만, 이어서 빼지 않음) */
function dropJitter(line) {
  const pts = [];
  for (const q of line) if (!pts.length || meters(pts[pts.length - 1], q) >= 0.5) pts.push(q);
  if (pts.length < 3) return pts;
  const out = [pts[0]];
  for (let i = 1; i < pts.length - 1; i++) {
    const a = out[out.length - 1], b = pts[i], c = pts[i + 1];
    if (turnAt(a, b, c) > (150 * Math.PI) / 180 && Math.min(meters(a, b), meters(b, c)) <= 5) continue;
    out.push(b);
  }
  out.push(pts[pts.length - 1]);
  return out;
}

/**
 * 꺾이는 곳을 둥글게: 10도 넘게 꺾이는 꼭짓점을 양쪽으로 r m(최대 [maxR], 양쪽 조각의 0.45 배까지) 떨어진 두 점 사이 곡선(2차 베지에)으로.
 * 곡선은 모서리 안쪽 r 안에만 있어 도로를 벗어나지 않음. 이미 촘촘한 곡선(조각이 짧음)은 그대로
 */
export function roundCorners(line, maxR = 15) {
  if (!line || line.length < 3) return line;
  const out = [line[0]];
  for (let i = 1; i < line.length - 1; i++) {
    const a = line[i - 1], b = line[i], c = line[i + 1];
    const ab = meters(a, b), bc = meters(b, c);
    let turn = Math.abs(bearing(a, b) - bearing(b, c));
    if (turn > Math.PI) turn = 2 * Math.PI - turn;
    const r = Math.min(maxR, ab * 0.45, bc * 0.45);
    if (turn < (10 * Math.PI) / 180 || r < 1.5) { out.push(b); continue; }
    const p1 = [b[0] + ((a[0] - b[0]) * r) / ab, b[1] + ((a[1] - b[1]) * r) / ab];
    const p2 = [b[0] + ((c[0] - b[0]) * r) / bc, b[1] + ((c[1] - b[1]) * r) / bc];
    for (let k = 0; k <= 4; k++) {
      const t = k / 4, u = 1 - t;
      out.push([u * u * p1[0] + 2 * u * t * b[0] + t * t * p2[0], u * u * p1[1] + 2 * u * t * b[1] + t * t * p2[1]]);
    }
  }
  out.push(line[line.length - 1]);
  return out;
}

/**
 * 정류장 사이 길 고치기: 이어지는 두 정류장(id) 사이의 실제 길. 노선 자료·길찾기가 모르는 회차 길 등.
 * 그 두 정류장을 차례로 지나는 모든 노선에 씀 (앱 data/RoadShape.kt SegFixes 와 같은 표)
 * cut: 정류장에 가장 가까운 점(골목으로 빠지는 첫 점 등)도 버리고 고친 길로 바로 이음 (노선 자료가 정류장 뒤 블록을 돌아 나오는 곳)
 */
export const SEG_FIXES = [
  {
    // 대전 211 이마트 → 갤러리아타임월드 (대덕대로 남쪽): 노선 자료가 이마트 뒤 블록(서쪽 80m)을 돌아 나와서, 다듬은 뒤에도
    // 선이 인도 쪽으로 200m 비껴 내려갔음 → 대덕대로 그대로
    from: "DJB8002468", to: "DJB8001078", cut: true,
    path: [[36.35495, 127.37942], [36.35359, 127.37941], [36.3532, 127.37939], [36.35292, 127.37933]],
  },
  {
    // 대전 211 대전산업정보고 → 래미안아파트2단지 (도산로 남서쪽): 노선 자료가 학교 앞 블록을 돌아 나와서 길 가운데 작은 혹이 남았음 → 도산로 그대로
    from: "DJB8001951", to: "DJB8001240", cut: true,
    path: [[36.33456, 127.38839]],
  },
  {
    // 전주 기린대로 병무청(3050104 → 길 건너 3050070): 임실·진안 버스는 기린대로로 올라가 이름 없는 큰 블록(전주고 서쪽)을 돌아 회차 (네이버 지도 노선과 같음)
    from: "TSB305000104", to: "TSB305000070",
    path: [
      [35.821319, 127.152197],
      [35.821797, 127.151635],
      [35.822193, 127.15113],
      [35.82233, 127.150958],
      [35.822449, 127.15083],
      [35.822529, 127.150739],
      [35.82257, 127.150693],
      [35.822663, 127.150589],
      [35.822737, 127.150495],
      [35.822784, 127.150436],
      [35.822841, 127.150374],
      [35.822958, 127.150242],
      [35.82316, 127.150012],
      [35.823265, 127.149886],
      [35.823849, 127.149296],
      [35.823902, 127.149279],
      [35.823918, 127.149278],
      [35.823943, 127.149275],
      [35.823964, 127.149275],
      [35.824004, 127.149282],
      [35.824197, 127.149542],
      [35.824247, 127.14961],
      [35.824377, 127.149785],
      [35.824438, 127.149867],
      [35.824494, 127.14992],
      [35.82462, 127.150039],
      [35.82471, 127.150124],
      [35.824744, 127.150599],
      [35.823897, 127.150686],
      [35.823803, 127.150695],
      [35.823739, 127.150601],
      [35.823496, 127.150843],
      [35.82332, 127.151035],
      [35.823286, 127.151072],
      [35.823208, 127.151174],
      [35.823109, 127.151089],
      [35.823083, 127.151059],
      [35.822837, 127.150775],
      [35.822806, 127.150746],
      [35.82277, 127.150713],
      [35.822729, 127.150675],
      [35.822663, 127.150589],
      [35.822493, 127.150585],
      [35.822267, 127.150861],
      [35.822091, 127.151058],
      [35.82139, 127.151889],
      [35.821092, 127.152248],
      [35.821012, 127.152345],
    ],
  },
];
/** line 에서 점 p 에 가장 가까운 꼭짓점 (from 부터 길 [maxM] m 안) {i, d} */
function nearestIdx(line, p, from, maxM) {
  let best = null, walked = 0;
  for (let i = from; i < line.length; i++) {
    if (i > from) walked += meters(line[i - 1], line[i]);
    if (walked > maxM) break;
    const d = meters(line[i], p);
    if (!best || d < best.d) best = { i, d };
  }
  return best;
}
/** 정류장 사이 길 고치기를 노선 선에 끼움. [stops] 는 노선 차례대로 [[위도, 경도, 이름, id]...] */
export function applySegFixes(line, stops = [], fixes = SEG_FIXES) {
  if (!line || line.length < 2 || !stops || stops.length < 2) return line;
  let out = line, cursor = 0;
  for (let k = 0; k < stops.length - 1; k++) {
    const a = stops[k], b = stops[k + 1];
    const fix = fixes.find((f) => f.from === a[3] && f.to === b[3]);
    if (!fix) continue;
    const ha = nearestIdx(out, a, cursor, 1e9);
    if (!ha || ha.d > 150) continue;
    const hb = nearestIdx(out, b, ha.i, 3000);
    if (!hb || hb.d > 150) continue;
    const c = fix.cut ? 1 : 0;
    out = [...out.slice(0, ha.i + 1 - c), ...fix.path, ...out.slice(hb.i + c)];
    cursor = ha.i - c + fix.path.length;
  }
  return out;
}

/** 그리기 전 다듬기 (정류장 사이 길 고치기를 끼우고, 골목 들르기 없애고, 곧은 길 펴고, 모서리 둥글게). [stops]: 노선 정류장 [[위도, 경도, 이름, id]...] */
export function tidyRoad(line, stops = []) {
  return line && line.length >= 2 ? roundCorners(straighten(despike(applySegFixes(line, stops), stops), stops)) : line;
}
