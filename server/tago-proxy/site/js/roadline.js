// 노선 선 다듬기 (그리기 전에): 짧게 들어갔다 되돌아 나오는 가시·작은 고리를 없애고, 꺾이는 곳을 도로 폭 안에서 둥글게.
// 노선 모양은 정류장 몇 개씩 끊어 받은 도로 길을 이어 붙인 것이라, 조각 끝이 큰길 옆 정류장에 닿는 곳에서
// 큰길 → 정류장 → 다시 큰길로 되돌아오는 가시나, 교차로 안의 작은 고리가 생김.

const meters = (a, b) => Math.hypot((b[0] - a[0]) * 110540, (b[1] - a[1]) * 111320 * Math.cos((a[0] * Math.PI) / 180));
const bearing = (a, b) => Math.atan2((b[1] - a[1]) * Math.cos((a[0] * Math.PI) / 180), b[0] - a[0]);

/** a → b → c 에서 꺾이는 각 (0 = 곧게, π = 완전히 되돌아감) */
function turnAt(a, b, c) {
  let t = Math.abs(bearing(a, b) - bearing(b, c));
  return t > Math.PI ? 2 * Math.PI - t : t;
}
/**
 * 가시·작은 고리 없애기 (작은 고리 먼저: 가시를 먼저 빼면 고리가 같은 점으로 돌아오는 것이 안 보임).
 *  1) 가시: 거의 되돌아가는(150도 넘게 꺾이는) 꼭짓점을, 양쪽 조각 중 하나가 [maxLeg] m 안이면 뺌.
 *     빼고 나서 또 되돌아가는 곳이 생기면 이어서 뺌 (큰길 → 골목 정류장 → 큰길로 되돌아온 가시가 통째로 빠짐)
 *  2) 작은 고리: 길을 따라 60m 안에 12m 곁으로 되돌아오는 꼬임(교차로 안), 또는 150m 안에 같은 점(2m)으로 되돌아오는 고리
 *     (조각 끝이 큰길 옆 골목 정류장까지 블록을 돌아 들어갔다 같은 자리로 나온 것)
 * 마을을 한 바퀴 돌아 나오는 회차 고리(실제 노선 250m 안팎)·회전교차로는 남음
 */
export function despike(line, maxLeg = 80) {
  if (!line || line.length < 3) return line;
  const out = [];
  for (const q of cutSmallLoops(line)) {
    const l = out[out.length - 1];
    if (l && meters(l, q) < 0.5) continue;
    out.push(q);
    while (out.length >= 3) {
      const a = out[out.length - 3], b = out[out.length - 2], c = out[out.length - 1];
      if (turnAt(a, b, c) > (150 * Math.PI) / 180 && Math.min(meters(a, b), meters(b, c)) <= maxLeg) {
        out.splice(out.length - 2, 1);
        if (meters(out[out.length - 2], out[out.length - 1]) < 0.5) out.pop(); // 가시 앞뒤가 같은 점
      } else break;
    }
  }
  return out;
}
/** 길을 따라 60m 안에 시작점 12m 곁으로, 또는 150m 안에 시작점 2m 곁으로 되돌아오는 작은 고리를 곧게 */
function cutSmallLoops(line) {
  const out = [];
  let i = 0;
  while (i < line.length) {
    out.push(line[i]);
    let walked = 0, far = 0, best = -1;
    for (let j = i + 1; j < line.length; j++) {
      walked += meters(line[j - 1], line[j]);
      if (walked > 150) break;
      const d = meters(line[i], line[j]);
      far = Math.max(far, d);
      if (far < d + 10 || walked < d * 2.5 + 20) continue;
      if ((walked <= 60 && d <= 12) || d <= 2) best = j;
    }
    i = best > 0 ? best : i + 1;
  }
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

/** 그리기 전 다듬기 (가시·고리 없애고 모서리 둥글게) */
export function tidyRoad(line) {
  return line && line.length >= 2 ? roundCorners(despike(line)) : line;
}
