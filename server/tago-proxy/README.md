# TagoHub 공공데이터 중계 서버

Cloudflare Workers 무료 요금제로 돌리는 작은 서버입니다. 하루 10만 요청까지 무료입니다.

- **키 보호**: 공공데이터포털 키를 서버의 비밀 값(`DATA_GO_KR_KEY`)으로 둡니다. 앱에는 키가 들어가지 않습니다.
- **호출 절약**: 같은 요청을 잠시 저장해 두고 나눠 줍니다.
  - 버스 도착·위치: 5초
  - 주변 정류장: 1시간
  - 노선·정류장 목록: 하루
- **막기**: 앱이 보내는 토큰(`APP_TOKEN`)이 맞아야 응답합니다. 허락한 기관(`src/proxy.js`의 `ALLOWED`)만 중계합니다.

앱은 `https://<주소>/dg/1613000/ArvlInfoInqireService/...`처럼 키 없이 묻고, 서버가 `https://apis.data.go.kr/1613000/...`에 키를 붙여 묻습니다.

**여러 기관 중계** (`/h/<http|https>/<호스트>/<경로>`): 앱은 키 자리에 `TAGOHUB_<키 이름>`을 넣어 보내고, 서버가 같은 이름의 비밀 값으로 바꿉니다. 검색 조건·경로·카카오 `Authorization` 헤더 어디든 됩니다.
- 대상: 공공데이터포털, 카카오, 철도공사 지하철, 서울 지하철·신호, 도로공사, 울산 버스
- 비밀 값: `TAGO_SERVICE_KEY`, `KLID_BUS_KEY`, `KAC_API_KEY`, `KRIC_SUBWAY_KEY`, `SEOUL_SIGNAL_KEY`, `EX_API_KEY`, `SEOUL_SUBWAY_KEY`, `KAKAO_REST_KEY` (`deploy.ps1`이 local.properties에서 읽어 넣음)
- 국가교통정보센터(`openapi.its.go.kr:9443`)는 Cloudflare가 9443 포트를 못 불러서, 도시교통정보센터(UTIC)는 등록한 IP에서만 응답해서 앱에 키를 둡니다. 카카오 지도 키(네이티브)와 Firebase 설정도 앱에 있어야 합니다.

## 가장 쉬운 배포 (브라우저 로그인 없이)

1. https://dash.cloudflare.com/profile/api-tokens 에서 **Create Token** → **Edit Cloudflare Workers** 템플릿 → **Use template**
2. Account Resources: 내 계정, Zone Resources: **All zones** → **Continue to summary** → **Create Token** → 토큰 복사
3. PC 터미널에서:

```powershell
powershell -ExecutionPolicy Bypass -File server	ago-proxy\deploy.ps1
```

토큰을 물으면 붙여 넣습니다. 스크립트가 서버를 올리고, 공공데이터포털 키(`local.properties`의 `TAGO_SERVICE_KEY`)와 새로 만든 앱 토큰을 서버에 넣습니다. 그리고 `local.properties`에 `TAGO_PROXY_URL`·`TAGO_PROXY_TOKEN`을 적습니다.

## 처음 한 번 배포하기 (PC 터미널에서, 브라우저 로그인)

```bash
cd server/tago-proxy
npx wrangler@4.45.0 login
```

브라우저가 열리면 Cloudflare에 가입하거나 로그인한 뒤 허용을 누릅니다.

```bash
npx wrangler@4.45.0 secret put DATA_GO_KR_KEY
```

물어보면 공공데이터포털 키(`local.properties`의 `TAGO_SERVICE_KEY` 값)를 붙여 넣습니다.

```bash
npx wrangler@4.45.0 secret put APP_TOKEN
```

아무 긴 글자(예: 비밀번호 생성기로 만든 32자)를 넣고, 같은 값을 따로 적어 둡니다.

```bash
npx wrangler@4.45.0 deploy
```

끝나면 `https://tagohub-proxy.<계정>.workers.dev` 주소가 나옵니다.

## 앱에 연결하기

`local.properties`에 두 줄을 넣고 앱을 다시 빌드합니다.

```
TAGO_PROXY_URL=https://tagohub-proxy.<계정>.workers.dev
TAGO_PROXY_TOKEN=<위에서 넣은 APP_TOKEN 값>
```

두 줄이 없으면 앱은 예전처럼 키로 직접 묻습니다.

## 확인

```bash
curl https://tagohub-proxy.<계정>.workers.dev/health
```

`ok`가 나오면 서버가 떠 있는 것입니다. 앱 요청 응답의 `X-Cache: HIT`는 저장해 둔 것을 나눠 준 경우입니다.

## 시험

```bash
npm test
```

## 지난 운행 기록 (D1)

- `POST /log/hits` `{ routeId, hits: [[nodeId, epochSec], ...] }`: 앱이 본 "버스가 정류장을 지난 시각"을 모음 (노선·정류장·분만, 같은 분은 한 번).
- `GET /log/stop?route=&node=`: 그 노선·정류장의 최근 6주 기록(분)을 돌려줌. 정류장 시간표의 "지난 운행 기록"이 이 폰 기록과 합쳐 씀.
- 데이터베이스 `tagohub-log` 는 `deploy.ps1` 이 만들고 `wrangler.deploy.toml`(저장소에 안 올림)로 연결해 배포함. API 토큰에 **D1 편집** 권한이 있어야 함. 없으면 이 기능만 빠지고(503) 나머지는 그대로.
- 6주 지난 기록은 올릴 때 가끔(2%) 지움. 무료 한도: 하루 10만 줄 쓰기.

## 정류장·노선 자료 조각 (정적 파일)

- `deploy.ps1` 이 `pipeline/build_web_data.py` 로 최신 정류장 DB(GitHub Release data-latest)를 작은 JSON 조각으로 나눠 `public/data/` 에 만들고 함께 올림 (약 3천 개, 90MB, 저장소에는 안 올림).
- 주소: `/data/tiles/{위도*10}_{경도*10}.json`, `/data/names/{첫 글자 16진수}.json`, `/data/routes/{0~1023}.json`, `/data/cities.json`, `/data/files/{앱 자료 파일}`, `/data/manifest.json`.
- 공개 자료라 앱 토큰 없이 받을 수 있고(CORS 허용), 1시간 저장. 조각 규칙은 앱 공용 코드 `WebData` 와 같음.
- 무료 요금제: 파일 하나 25MB, 2만 개까지. 정적 파일 요청은 하루 10만 요청 한도에 들어가지 않음.

## 웹 버전 (화면 + 토큰 없는 실시간 통로)

`deploy.ps1` 이 `composeApp`(Compose Multiplatform) 웹 빌드를 `public/` 에 넣고, 한글 글꼴(Noto Sans KR, OFL)에서 필요한 글자만 남긴 작은 파일(`pipeline/build_web_font.py`, 약 2MB)을 같이 올린다.
서버 주소(`https://….workers.dev/`)를 브라우저로 열면 웹 버전이 보인다.

- 웹 페이지에는 앱 토큰을 넣을 수 없으므로, 실시간은 `/w/dg/1613000/...` 로 토큰 없이 받는다.
  허락하는 것은 버스 도착(`ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList`)·위치(`BusLcInfoInqireService/getRouteAcctoBusLcList`)뿐이고,
  질의는 `cityCode`·`nodeId`/`routeId` 만 (값 모양 검사), 쪽 번호·개수는 서버가 정함 (`src/proxy.js` `webRequest`).
  지하철 역 찾기·역 시간표, 기차·고속·시외버스 출발→도착 조회도 같은 허락 목록(`WEB_OPS`)에 있음.
- 고속도로(한국도로공사)는 `/w/ex/<기능>?pageNo=N`: 돌발 문자정보·정체 구간(2분 저장)·주유 가격(6시간 저장)만,
  키(`EX_API_KEY`)는 서버가 붙임 (`src/proxy.js` `exWebRequest`).
- 서울 지하철 실시간 도착 `/w/seoul/arrival/<역 이름>` (10초, `SEOUL_SUBWAY_KEY`, `seoulWebRequest`).
- 카카오 장소 찾기 `/w/kakao/place?q=&x=&y=` (하루) · 대중교통 길찾기 `/w/kakao/transit?sx=&sy=&ex=&ey=` (5분), `KAKAO_REST_KEY` (`kakaoWebRequest`).
- 한국공항공사 `/w/kac/<기능>`: 혼잡도·소요시간·주차장(2분), 공항별 하루 실시간 운항 `?air=&date=&pageNo=`(1분), `KAC_API_KEY` (`kacWebRequest`).
- 실시간 신호등 `/w/signal/seoul/<교차로 ID>` (3초, `SEOUL_SIGNAL_KEY`), `/w/signal/ulsan/map|now` (하루·3초, `KLID_BUS_KEY`) (`signalWebRequest`).
- 위 JSON 통로는 모두 `src/index.js` `cachedJson` 으로: 저장해 둔 것이 있으면 바로, 없으면 주소마다 분당 제한을 지나 묻고 정상 응답만 저장.
- 앱과 같은 5초 저장을 같이 쓰고, 주소(IP)마다 1분 120번까지 (`wrangler.toml` `[[ratelimits]] WEB_LIMIT`).
- PC 버전(데스크톱)은 같은 화면을 `./gradlew :composeApp:run` 으로 띄움 (서버 주소는 local.properties 의 `TAGO_PROXY_URL`).

## 노선 도로 모양 (`/w/shape/<노선 id>`)

웹·PC 버전 노선 화면의 선을 정류장 직선 대신 길 따라 그리려고, 서버가 OSRM 공개 서버로 노선 모양을 한 번 만들어
D1 `shapes` 표에 저장하고 모두에게 나눠 준다 (`src/shape.js`, 폰 앱 RoadShapeClient 와 같은 방법: 25개씩 구간 요청,
정류장 사이 블록을 한 바퀴 도는 구간은 직선). 처음 보는 노선만 1~3초, 그 뒤로는 바로. 정류장 목록이 바뀌면 다시 만든다.
노선 정류장은 정적 파일(`public/data/routes`)을 `env.ASSETS` 로 읽음. OSRM 공개 서버는 대량 사용용이 아니라, 사용자가 많아지면 자체 OSRM 으로 바꿀 것.

## 카카오 지도 (웹 버전)

웹 버전은 폰 앱과 같은 카카오 지도를 쓴다 (`composeApp/src/wasmJsMain/resources/kakaomap.js`). `deploy.ps1` 이 `local.properties` 의 `KAKAO_JS_KEY`
(카카오 JavaScript 키, 웹 페이지에 들어가도 되는 키)를 `public/config.js` 로 넣는다. 카카오 개발자 사이트 → 내 애플리케이션 → 앱 설정 →
플랫폼 키 → JavaScript 키 → **JavaScript SDK 도메인**에 서버 주소(`https://….workers.dev`)를 등록해야 지도가 보인다 (로컬 시험은 `http://localhost:8090` 도).
키가 없거나 도메인이 안 맞으면 OpenStreetMap 지도로 보인다. PC 버전은 늘 OpenStreetMap.
