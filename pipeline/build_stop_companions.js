// 같은 자리에 여러 시·군 정류장이 겹치는 곳을 미리 계산해 표로 만든다.
// 앱은 이 표를 assets 에서 바로 읽어, 정류장을 열 때 네트워크·DB 조회 없이 타 시·군을 함께 보여 준다.
//
// 실행: node pipeline/build_stop_companions.js
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
const CELL = 0.0004            // 약 44m 격자
const M_PER_DEG_LAT = 111_000.0

const db = new DatabaseSync(DB, { readOnly: true })
const rows = db.prepare('SELECT id, name, lat, lng, no, city FROM stops WHERE lat IS NOT NULL AND lng IS NOT NULL').all()

const key = (la, ln) => `${Math.floor(la / CELL)}:${Math.floor(ln / CELL)}`
const grid = new Map()
for (const s of rows) {
  const k = key(s.lat, s.lng)
  let a = grid.get(k)
  if (!a) grid.set(k, (a = []))
  a.push(s)
}

function meters(a, b) {
  const dLat = (a.lat - b.lat) * M_PER_DEG_LAT
  const dLng = (a.lng - b.lng) * M_PER_DEG_LAT * Math.cos((a.lat * Math.PI) / 180)
  return Math.hypot(dLat, dLng)
}

// 각 정류장마다 시·군별로 가장 가까운 하나만 남긴다 (StopMerge.companions 와 같은 규칙)
const best = new Map()
for (const s of rows) {
  const gi = Math.floor(s.lat / CELL)
  const gj = Math.floor(s.lng / CELL)
  for (let di = -1; di <= 1; di++) {
    for (let dj = -1; dj <= 1; dj++) {
      const arr = grid.get(`${gi + di}:${gj + dj}`)
      if (!arr) continue
      for (const t of arr) {
        if (t.id === s.id || (t.city || '') === (s.city || '')) continue
        const d = meters(s, t)
        if (d > RADIUS_M) continue
        let m = best.get(s.id)
        if (!m) best.set(s.id, (m = new Map()))
        const cur = m.get(t.city || '')
        if (!cur || d < cur.d) m.set(t.city || '', { s: t, d })
      }
    }
  }
}

const clean = (v) => String(v ?? '').replace(/[\t\n\r]/g, ' ').replace(/\|/g, '/').replace(/;/g, ',')
const lines = []
for (const [id, m] of best) {
  const comps = [...m.values()].map(({ s }) =>
    [s.id, clean(s.city), clean(s.no), clean(s.name), s.lat, s.lng].join('|'),
  )
  lines.push(`${id}\t${comps.join(';')}`)
}
lines.sort()
fs.writeFileSync(OUT, lines.join('\n') + '\n', 'utf8')
console.log(`stops=${rows.length} groups=${lines.length} -> ${path.relative(ROOT, OUT)}`)
