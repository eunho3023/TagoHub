// 같은 자리에 여러 시·군 정류장이 겹치는 곳을 미리 계산해 표로 만든다.
// 앱은 이 표를 assets 에서 바로 읽어, 정류장을 열 때 네트워크·DB 조회 없이 타 시·군을 함께 보여 준다.
//
// 실행: node pipeline/build_stop_companions.js  (정류장 좌표나 묶는 규칙이 바뀌면 다시 실행)
// 입력: app/src/main/assets/bus_stops.db (stops 테이블)
// 출력: app/src/main/assets/stop_companions.tsv
//   nodeId \t id|city|no|name|lat|lng;id|city|no|name|lat|lng
const { DatabaseSync } = require('node:sqlite')
const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..')
const DB = path.join(ROOT, 'app/src/main/assets/bus_stops.db')
const OUT = path.join(ROOT, 'app/src/main/assets/stop_companions.tsv')

const RADIUS_M = 30.0          // StopMerge.RADIUS_M 과 같아야 한다
const SAME_NAME_M = 60.0       // StopMerge.SAME_NAME_M 과 같아야 한다 (이름이 같으면 이만큼까지)
const CELL = 0.0008            // 약 70~89m 격자 (경도 방향도 60m 보다 넓게)
const M_PER_DEG_LAT = 111_000.0

const db = new DatabaseSync(DB, { readOnly: true })
const rows = db.prepare('SELECT id, name, lat, lng, no, city FROM stops WHERE lat IS NOT NULL AND lng IS NOT NULL').all()

const key = (la, ln) => `${Math.floor(la / CELL)}:${Math.floor(ln / CELL)}`
const grid = new Map()
rows.forEach((s, i) => {
  const k = key(s.lat, s.lng)
  let a = grid.get(k)
  if (!a) grid.set(k, (a = []))
  a.push(i)
})

function meters(a, b) {
  const dLat = (a.lat - b.lat) * M_PER_DEG_LAT
  const dLng = (a.lng - b.lng) * M_PER_DEG_LAT * Math.cos((a.lat * Math.PI) / 180)
  return Math.hypot(dLat, dLng)
}
const nameKey = (n) => String(n ?? '').replace(/[\s.·,()[\]\-_]/g, '')

// 같은 자리 묶기 (StopMerge.groups 와 같은 규칙): 다른 시군이 30m 안이거나 이름이 같고 60m 안이면 후보,
// 가까운 짝부터 묶고 한 묶음에는 시군마다 하나씩만
const edges = []
rows.forEach((s, i) => {
  const gi = Math.floor(s.lat / CELL)
  const gj = Math.floor(s.lng / CELL)
  for (let di = -1; di <= 1; di++) {
    for (let dj = -1; dj <= 1; dj++) {
      const arr = grid.get(`${gi + di}:${gj + dj}`)
      if (!arr) continue
      for (const j of arr) {
        if (j <= i) continue
        const t = rows[j]
        if ((t.city || '') === (s.city || '')) continue
        const d = meters(s, t)
        const k = nameKey(s.name)
        if (d <= (k && k === nameKey(t.name) ? SAME_NAME_M : RADIUS_M)) edges.push([i, j, d])
      }
    }
  }
})
edges.sort((x, y) => x[2] - y[2] || Math.min(x[0], x[1]) - Math.min(y[0], y[1]) || Math.max(x[0], x[1]) - Math.max(y[0], y[1]))
const parent = rows.map((_, i) => i)
const cities = rows.map((s) => new Set([s.city || '']))
const root = (x) => { while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x] } return x }
for (const [a, b] of edges) {
  const ra = root(a), rb = root(b)
  if (ra === rb || [...cities[ra]].some((c) => cities[rb].has(c))) continue
  const [keep, gone] = ra < rb ? [ra, rb] : [rb, ra]
  parent[gone] = keep
  for (const c of cities[gone]) cities[keep].add(c)
}
const groups = new Map()
rows.forEach((_, i) => { const r = root(i); if (!groups.has(r)) groups.set(r, []); groups.get(r).push(i) })

const clean = (v) => String(v ?? '').replace(/[\t\n\r]/g, ' ').replace(/\|/g, '/').replace(/;/g, ',')
const lines = []
for (const g of groups.values()) {
  if (g.length < 2) continue
  for (const i of g) {
    const comps = g.filter((j) => j !== i).map((j) => rows[j]).map((s) =>
      [s.id, clean(s.city), clean(s.no), clean(s.name), s.lat, s.lng].join('|'),
    )
    lines.push(`${rows[i].id}	${comps.join(';')}`)
  }
}
lines.sort()
fs.writeFileSync(OUT, lines.join('\n') + '\n', 'utf8')
console.log(`stops=${rows.length} groups=${lines.length} -> ${path.relative(ROOT, OUT)}`)
