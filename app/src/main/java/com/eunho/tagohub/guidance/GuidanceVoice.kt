package com.eunho.tagohub.guidance

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 경로 안내 음성: 기기에 설치된 음성 합성(TTS)으로 한국어를 읽는다 (인터넷이 없어도 됨).
 * - 말하는 동안 음악·라디오를 잠깐 줄이고(오디오 포커스), 끝나면 되돌린다.
 * - 안내 전용 오디오 속성을 써서 차량 스피커·블루투스가 내비 음성으로 인식하게 한다.
 * - 안내는 하나씩 차례로 말한다(앞 말을 끊지 않음). 다만 밀린 말이 [MAX_BACKLOG] 건을 넘으면 늦은 정보라 비우고 최신만 말한다.
 *   [speak] 의 interrupt 가 true 면(경로 이탈 같은 급한 안내) 앞 말을 끊고 바로 말한다.
 */
class GuidanceVoice(context: Context) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(app, this)
    private var focus: AudioFocusRequest? = null
    private val pending = ArrayDeque<String>()
    private var counter = 0

    /** 말하는 중이거나 차례를 기다리는 안내 번호 */
    private val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 음성 엔진이 준비됐는지 */
    @Volatile var ready = false
        private set

    /** 한국어 음성을 쓸 수 있는지 (설치 안 된 기기면 false → 화면 안내만) */
    @Volatile var koreanAvailable = true
        private set

    @Volatile var enabled = true

    /** 안내음성 크기 0.0~1.0 (기기 미디어 음량과 별개로 이 앱 음성만 조절) */
    @Volatile var volume = 1f

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.w(TAG, "음성 엔진을 시작하지 못함 (status=$status)")
            koreanAvailable = false
            return
        }
        val result = engine.setLanguage(Locale.KOREAN)
        koreanAvailable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        engine.setAudioAttributes(attrs)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        ready = true
        while (pending.isNotEmpty()) speak(pending.removeFirst())
    }

    fun speak(text: String, interrupt: Boolean = false) {
        if (!enabled || text.isBlank()) return
        val engine = tts ?: return
        if (!ready) {
            // 엔진이 아직 켜지는 중: 최근 몇 건만 기억
            while (pending.size >= MAX_BACKLOG) pending.removeFirst()
            pending.addLast(text)
            return
        }
        if (!koreanAvailable) return
        requestFocus()
        val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        val flush = interrupt || queued.size >= MAX_BACKLOG
        if (flush) queued.clear()
        val id = "nav-${counter++}"
        queued += id
        engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
    }

    fun stopSpeaking() {
        queued.clear()
        tts?.stop()
        abandonFocus()
    }

    fun shutdown() {
        abandonFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun requestFocus() {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
            .also { focus = it }
        audio.requestAudioFocus(req)
    }

    private fun finished(id: String?) {
        if (id != null) queued.remove(id)
        if (queued.isEmpty()) abandonFocus()
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
    }

    private companion object {
        const val TAG = "TagoGuidance"

        /** 말하는 중 + 기다리는 안내가 이 수에 이르면 비우고 최신만 */
        const val MAX_BACKLOG = 3
    }
}
