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
/**
 * 골목 들르기만 없애기 (회차는 남김).
 * 큰길로 오다가 옆 골목 정류장까지 들어갔다 같은 자리(15m 안)로 나와서 **원래 가던 방향으로 계속 가면** 골목 들르기 → 곧게.
 * 노선 모양을 정류장 몇 개씩 끊어 받아 이어 붙인 곳에 생김 (큰길 → 골목 정류장 → 큰길로 되돌아오는 가시, 블록을 한 바퀴 도는 고리).
 * 고리를 돌거나 막다른 길 끝까지 갔다 나온 뒤 **왔던 쪽으로 되돌아가면** 회차 → 크기와 상관없이 그대로 둠.
 * 들어간 길 150m 넘는 것도 그대로 (실제로 마을을 돌아 나오는 노선)
 */
export function despike(line) {
  if (!line || line.length < 3) return line;
  const pts = dropJitter(line);
  const out = [];
  let i = 0;
  while (i < pts.length) {
    if (!out.length || meters(out[out.length - 1], pts[i]) >= 0.5) out.push(pts[i]); // 골목에서 나온 점이 들어간 점과 같으면 한 번만
    let walked = 0, far = 0;
    const cand = [];
    for (let j = i + 1; j < pts.length; j++) {
      walked += meters(pts[j - 1], pts[j]);
      if (walked > 150) break;
      const d = meters(pts[i], pts[j]);
      far = Math.max(far, d);
      if (d <= 15 && far >= d + 10 && walked >= d * 2.5 + 20) cand.push(j);
    }
    const j = cand.reverse().find((k) => goesOn(pts, i, k));
    i = j != null ? j : i + 1;
  }
  return out;
}
/** i 로 들어온 방향(30m 앞에서)과 j 에서 나가는 방향(30m 뒤까지)이 60도 안 = 가던 방향으로 계속 감 (노선 처음·끝이라 모르면 false) */
function goesOn(line, i, j) {
  const before = pointAlong(line, i, 30, -1), after = pointAlong(line, j, 30, 1);
  if (meters(before, line[i]) < 5 || meters(line[j], after) < 5) return false;
  return angleBetween(bearing(before, line[i]), bearing(line[j], after)) <= Math.PI / 3;
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

/** 그리기 전 다듬기 (골목 들르기 없애고 모서리 둥글게) */
export function tidyRoad(line) {
  return line && line.length >= 2 ? roundCorners(despike(line)) : line;
}
