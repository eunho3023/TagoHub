package com.eunho.tagohub.data

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * 앱 제보를 깃허브 로그인 없이 바로 보냄: Firestore 컬렉션 reports 에 한 건 추가 (익명 로그인으로도 쓸 수 있고 읽기는 막혀 있음, firestore.rules 참고).
 * 보내지 못하면(오프라인·규칙 미배포 등) 호출한 쪽이 깃허브 제보 화면으로 넘어감.
 */
object ReportSender {
    private const val TAG = "TagoReport"
    const val MAX_CHARS = 2000
    val KINDS = listOf("오류", "추가 요청", "삭제 요청")

    /** Firestore 에 올릴 필드. 규칙의 허용 목록과 같아야 함 */
    fun fields(kind: String, text: String, appVersion: String, device: String, os: String, uid: String): Map<String, Any> = mapOf(
        "kind" to kind, "text" to text.trim().take(MAX_CHARS), "appVersion" to appVersion, "device" to device, "os" to os,
        "uid" to uid, "createdAt" to FieldValue.serverTimestamp(),
    )

    /** [onDone] 은 보냈으면 true. 메인 스레드에서 불림 */
    fun send(context: Context, kind: String, text: String, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val os = "Android ${android.os.Build.VERSION.RELEASE}"
        fun write(uid: String) {
            FirebaseFirestore.getInstance().collection("reports").add(fields(kind, text, version, device, os, uid))
                .addOnSuccessListener { onDone(true) }
                .addOnFailureListener { Log.w(TAG, "제보 전송 실패", it); onDone(false) }
        }
        runCatching {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            if (user != null) write(user.uid)
            else auth.signInAnonymously()
                .addOnSuccessListener { r -> r.user?.uid?.let(::write) ?: onDone(false) }
                .addOnFailureListener { Log.w(TAG, "익명 로그인 실패", it); onDone(false) }
        }.onFailure { Log.w(TAG, "제보 시작 실패", it); onDone(false) }
    }
}
