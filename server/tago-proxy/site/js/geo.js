// 브라우저 위치: 정밀 위치(GPS)를 못 받으면 대략 위치로 한 번 더 묻고, 못 받은 까닭은 종류별로 안내.
// 위치는 이 기기 안에서만 씀 (서버로 보내지 않음).

const UA = navigator.userAgent || "";
/** 카카오톡·네이버·인스타그램 등 앱 안 브라우저 (위치를 막는 곳이 많음) */
export const inAppBrowser = /KAKAOTALK|NAVER\(inapp|Instagram|FBAN|FBAV|\bLine\/|DaumApps|everytimeApp|BAND\/|; wv\)/i.test(UA);
const iOS = /iPhone|iPad|iPod/.test(UA) || (/Macintosh/.test(UA) && navigator.maxTouchPoints > 1);

/** 사이트 권한 상태 ("granted"·"denied"·"prompt", 모르면 null) */
async function siteState() {
  try { return (await navigator.permissions.query({ name: "geolocation" })).state; } catch (e) { return null; }
}

/** 못 받은 까닭 → 안내 문구 */
export async function geoMessage(err) {
  if (!("geolocation" in navigator)) return "이 브라우저는 위치를 알려 주지 않아요.";
  if (!window.isSecureContext) return "보안 연결(https)에서만 위치를 쓸 수 있어요.";
  if (err && err.code === 1) {
    if (inAppBrowser) return "이 앱 안 브라우저는 위치를 막고 있어요. 오른쪽 위 메뉴에서 '다른 브라우저로 열기'로 크롬·사파리·삼성 인터넷에서 열어 주세요.";
    const site = await siteState();
    // 사이트는 허락했는데 막힘: 휴대폰 설정에서 브라우저 앱의 위치가 꺼진 경우
    const os = iOS
      ? "아이폰 설정 → 개인정보 보호 및 보안 → 위치 서비스가 켜져 있고, 그 아래 Safari 웹 사이트(또는 쓰는 브라우저)가 '앱을 사용하는 동안'인지 확인해 주세요."
      : "휴대폰 설정에서 위치가 켜져 있고, 쓰는 브라우저 앱(크롬·삼성 인터넷 등)의 위치 권한이 '앱 사용 중에만 허용'인지 확인해 주세요.";
    if (site === "granted") return "사이트는 허락했지만 휴대폰이 위치를 막고 있어요. " + os;
    return "위치 사용이 막혀 있어요. 주소창 왼쪽 자물쇠(사이트 설정)에서 위치를 '허용'으로 바꾸고, " + os;
  }
  if (err && err.code === 3) return "위치를 받는 데 너무 오래 걸려요. 휴대폰의 위치(GPS)가 켜져 있는지 보고, 밖이나 창가에서 다시 해 주세요.";
  return "기기가 위치를 찾지 못했어요. 휴대폰의 위치(GPS)가 켜져 있는지 확인해 주세요.";
}

const HIGH = { enableHighAccuracy: true, maximumAge: 5000, timeout: 20000 };
const LOW = { enableHighAccuracy: false, maximumAge: 120000, timeout: 20000 };

/** 지금 위치 한 번: [위도, 경도, 정확도m]. 못 받으면 Error(안내 문구) */
export function locate() {
  return new Promise((res, rej) => {
    if (!("geolocation" in navigator)) return geoMessage(null).then((m) => rej(new Error(m)));
    const ok = (p) => res([p.coords.latitude, p.coords.longitude, p.coords.accuracy || 0]);
    const fail = (e) => geoMessage(e).then((m) => rej(new Error(m)));
    navigator.geolocation.getCurrentPosition(ok, (e) => {
      if (e && e.code === 1) return fail(e);
      navigator.geolocation.getCurrentPosition(ok, fail, LOW); // GPS 가 안 되면 대략 위치(와이파이·기지국)로
    }, HIGH);
  });
}

/** 위치 따라가기: onPos([위도, 경도, 정확도m]), onError(안내 문구). 그만두는 함수를 돌려줌 */
export function follow(onPos, onError) {
  let id = null, stopped = false, got = false;
  const start = (opts, last) => {
    id = navigator.geolocation.watchPosition(
      (p) => { got = true; onPos([p.coords.latitude, p.coords.longitude, p.coords.accuracy || 0]); },
      (e) => {
        if (stopped) return;
        // 정밀 위치가 한 번도 안 왔으면 대략 위치로 다시. 이미 받고 있었으면 잠깐 끊긴 것이라 그대로 둠
        if (e && e.code !== 1 && !last && !got) { navigator.geolocation.clearWatch(id); start(LOW, true); return; }
        if (e && e.code !== 1 && got) return;
        geoMessage(e).then((m) => { if (!stopped) onError(m); });
      },
      opts,
    );
  };
  if (!("geolocation" in navigator)) geoMessage(null).then(onError);
  else start({ ...HIGH, maximumAge: 3000, timeout: 30000 }, false);
  return () => { stopped = true; if (id != null) navigator.geolocation.clearWatch(id); };
}
