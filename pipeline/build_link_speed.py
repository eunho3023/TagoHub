"""국토교통부 표준 노드·링크(구간단위)에서 도로 제한속도·차로 수를 뽑아 앱에 넣을 파일을 만든다.

    python pipeline/build_link_speed.py  [NODELINKDATA 압축을 푼 폴더]      # 기본: ~/Downloads/NODELINKDATA

필요: pip install pyproj numpy
읽는 파일: MOCT_LINK.dbf / .shp / .shx  (좌표계: 중부원점 TM, ITRF2000)
만드는 파일: app/src/main/assets/road_links.bin

도로 등급 101~106 (고속국도·도시고속·일반국도·특별/광역시도·국가지원지방도·지방도)만 담는다. 시군도(107)는 너무 많아 뺐다.
링크를 8m 오차로 단순화해 500m 이하 선분으로 나누고, 선분마다 한 줄로 저장한다. 격자(0.01도) 순으로 정렬해 앱이 이진 탐색으로 찾는다.

파일 형식 (빅엔디언):
  int 0x4C4E4B31('LNK1'), int 선분 수
  선분 × n: int 위도1, int 경도1 (1e-5도), short 위도변화, short 경도변화 (끝점-시작점, 1e-5도), u8 제한속도(km/h, 0=모름), u8 차로 수, u8 도로등급(1~6)
"""
import struct
import sys
from pathlib import Path

import numpy as np
from pyproj import Transformer

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/road_links.bin"
RANKS = {b"101": 1, b"102": 2, b"103": 3, b"104": 4, b"105": 5, b"106": 6}
TOL_M = 8.0
MAX_SEG_M = 500.0
M_PER_DEG_LAT = 111_000.0


def dbf_columns(f):
    f.seek(0)
    h = f.read(32)
    n = struct.unpack("<I", h[4:8])[0]
    hl, rl = struct.unpack("<HH", h[8:12])
    cols, off = {}, 1
    while True:
        d = f.read(32)
        if d[0] == 0x0D:
            break
        name, ln = d[:11].split(b"\0")[0].decode(), d[16]
        cols[name] = (off, ln)
        off += ln
    return n, hl, rl, cols


def dp(pts, tol):
    """Douglas-Peucker (pts: Nx2 미터 좌표). 남길 점의 번호"""
    keep = {0, len(pts) - 1}
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        p, q = pts[a], pts[b]
        d = q - p
        L = np.hypot(*d)
        seg = pts[a + 1:b]
        dist = np.hypot(*(seg - p).T) if L == 0 else np.abs(d[0] * (seg[:, 1] - p[1]) - d[1] * (seg[:, 0] - p[0])) / L
        i = int(np.argmax(dist))
        if dist[i] > tol:
            m = a + 1 + i
            keep.add(m)
            stack += [(a, m), (m, b)]
    return sorted(keep)


def main():
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads" / "NODELINKDATA"
    tf = Transformer.from_crs(
        "+proj=tmerc +lat_0=38 +lon_0=127 +k=1 +x_0=200000 +y_0=600000 +ellps=GRS80 +units=m +no_defs", "EPSG:4326", always_xy=True)
    dbf, shp, shx = (open(folder / f"MOCT_LINK.{e}", "rb") for e in ("dbf", "shp", "shx"))
    n, hl, rl, cols = dbf_columns(dbf)
    dbf.seek(hl)
    sel = []  # (레코드 번호, 속도, 차로, 등급)
    so, sl = cols["MAX_SPD"]
    lo, ll = cols["LANES"]
    ro, rll = cols["ROAD_RANK"]
    for i in range(n):
        r = dbf.read(rl)
        rank = RANKS.get(r[ro:ro + rll].strip())
        if rank is None:
            continue
        try:
            spd = int(r[so:so + sl].strip() or 0)
            lanes = int(r[lo:lo + ll].strip() or 0)
        except ValueError:
            spd = lanes = 0
        sel.append((i, min(spd, 255), min(lanes, 255), rank))
    print("대상 링크", len(sel), "/", n)

    shx.seek(100)
    idx = np.frombuffer(shx.read(8 * n), dtype=">i4").reshape(n, 2)  # (오프셋/2, 길이/2)
    recs = []
    for k, (i, spd, lanes, rank) in enumerate(sel):
        off = int(idx[i][0]) * 2
        shp.seek(off + 8)
        body = shp.read(int(idx[i][1]) * 2)
        if struct.unpack("<i", body[:4])[0] not in (3, 13, 23):
            continue
        parts, npts = struct.unpack("<ii", body[36:44])
        xy = np.frombuffer(body, dtype="<f8", count=npts * 2, offset=44 + 4 * parts).reshape(npts, 2)
        if npts < 2:
            continue
        lon, lat = tf.transform(xy[:, 0], xy[:, 1])
        lat, lon = np.asarray(lat), np.asarray(lon)
        m = np.column_stack([(lon - lon[0]) * 88_000.0, (lat - lat[0]) * M_PER_DEG_LAT])
        keep = dp(m, TOL_M)
        for a, b in zip(keep, keep[1:]):
            length = float(np.hypot(*(m[b] - m[a])))
            pieces = max(1, int(np.ceil(length / MAX_SEG_M)))
            for p in range(pieces):
                t0, t1 = p / pieces, (p + 1) / pieces
                la0, ln0 = lat[a] + (lat[b] - lat[a]) * t0, lon[a] + (lon[b] - lon[a]) * t0
                la1, ln1 = lat[a] + (lat[b] - lat[a]) * t1, lon[a] + (lon[b] - lon[a]) * t1
                recs.append((round(la0 * 1e5), round(ln0 * 1e5), round(la1 * 1e5), round(ln1 * 1e5), spd, lanes, rank))
        if k % 100_000 == 0:
            print(k, flush=True)
    # 격자(0.01도) 순: 앱이 이진 탐색으로 찾음 (시작점 기준)
    recs.sort(key=lambda r: (r[0] // 1000, r[1] // 1000))
    with open(OUT, "wb") as f:
        f.write(struct.pack(">ii", 0x4C4E4B31, len(recs)))
        for r in recs:
            f.write(struct.pack(">iihhBBB", r[0], r[1], r[2] - r[0], r[3] - r[1], r[4], r[5], r[6]))
    print(f"선분 {len(recs)}개 → {OUT} ({OUT.stat().st_size / 1e6:.1f}MB)")


if __name__ == "__main__":
    main()
