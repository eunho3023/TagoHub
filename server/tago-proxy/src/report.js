// 웹 버전 앱 제보: POST /w/report {kind, text} → 폰 앱(ReportSender)과 같은 Firestore 컬렉션 reports 에 한 건.
// 서버가 Firebase 익명 로그인(Identity Toolkit) 후 Firestore REST 로 씀. 규칙(firestore.rules)의 허용 필드·조건과 같게.
// 비밀 값: FIREBASE_API_KEY, FIREBASE_PROJECT_ID (deploy.ps1 이 app/google-services.json 에서 넣음),
// FIREBASE_ANDROID_CERT (키가 안드로이드 앱으로 제한돼 있을 때 필요, 같은 파일의 인증서 해시)

export const KINDS = ["오류", "추가 요청", "삭제 요청"];
export const MAX_CHARS = 2000;
const HEAD = { "Access-Control-Allow-Origin": "*", "Content-Type": "application/json; charset=utf-8" };
const PACKAGE = "com.eunho.tagohub";

/** 받은 내용 확인 → {kind, text, device} 또는 null */
export function parseReport(body) {
  if (!body || typeof body !== "object") return null;
  const kind = String(body.kind || "");
  const text = String(body.text || "").trim().slice(0, MAX_CHARS);
  if (!KINDS.includes(kind) || !text) return null;
  const device = String(body.device || "").replace(/[\u0000-\u001f]/g, " ").trim().slice(0, 120) || "웹";
  return { kind, text, device };
}

/** Firestore commit 요청 본문 (createdAt 은 서버 시각 = 규칙의 request.time) */
export function commitBody(project, id, uid, r) {
  const s = (v) => ({ stringValue: v });
  return {
    writes: [{
      update: {
        name: `projects/${project}/databases/(default)/documents/reports/${id}`,
        fields: { kind: s(r.kind), text: s(r.text), appVersion: s("web"), device: s(r.device), os: s("웹"), uid: s(uid) },
      },
      updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }],
      currentDocument: { exists: false },
    }],
  };
}

function androidHeaders(env) {
  const cert = (env.FIREBASE_ANDROID_CERT || "").replace(/[^0-9A-Fa-f]/g, "").toUpperCase();
  return cert ? { "X-Android-Package": PACKAGE, "X-Android-Cert": cert } : {};
}

/** 익명 로그인 (50분 동안 같은 계정을 다시 씀: 제보마다 익명 사용자가 늘지 않게) */
async function anonToken(env, fetcher) {
  const cache = typeof caches !== "undefined" ? caches.default : null;
  const key = new Request("https://cache.tagohub/report/anon");
  const hit = cache && (await cache.match(key));
  if (hit) return hit.json();
  const r = await fetcher(`https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${encodeURIComponent(env.FIREBASE_API_KEY)}`, {
    method: "POST", headers: { "Content-Type": "application/json", ...androidHeaders(env) }, body: JSON.stringify({ returnSecureToken: true }),
  });
  if (!r.ok) throw new Error("익명 로그인 실패 " + r.status);
  const j = await r.json();
  const t = { idToken: j.idToken, uid: j.localId };
  if (!t.idToken || !t.uid) throw new Error("익명 로그인 응답 이상");
  if (cache) await cache.put(key, new Response(JSON.stringify(t), { headers: { "Cache-Control": "max-age=3000" } }));
  return t;
}

export async function handleReport(request, env, ctx, fetcher = fetch) {
  if (request.method !== "POST") return new Response(JSON.stringify({ ok: false }), { status: 405, headers: HEAD });
  if (!env.FIREBASE_API_KEY || !env.FIREBASE_PROJECT_ID) return new Response(JSON.stringify({ ok: false, why: "설정 없음" }), { status: 503, headers: HEAD });
  let body = null;
  try { body = await request.json(); } catch (_) { /* 아래에서 거름 */ }
  const r = parseReport(body);
  if (!r) return new Response(JSON.stringify({ ok: false, why: "내용 확인" }), { status: 400, headers: HEAD });
  try {
    const t = await anonToken(env, fetcher);
    const id = crypto.randomUUID().replace(/-/g, "").slice(0, 20);
    const res = await fetcher(`https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents:commit`, {
      method: "POST", headers: { "Content-Type": "application/json", Authorization: "Bearer " + t.idToken, ...androidHeaders(env) },
      body: JSON.stringify(commitBody(env.FIREBASE_PROJECT_ID, id, t.uid, r)),
    });
    if (!res.ok) throw new Error("Firestore " + res.status);
    return new Response(JSON.stringify({ ok: true }), { headers: HEAD });
  } catch (e) {
    return new Response(JSON.stringify({ ok: false, why: String(e.message || e) }), { status: 502, headers: HEAD });
  }
}
