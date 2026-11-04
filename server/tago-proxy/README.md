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

`deploy.ps1` 이 `composeApp`(Compose Multiplatform) 웹 빌드를 `public/` 에 넣고 한글 글꼴(Noto Sans KR, OFL)을 받아 같이 올린다.
서버 주소(`https://….workers.dev/`)를 브라우저로 열면 웹 버전이 보인다.

- 웹 페이지에는 앱 토큰을 넣을 수 없으므로, 실시간은 `/w/dg/1613000/...` 로 토큰 없이 받는다.
  허락하는 것은 버스 도착(`ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList`)·위치(`BusLcInfoInqireService/getRouteAcctoBusLcList`)뿐이고,
  질의는 `cityCode`·`nodeId`/`routeId` 만 (값 모양 검사), 쪽 번호·개수는 서버가 정함 (`src/proxy.js` `webRequest`).
- 앱과 같은 5초 저장을 같이 쓰고, 주소(IP)마다 1분 120번까지 (`wrangler.toml` `[[ratelimits]] WEB_LIMIT`).
- PC 버전(데스크톱)은 같은 화면을 `./gradlew :composeApp:run` 으로 띄움 (서버 주소는 local.properties 의 `TAGO_PROXY_URL`).
