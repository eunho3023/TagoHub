import { test } from "node:test";
import assert from "node:assert/strict";
import { parseReport, commitBody, handleReport } from "../src/report.js";

test("제보 내용 확인", () => {
  assert.deepEqual(parseReport({ kind: "오류", text: "  버스가 안 보여요 ", device: "Chrome" }), { kind: "오류", text: "버스가 안 보여요", device: "Chrome" });
  assert.equal(parseReport({ kind: "기타", text: "x" }), null);
  assert.equal(parseReport({ kind: "오류", text: "   " }), null);
  assert.equal(parseReport({ kind: "오류", text: "가".repeat(3000) }).text.length, 2000);
});

test("Firestore 문서 모양은 규칙의 필드와 같음", () => {
  const b = commitBody("tagohub", "abc", "u1", { kind: "오류", text: "t", device: "d" });
  const w = b.writes[0];
  assert.deepEqual(Object.keys(w.update.fields).sort(), ["appVersion", "device", "kind", "os", "text", "uid"]);
  assert.equal(w.update.fields.uid.stringValue, "u1");
  assert.equal(w.updateTransforms[0].fieldPath, "createdAt");
  assert.ok(w.update.name.endsWith("/documents/reports/abc"));
});

test("익명 로그인 뒤 Firestore 에 씀, 설정이 없으면 503", async () => {
  globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };
  const calls = [];
  const fetcher = async (u, o) => {
    calls.push([u, o]);
    if (u.includes("identitytoolkit")) return { ok: true, json: async () => ({ idToken: "tok", localId: "uid9" }) };
    return { ok: true, json: async () => ({}) };
  };
  const env = { FIREBASE_API_KEY: "k", FIREBASE_PROJECT_ID: "tagohub", FIREBASE_ANDROID_CERT: "ab:cd" };
  const req = (body) => ({ method: "POST", json: async () => body });
  const r = await handleReport(req({ kind: "추가 요청", text: "노선 추가" }), env, {}, fetcher);
  assert.equal(r.status, 200);
  assert.equal(calls[1][1].headers.Authorization, "Bearer tok");
  assert.equal(calls[1][1].headers["X-Android-Cert"], "ABCD");
  assert.equal(JSON.parse(calls[1][1].body).writes[0].update.fields.uid.stringValue, "uid9");
  assert.equal((await handleReport(req({ kind: "오류", text: "x" }), {}, {}, fetcher)).status, 503);
  assert.equal((await handleReport(req({ kind: "x", text: "x" }), env, {}, fetcher)).status, 400);
});
