# TagoHub 공공데이터 중계 서버

Cloudflare Workers 무료 요금제로 돌리는 작은 서버입니다. 하루 10만 요청까지 무료입니다.

- **키 보호**: 공공데이터포털 키를 서버의 비밀 값(`DATA_GO_KR_KEY`)으로 둡니다. 앱에는 키가 들어가지 않습니다.
- **호출 절약**: 같은 요청을 잠시 저장해 두고 나눠 줍니다.
  - 버스 도착·위치: 15초
  - 주변 정류장: 1시간
  - 노선·정류장 목록: 하루
- **막기**: 앱이 보내는 토큰(`APP_TOKEN`)이 맞아야 응답합니다. 허락한 기관(`src/proxy.js`의 `ALLOWED`)만 중계합니다.

앱은 `https://<주소>/dg/1613000/ArvlInfoInqireService/...`처럼 키 없이 묻고, 서버가 `https://apis.data.go.kr/1613000/...`에 키를 붙여 묻습니다.

**여러 기관 중계** (`/h/<http|https>/<호스트>/<경로>`): 앱은 키 자리에 `TAGOHUB_<키 이름>`을 넣어 보내고, 서버가 같은 이름의 비밀 값으로 바꿉니다. 검색 조건·경로·카카오 `Authorization` 헤더 어디든 됩니다.
- 대상: 공공데이터포털, 카카오, 철도공사 지하철, 서울 지하철·신호, 도시교통정보센터, 도로공사, 울산 버스
- 비밀 값: `TAGO_SERVICE_KEY`, `KLID_BUS_KEY`, `KAC_API_KEY`, `KRIC_SUBWAY_KEY`, `UTIC_API_KEY`, `SEOUL_SIGNAL_KEY`, `EX_API_KEY`, `SEOUL_SUBWAY_KEY`, `KAKAO_REST_KEY` (`deploy.ps1`이 local.properties에서 읽어 넣음)
- 국가교통정보센터(`openapi.its.go.kr:9443`)는 Cloudflare가 9443 포트를 못 불러서 앱에 키를 둡니다. 카카오 지도 키(네이티브)와 Firebase 설정도 앱에 있어야 합니다.

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
