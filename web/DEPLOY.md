# 웹 버전을 인터넷에 올리기 (Google Cloud Run)

PC·아이폰·안드로이드 브라우저에서 주소 하나로 쓰게 하는 방법입니다. 서버(`web/server.py`)가 키를 붙여 공공 API를 대신 부르고, 브라우저는 그 서버만 부릅니다.
키는 이미지에 들어가지 않고 배포할 때 Cloud Run 환경변수로만 들어갑니다(저장소에는 올라가지 않음).

## 처음 한 번만 (직접 해야 함)

1. **gcloud 설치**: https://cloud.google.com/sdk/docs/install → 설치 뒤 새 PowerShell 에서
   ```powershell
   gcloud auth login
   gcloud config set project tagohub
   ```
2. **결제 계정 연결**: https://console.cloud.google.com/billing 에서 `tagohub` 프로젝트에 결제 수단(카드)을 연결합니다.
   Cloud Run 은 월 무료 사용량(요청 약 200만 건, 메모리·CPU 일부)이 있어서 적게 쓰면 거의 0원이지만, 결제 계정이 있어야 켤 수 있습니다.
   **예산 알림을 만들어 두세요**(결제 → 예산 및 알림, 예: 월 5,000원).
3. **API 켜기** (한 번):
   ```powershell
   gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com
   ```

## 올리기

저장소 폴더에서:
```powershell
.\web\deploy.ps1
```
몇 분 뒤 `Service URL: https://tagohub-web-…run.app` 주소가 나옵니다. 그 주소를 폰·PC 브라우저에서 열면 됩니다.
(코드를 고친 뒤 같은 명령을 다시 실행하면 새 버전으로 바뀝니다.)

- 서울 리전(`asia-northeast3`), 최대 2대, 안 쓰면 0대로 줄어듦(첫 접속은 몇 초 느릴 수 있음).
- 한 주소(IP)당 1분에 90번까지만 허용(`RATE_LIMIT_PER_MIN`, Dockerfile). 넘으면 429 안내.
- 정류장 DB·지하철역·열차 시간표는 안드로이드 앱과 같은 파일(`app/src/main/assets`)이 이미지에 들어갑니다. 새로 만들려면 앱 데이터를 갱신한 뒤 다시 배포하세요.

## 카카오 지도를 쓰려면

카카오 개발자 사이트 → 내 애플리케이션 → 플랫폼 키 → JavaScript 키 → **JavaScript SDK 도메인**에 배포된 주소(`https://tagohub-web-….run.app`)를 추가하세요.
등록하지 않으면 지도는 자동으로 OpenStreetMap 으로 열립니다. 카카오 장소 검색·길찾기는 서버가 REST 키로 부르므로 도메인 등록이 필요 없습니다.

## 주의

- 공공 API를 서버가 대신 불러 여러 사람에게 보여 주는 것이라 **재제공 약관**이 중요합니다(`docs/api-terms-checklist.md`). 약관이 막는 데이터는 빼야 할 수 있습니다.
- 키 하나를 모든 방문자가 나눠 씁니다. 공공데이터 API의 하루 호출 한도(개발 계정은 보통 1,000~10,000건)를 넘으면 해당 기능이 오류를 냅니다. 사용자가 늘면 운영 계정 신청이 필요합니다.
- 접속 IP를 제한하는 API가 있다면 Cloud Run 은 고정 IP가 아니라서 동작하지 않을 수 있습니다(지금 웹 버전이 쓰는 TAGO·카카오·서울 지하철은 IP 제한 없음).
- 로그인·계정 동기화는 아직 없습니다. 즐겨찾기는 브라우저에 저장됩니다.
