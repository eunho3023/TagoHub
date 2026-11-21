// 타볼까(Cloudflare Pages)에서 서버 코드 돌리기: 서버 길(/w/ /h/ /dg/ /log/ /appshape/ /health, pages/_routes.json)만 여기로 오고
// 나머지(화면·자료 파일)는 Pages 가 바로 내줌. 서버 코드는 예전 서버(Workers)와 같은 src/index.js 를 그대로 씀.
// 바인딩(D1 DB, 자료 파일 ASSETS)과 비밀 값은 deploy.ps1 이 Pages 프로젝트에 넣음
import worker from "../../src/index.js";

export async function onRequest(context) {
  const ctx = { waitUntil: (p) => context.waitUntil(p), passThroughOnException: () => context.passThroughOnException?.() };
  return worker.fetch(context.request, context.env, ctx);
}
