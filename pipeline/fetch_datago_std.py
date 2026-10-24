"""공공데이터포털(data.go.kr) 표준데이터를 통째로 내려받아 CSV(UTF-8)로 저장한다. 로그인·인증키 필요 없음 (포털 화면의 '다운로드' 단추가 쓰는 주소를 그대로 부름).

    python pipeline/fetch_datago_std.py  <publicDataPk>  [저장할 CSV 경로]

예) 전국무인교통단속카메라표준데이터:  python pipeline/fetch_datago_std.py 15028200 pipeline/out/camera.csv
포털 화면(https://www.data.go.kr/data/<pk>/standard.do)에 '표준데이터' 로 나오는 것만 된다. 파일데이터는 fetch_datago_file.py 를 쓴다.
"""
import csv
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://www.data.go.kr"
UA = {"User-Agent": "Mozilla/5.0"}


def get_json(url: str):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def fetch(pk: str, out: Path):
    head = get_json(f"{BASE}/download/columList.json?pk={pk}&ext=CSV")
    cols = head["columList"]
    vo = head["tableVO"]
    total, per = int(head["totalCount"]), 10000
    print(f"{head['fileName']}: {total}건, 항목 {len(cols)}개")
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow([c["columNm"] for c in cols])
        for page in range(1, (total + per - 1) // per + 1):
            q = [("colNmList", vo["colNmList"]) if isinstance(vo["colNmList"], str) else None]
            params = [("colNmList", c) for c in (vo["colNmList"] if isinstance(vo["colNmList"], list) else [vo["colNmList"]])]
            params += [("totalCount", total), ("svcTableNm", vo["svcTableNm"]), ("perPage", per), ("page", page), ("publicDataPk", pk)]
            rows = get_json(f"{BASE}/download/standard.json?" + urllib.parse.urlencode(params))
            for r in rows:
                w.writerow([("" if r.get(c["columCode"]) is None else r[c["columCode"]]) for c in cols])
            print(f"  {min(page * per, total)}/{total}", flush=True)
    print("→", out)


if __name__ == "__main__":
    pk = sys.argv[1]
    fetch(pk, Path(sys.argv[2]) if len(sys.argv) > 2 else Path(f"pipeline/out/std_{pk}.csv"))
