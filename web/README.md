# TagoHub 웹 버전 (PC용)

안드로이드 앱의 **조회 기능**을 PC 브라우저에서 쓰는 버전입니다. 작은 서버(`server.py`)가 키를 붙여 공공 API·카카오를 대신 부르고,
브라우저 화면(`static/`)은 그 서버만 부릅니다. 그래서 키가 브라우저에 보이지 않고, 브라우저가 막는 호출(CORS) 문제도 없습니다.

## 쓰는 법

**exe로 (파이썬 없이)**
1. `TagoHubWeb.exe` 와 같은 폴더에 `local.properties` 를 둡니다 (프로젝트 폴더의 것을 복사).
2. exe를 더블클릭 → 검은 창이 뜨고 브라우저가 `http://localhost:8000` 으로 열립니다. 끄려면 검은 창을 닫으세요.
3. 첫 실행 때 정류장 데이터(약 120MB)를 GitHub 에서 받습니다 (몇십 초, 인터넷 필요). 이후에는 `data/` 폴더의 것을 씁니다.

**파이썬으로**
```bash
python web/server.py                 # 브라우저가 자동으로 열림
python web/server.py --port 9000     # 포트 바꾸기
python web/server.py --host 0.0.0.0  # 같은 Wi-Fi 의 폰에서도 http://PC주소:8000 으로 열기 (방화벽 허용 필요)
```
표준 라이브러리만 씁니다 (설치할 것 없음). 키는 저장소 루트(또는 exe 옆)의 `local.properties` 또는 환경변수에서 읽습니다.

| 키 | 쓰임 |
|---|---|
| `TAGO_SERVICE_KEY` | 버스 정류장·노선·도착, 기차·항공 시간표 |
| `KAKAO_REST_KEY` | 장소 검색, 대중교통·자동차 길찾기 |
| `SEOUL_SUBWAY_KEY` | 서울·수도권 지하철 실시간 도착 |

## 되는 것 / 안 되는 것

- 되는 것: 장소·정류장·역 검색, 내 주변 정류장, 정류장 실시간 도착(국토교통부 서비스 지역), 노선 타임라인과 버스 위치,
  지하철 실시간 도착(서울·수도권), 기차·항공 시간표, 대중교통·자동차 길찾기 미리보기(경로 옵션 포함), 즐겨찾기, 라이트·다크·시스템 모드
- 아직 없는 것: 고속·시외버스, 서울·경기·부산 등 지역 전용 버스 API, 지하철 시간표·출구, 집/회사
- 일부러 뺀 것(폰에서만 의미 있음): GPS 경로 안내, 하차·막차·출근 알림, 홈 화면 위젯, 음성 안내
- 지도는 OpenStreetMap(Leaflet)입니다. 카카오맵으로 바꾸려면 카카오 개발자 사이트에서 JavaScript 키를 받고
  JavaScript SDK 도메인에 `http://localhost:8000` 을 등록해야 합니다 (아직 코드에 연결하지 않음).

## exe 만들기

```bash
pip install pyinstaller
pyinstaller --noconfirm --onefile --name TagoHubWeb \
  --add-data "web/static;static" --add-data "app/src/main/assets/subway_stations.tsv;data" web/server.py
```
`dist/TagoHubWeb.exe` 가 생깁니다. 키(`local.properties`)는 exe에 넣지 않습니다 — 넣으면 exe를 받은 사람이 꺼낼 수 있습니다.

## 시험

```bash
python web/test_server.py     # 서버의 순수한 부분 (인터넷·키 없이)
```
