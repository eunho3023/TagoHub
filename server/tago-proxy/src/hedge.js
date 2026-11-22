/**
 * 바깥 기관에 묻되, [hedgeMs] 안에 답이 없으면 같은 요청을 새 연결로 하나 더 보내고 먼저 온 좋은 답을 씀 (최대 [tries] 번).
 *
 * 왜: Cloudflare → 공공데이터포털(apis.data.go.kr) 연결이 자주 맺어지지 않아, 약 20초 기다린 뒤 522 로 끝남
 * (2026-11-22 잰 것: 웹 서버 거쳐 5번 중 3번 522·20초, 같은 PC 에서 포털로 바로 물으면 8번 모두 0.2~2.6초).
 * 실패한 바로 다음 요청은 0.2초 만에 오기도 해서, 한 연결을 20초 기다리는 대신 3초마다 새로 물음.
 *
 * 좋은 답: 상태가 522·502·503·504·520·521·523·524 가 아닌 응답 (그 밖의 오류는 기관이 답한 것이라 그대로 돌려줌).
 * 모두 실패하면 마지막 응답, 응답이 하나도 없으면 마지막 오류를 던짐. [totalMs] 가 지나면 504.
 * GET 만 겹쳐 보냄 (POST 는 한 번만).
 */
const RETRY_STATUS = new Set([502, 503, 504, 520, 521, 522, 523, 524]);
export const HEDGE_MS = 3000, HEDGE_TRIES = 3, HEDGE_TOTAL_MS = 15000;

export function hedgedFetch(url, init = {}, { hedgeMs = HEDGE_MS, tries = HEDGE_TRIES, totalMs = HEDGE_TOTAL_MS, fetcher = fetch } = {}) {
  if (init.method && init.method !== "GET") tries = 1;
  return new Promise((resolve, reject) => {
    const ctrls = [];
    let done = false, pending = 0, started = 0, lastRes = null, lastErr = null, timer = null;
    const finish = (fn, v) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      clearTimeout(cap);
      fn(v);
    };
    const abortOthers = (keep) => ctrls.forEach((c) => { if (c !== keep) try { c.abort(); } catch { /* 이미 끝남 */ } });
    const launch = () => {
      if (done || started >= tries) return;
      started++;
      pending++;
      const c = new AbortController();
      ctrls.push(c);
      Promise.resolve().then(() => fetcher(url, { ...init, signal: c.signal })).then((res) => {
        pending--;
        if (done) return;
        if (!RETRY_STATUS.has(res.status)) {
          abortOthers(c);
          // 몇 번 물었는지 (바깥 fetch 응답 헤더는 고칠 수 없어서 새 응답으로 감쌈)
          const out = new Response(res.body, res);
          out.headers.set("X-Tries", String(started));
          return finish(resolve, out);
        }
        lastRes = res;
        next();
      }, (e) => {
        pending--;
        if (done) return;
        lastErr = e;
        next();
      });
      clearTimeout(timer);
      if (started < tries) timer = setTimeout(launch, hedgeMs);
    };
    // 하나가 실패로 끝남: 아직 안 보낸 몫이 있으면 바로 보내고, 다 보냈고 기다리는 것도 없으면 끝
    const next = () => {
      if (started < tries) return launch();
      if (pending === 0) return lastRes ? finish(resolve, lastRes) : finish(reject, lastErr || new Error("upstream failed"));
    };
    const cap = setTimeout(() => {
      abortOthers(null);
      finish(resolve, lastRes || new Response("upstream timeout", { status: 504 }));
    }, totalMs);
    launch();
  });
}
