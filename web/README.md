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
| `KAKAO_JS_KEY` | (선택) 카카오맵으로 지도를 그림. 없으면 OpenStreetMap |

## 되는 것 / 안 되는 것

- 되는 것: 장소·정류장·역 검색, 내 주변 정류장, 정류장 실시간 도착(국토교통부 서비스 지역), 노선 타임라인과 버스 위치,
  지하철 실시간 도착(서울·수도권), 기차·항공 시간표, 대중교통·자동차 길찾기 미리보기(경로 옵션 포함), 즐겨찾기, 라이트·다크·시스템 모드
- 기차 탭: 역을 **가나다순 / 지역별**로 골라 볼 수 있고, 직통이 없으면 **환승 1회** 경로를 찾아 줍니다. 열차마다 **정차역**을 펼쳐 볼 수 있습니다.
  - 정차역은 앱과 같은 한국철도공사 시간표 파일(`app/src/main/assets/train_timetable.tsv`)에서 바로 찾습니다(인터넷·TAGO 호출 없음). 시간표에 없는 열차는 "찾지 못했습니다"가 나옵니다.
  - 환승은 서울·대전·동대구·익산 등 주요 환승역 약 30곳을 거치는 경우만 찾고, 갈아타는 시간은 10분 이상으로 잡습니다.
- 아직 없는 것: 고속·시외버스, 서울·경기·부산 등 지역 전용 버스 API, 지하철 시간표·출구, 집/회사
- 일부러 뺀 것(폰에서만 의미 있음): GPS 경로 안내, 하차·막차·출근 알림, 홈 화면 위젯, 음성 안내
- 지도는 기본 OpenStreetMap(Leaflet)입니다. 카카오맵으로 보려면 카카오 개발자 사이트 → 앱 → 플랫폼 키 → JavaScript 키의
  "JavaScript SDK 도메인"에 `http://localhost:8000` 을 등록하고, 그 키를 `local.properties` 에 `KAKAO_JS_KEY=키` 로 한 줄 넣으세요.
  (더보기 → 지도에서 OpenStreetMap 으로 되돌릴 수 있고, 카카오맵을 못 불러오면 자동으로 OpenStreetMap 으로 열립니다.)

## Windows에서 앱처럼 설치 (PWA)

웹 버전을 Firebase Hosting에 배포한 뒤 Edge에서 사이트를 열고 **설정 및 기타 → 앱 → 이 사이트를 앱으로 설치**를 선택하면 시작 메뉴와 작업 표시줄에서 앱처럼 실행할 수 있습니다. Chrome에서도 사이트 메뉴의 **앱 설치**를 사용할 수 있습니다. 개발 중에는 `http://localhost:8000`에서도 확인할 수 있습니다.

iPhone/iPad에서는 HTTPS로 연 웹사이트의 Safari **공유 → 홈 화면에 추가**를 선택해 웹앱으로 실행할 수 있도록 Apple 메타데이터와 아이콘도 연결했습니다. 이는 App Store용 네이티브 iOS 앱과는 별도입니다.

서비스 워커는 화면 파일만 캐시합니다. `/api/` 실시간 교통 응답과 지도 타일은 캐시하지 않으므로 도착정보·검색·지도 기능에는 인터넷 연결이 필요합니다. 이 PWA는 별도 `.exe`/MSIX 설치 프로그램과는 다른 방식입니다.

## Firebase Hosting + Cloud Run 준비

`firebase.json`은 `static/`만 Firebase Hosting에 제공하고, `/api/**`를 `tagohub-api` Cloud Run 서비스의 `asia-northeast3` 리전으로 전달합니다. Python 서버 컨테이너는 `deploy/Dockerfile`을 사용합니다. `deploy/prepare-cloudrun-context.ps1`은 배포에 필요한 서버, 정적 파일, 작은 TSV 데이터만 프로젝트의 `.work/` 아래 임시 빌드 폴더로 복사합니다. `local.properties`, Firebase 클라이언트 설정, 서명 파일과 API 키는 복사하지 않습니다.

배포 전에는 Firebase 프로젝트 연결, Secret Manager의 서버 API 키, Cloud Run 공개 호출에 대한 인증·남용 방지, 정류장 DB의 인스턴스별 다운로드 동작을 검토해야 합니다. Cloud Run은 Firebase Spark 요금제에서 사용할 수 없으며 Billing 계정을 연결하면 Blaze로 바뀔 수 있습니다. 이 저장소에는 아직 Firebase 프로젝트 설정이 없으므로 이 파일들은 배포 준비용이며, 결제 설정이나 공개 배포를 수행하지 않습니다. Android/iOS Firebase 연결과 즐겨찾기 계정 동기화도 아직 구성되지 않았습니다.

## exe 만들기

```bash
pip install pyinstaller
pyinstaller --noconfirm --onefile --name TagoHubWeb \
  --add-data "web/static;static" --add-data "app/src/main/assets/subway_stations.tsv;data" --add-data "app/src/main/assets/train_timetable.tsv;data" web/server.py
```
`dist/TagoHubWeb.exe` 가 생깁니다. 키(`local.properties`)는 exe에 넣지 않습니다 — 넣으면 exe를 받은 사람이 꺼낼 수 있습니다.

## 시험

```bash
python web/test_server.py     # 서버의 순수한 부분 (인터넷·키 없이)
```
