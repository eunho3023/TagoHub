# TagoHub 작업 규칙

이 저장소에서 코드나 문서를 수정할 때 따르는 규칙입니다. 사람과 자동화 에이전트 모두 같은 절차를 씁니다.

## 1. 변경하면 PR까지 만든다

1. 작업은 `main`이 아닌 기능 브랜치에서 한다. (`main` 직접 푸시 금지)
2. 의도한 파일만 스테이징하고 커밋한다.
3. 원격에 푸시한 뒤 **같은 작업 안에서 PR을 만든다.** 푸시만 하고 끝내지 않는다.
   - base: `main` / head: 작업 브랜치
   - 제목: 변경을 한 줄로 요약 (한국어)
   - 본문: 아래 형식
4. 검증이 끝나지 않았으면 초안으로 연다. `gh pr create --draft ...` → 검증 후 ready 로 전환.
5. 이미 열린 PR이 있으면 새로 만들지 않고 본문만 갱신한다. `gh pr edit <번호> --body-file <파일>`
6. 실험용 잡브랜치나 의미 없는 변경에는 PR을 만들지 않는다.

```bash
gh pr create --base main --head <브랜치> \
  --title "<한 줄 요약>" \
  --body-file <본문 파일>
```

## 2. PR 본문 형식

```markdown
## 요약
## 배경
## 변경 사항
## 변경 파일
## 테스트 방법
## 검증
## 리스크와 롤백
## 체크리스트
```

- 실행한 검사와 **실행하지 못한 검사**를 구분해 적는다.
- 스크린샷이 필요하면 첨부 자리와 함께 병합 전 확인 항목으로 남긴다.

## 3. 커밋

- 메시지 첫 줄은 한국어 요약, 본문에는 변경 이유와 범위를 적는다.
- 마지막 줄에 아래 트레일러를 넣는다.

```text
Co-authored-by: Manus <dev-agent@manus.ai>
```

## 4. 비밀값

아래는 커밋하지 않는다. 스테이징 후 diff에서 API 키·토큰·개인키 패턴을 한 번 확인한다.

- `local.properties`, `keystore.properties`, `*.jks`, `*.keystore`
- `app/google-services.json`

## 5. 검증 기본값

| 대상 | 명령 |
|---|---|
| 웹 | `node --check web/static/app.js` |
| 앱 | `./gradlew :app:compileDebugKotlin` (JDK 17 + Android SDK, `google-services.json` 필요) |
| 커밋 전 | `git status` 로 의도한 파일만 스테이징됐는지 확인 |

컴파일을 실행할 수 없는 환경이면 PR 본문의 "검증" 항목에 그 사실을 명시하고 리뷰어 확인 항목으로 남긴다.
