package com.eunho.tagohub.ui.common

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.CloudAccount
import com.eunho.tagohub.data.EmailAuth
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.LoginGate
import com.eunho.tagohub.data.PhoneNumber
import com.eunho.tagohub.ui.map.MapViewModel
import kotlinx.coroutines.launch

/** 로그인 방법: 이메일+비밀번호 / 메일로 온 링크 / 문자로 온 인증번호 */
private enum class AuthMode(val label: String) { Password("비밀번호"), Link("이메일 링크"), Phone("전화번호") }

/**
 * 가입·로그인 창. 이메일+비밀번호, 이메일 링크(비밀번호 없이), 전화번호(문자 인증번호), 구글 계정 중에서 고름.
 * 가입하면 지금까지의 즐겨찾기·설정이 그 계정에 그대로 이어지고, 로그인하면 그 계정에 저장된 것을 받아 옴. 성공하면 [onSuccess].
 */
@Composable
fun EmailAuthDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) {
        var c = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        c as? Activity
    }
    val account by CloudAccount.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(AuthMode.Password) }
    var signUp by rememberSaveable { mutableStateOf(true) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var linkSent by rememberSaveable { mutableStateOf(false) }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // 메일의 링크를 눌러 로그인이 끝나면(앱이 링크를 받아 처리) 창을 닫음
    LaunchedEffect(linkSent, account.email) { if (linkSent && account.email != null) onSuccess() }

    fun run(block: suspend () -> String?) {
        busy = true
        error = null
        info = null
        scope.launch {
            val msg = block()
            busy = false
            if (msg == null || msg.startsWith("다른 계정")) onSuccess() else error = msg   // 다른 계정으로 바뀐 안내는 성공으로 봄
        }
    }

    fun sendLink() {
        busy = true
        error = null
        info = null
        scope.launch {
            val msg = CloudAccount.sendEmailLink(context, email)
            busy = false
            if (msg == null) { linkSent = true; info = "메일을 보냈어요. 이 폰에서 메일을 열어 링크를 눌러 주세요" } else error = msg
        }
    }

    fun sendCode(resend: Boolean) {
        val a = activity ?: return
        val number = PhoneNumber.toE164(phone)
        if (number == null) { error = EmailAuth.message("ERROR_INVALID_PHONE_NUMBER"); return }
        busy = true
        error = null
        info = null
        CloudAccount.sendPhoneCode(
            a, number, resend,
            onSent = { busy = false; codeSent = true; info = "${PhoneNumber.masked(number)} 로 인증번호를 보냈어요" },
            onDone = { msg -> busy = false; if (msg == null || msg.startsWith("다른 계정")) onSuccess() else error = msg },
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (mode == AuthMode.Password && signUp) "회원가입" else "로그인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m; error = null; info = null }, label = { Text(m.label) })
                    }
                }
                if (mode == AuthMode.Password) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = signUp, onClick = { signUp = true; error = null }, label = { Text("가입") })
                    FilterChip(selected = !signUp, onClick = { signUp = false; error = null }, label = { Text("로그인") })
                }
                if (mode != AuthMode.Phone) OutlinedTextField(
                    email, { email = it; error = null }, label = { Text("이메일") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                )
                if (mode == AuthMode.Password) OutlinedTextField(
                    password, { password = it; error = null },
                    label = { Text(if (signUp) "비밀번호 (${EmailAuth.MIN_PASSWORD}자 이상)" else "비밀번호") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                if (mode == AuthMode.Phone) {
                    OutlinedTextField(
                        phone, { phone = it; error = null }, label = { Text("전화번호 (예: 010-1234-5678)") }, singleLine = true,
                        enabled = !codeSent, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                    )
                    if (codeSent) OutlinedTextField(
                        code, { code = it.filter(Char::isDigit).take(PhoneNumber.CODE_LENGTH); error = null },
                        label = { Text("인증번호 ${PhoneNumber.CODE_LENGTH}자리") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                Text(
                    when (mode) {
                        AuthMode.Password -> if (signUp) "가입하면 지금 저장된 즐겨찾기와 설정이 계정에 이어져요." else "로그인하면 그 계정에 저장된 즐겨찾기와 설정을 받아 와요."
                        AuthMode.Link -> "비밀번호 없이, 메일로 보내는 링크를 누르면 로그인돼요. 링크를 요청한 이 폰에서 열어 주세요."
                        AuthMode.Phone -> "문자로 인증번호를 보내요. 문자 요금이 부과될 수 있어요. 전화번호만으로는 보안이 약하니, 가능하면 이메일이나 구글 계정도 함께 쓰세요."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (mode == AuthMode.Password && !signUp) TextButton(enabled = !busy, onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val msg = CloudAccount.sendPasswordReset(email)
                            busy = false
                            if (msg == null) info = "재설정 메일을 보냈어요. 메일함을 확인해 주세요" else error = msg
                        }
                    }) { Text("비밀번호 재설정") }
                    if (mode == AuthMode.Phone && codeSent) TextButton(enabled = !busy, onClick = { sendCode(resend = true) }) { Text("인증번호 다시 받기") }
                    TextButton(enabled = !busy && activity != null, onClick = {
                        val a = activity ?: return@TextButton
                        run { CloudAccount.signInWithGoogle(a) }
                    }) { Text("구글로 계속") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                when (mode) {
                    AuthMode.Password -> run { if (signUp) CloudAccount.signUpWithEmail(context, email, password) else CloudAccount.signInWithEmail(context, email, password) }
                    AuthMode.Link -> sendLink()
                    AuthMode.Phone -> if (codeSent) run { CloudAccount.confirmPhoneCode(context, code) } else sendCode(resend = false)
                }
            }) {
                Text(
                    when {
                        busy -> "처리 중…"
                        mode == AuthMode.Password -> if (signUp) "가입" else "로그인"
                        mode == AuthMode.Link -> if (linkSent) "링크 다시 받기" else "링크 받기"
                        codeSent -> "확인"
                        else -> "인증번호 받기"
                    },
                )
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * 메일의 로그인 링크를 눌러 앱이 열렸을 때 로그인을 마무리함. 링크를 보낼 때 적어 둔 이메일이 있으면 바로 로그인하고,
 * 없으면(다른 폰에서 열었을 때 등) 이메일을 한 번 물어봄.
 */
@Composable
fun EmailLinkHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link by CloudAccount.emailLink.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val l = link ?: return
    val saved = remember(l) { CloudAccount.pendingLinkEmail(context) }

    fun finish(msg: String?) {
        CloudAccount.emailLink.value = null
        android.widget.Toast.makeText(context, msg ?: "로그인했어요", android.widget.Toast.LENGTH_LONG).show()
    }

    if (saved != null) {
        LaunchedEffect(l) { finish(CloudAccount.completeEmailLink(context, l)) }
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) CloudAccount.emailLink.value = null },
        title = { Text("이메일을 확인할게요") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("로그인 링크를 받은 이메일 주소를 입력해 주세요.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    email, { email = it; error = null }, label = { Text("이메일") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val msg = CloudAccount.completeEmailLink(context, l, email)
                    busy = false
                    if (msg == null || msg.startsWith("다른 계정")) finish(msg) else error = msg
                }
            }) { Text(if (busy) "처리 중…" else "로그인") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { CloudAccount.emailLink.value = null }) { Text("취소") } },
    )
}

/**
 * 즐겨찾기를 새로 넣으려는데 로그인이 안 되어 있을 때 뜨는 안내: 로그인하고 추가 / 그냥 추가 / 취소.
 * (로그인 없이 추가하면 앱을 지우거나 폰을 바꿀 때 사라질 수 있음)
 */
@Composable
fun LoginGateHost(vm: MapViewModel) {
    val pending by LoginGate.pending.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    var showAuth by remember { mutableStateOf(false) }
    val f = pending ?: return

    fun add(item: Favorite) {
        if (favorites.none { it.kind == item.kind && it.id == item.id }) vm.applyToggleFavorite(item)
        LoginGate.clear()
        showAuth = false
    }

    if (showAuth) {
        EmailAuthDialog(onDismiss = { showAuth = false }, onSuccess = { add(f) })
        return
    }
    AlertDialog(
        onDismissRequest = { LoginGate.clear() },
        title = { Text("로그인이 안 되어 있어요") },
        text = { Text("로그인하고 추가하면 앱을 지우거나 폰을 바꿔도 즐겨찾기가 이어져요. 로그인하지 않고 추가할 수도 있어요.") },
        confirmButton = { TextButton(onClick = { showAuth = true }) { Text("로그인하고 추가") } },
        dismissButton = {
            Row {
                TextButton(onClick = { LoginGate.clear() }) { Text("취소") }
                TextButton(onClick = { add(f) }) { Text("그냥 추가") }
            }
        },
    )
}
