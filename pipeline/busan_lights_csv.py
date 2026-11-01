#!/usr/bin/env python3
"""
부산광역시 신호등 현황(SHP) 파일(공공데이터포털 15084253, 교통신호제어기 약 2,400곳 = 교차로마다 하나)에서 위치만 뽑아
build_traffic_lights.py 가 읽는 CSV(열: 위도,경도,잔여시간표시기유무)로 저장한다.

  python pipeline/busan_lights_csv.py <부산광역시_신호등_현황(SHP).zip> <출력.csv>

  - 파일은 공공데이터포털에서 로그인 없이 내려받을 수 있음(이용허락범위 제한 없음). zip 안의 .dbf 표에 위도(lat)·경도(lng)가 WGS84 로 들어 있음.
  - 잔여시간표시기 정보는 없어서 모두 N.
  - 표준 라이브러리만 씀(.dbf 를 직접 읽음). zip 안 파일은 이름이 아니라 확장자만 보고 읽어서 경로 문제가 없음.
"""
import csv
import struct
import sys
import zipfile


def dbf_rows(data: bytes):
    """dBase(.dbf) 바이트 → [{열 이름: 값}] (글자는 cp949)"""
    n, header_len, rec_len = struct.unpack("<IHH", data[4:12])
    fields, pos = [], 32
    while data[pos] != 0x0D:
        name = data[pos:pos + 11].split(b"\x00")[0].decode("ascii", "replace")
        fields.append((name, data[pos + 16]))
        pos += 32
    rows = []
    for r in range(n):
        raw = data[header_len + r * rec_len: header_len + (r + 1) * rec_len]
        if not raw or raw[0:1] == b"*":      # 지워진 줄
            continue
        off, row = 1, {}
        for name, ln in fields:
            row[name] = raw[off:off + ln].replace(b"\x00", b"").decode("cp949", "replace").strip()
            off += ln
        rows.append(row)
    return rows


def points(rows):
    """한국 범위 안의 (위도, 경도)만"""
    out = []
    for r in rows:
        try:
            lat, lng = float(r.get("lat", "")), float(r.get("lng", ""))
        except ValueError:
            continue
        if 33.0 <= lat <= 39.5 and 124.0 <= lng <= 132.0:
            out.append((lat, lng))
    return out


def main(argv) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    with zipfile.ZipFile(argv[1]) as z:
        member = next((i for i in z.infolist() if i.filename.lower().endswith(".dbf")), None)
        if member is None:
            raise SystemExit("zip 안에 .dbf 가 없어요")
        rows = dbf_rows(z.read(member))
    pts = points(rows)
    with open(argv[2], "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["위도", "경도", "잔여시간표시기유무"])
        w.writerows((f"{la:.6f}", f"{ln:.6f}", "N") for la, ln in pts)
    print(f"부산 신호제어기 {len(pts):,}곳 (표 {len(rows):,}줄) → {argv[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
