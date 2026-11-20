package com.eunho.tagohub.ui.common

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.CloudAccount
import com.eunho.tagohub.data.IdAccount
import com.eunho.tagohub.data.PhoneNumber
import kotlinx.coroutines.launch

/** 아이디 회원 창의 화면들 */
private enum class IdPage { Login, SignUp, FindId, Reset }

private const val ASKED_PREF = "id_login_asked"

/**
 * 앱을 처음 깔았을 때 한 번만: 로그인할지 물어봄. "로그인" 을 누르면 아이디 로그인 창.
 * 이미 로그인한 계정(이메일·전화번호)이 있으면 묻지 않음.
 */
@Composable
fun FirstLoginPrompt() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    val account by CloudAccount.state.collectAsStateWithLifecycle()
    var asked by remember { mutableStateOf(prefs.getBoolean(ASKED_PREF, false)) }
    var login by remember { mutableStateOf(false) }
    fun done() { asked = true; prefs.edit().putBoolean(ASKED_PREF, true).apply() }
    if (!asked && account.email == null && account.phone == null) AlertDialog(
        onDismissRequest = { done() },
        title = { Text("로그인할까요?") },
        text = { Text("로그인하면 즐겨찾기와 설정이 계정에 저장돼서, 앱을 지우거나 폰을 바꿔도 그대로 이어져요. 나중에 설정에서도 할 수 있어요.") },
        confirmButton = { TextButton(onClick = { done(); login = true }) { Text("로그인") } },
        dismissButton = { TextButton(onClick = { done() }) { Text("나중에") } },
    )
    if (login) IdAuthDialog(onDismiss = { login = false })
}

/**
 * 아이디 로그인 창. 아래에서 아이디 찾기·비밀번호 찾기·회원가입으로 넘어감 (한 창 안에서 화면만 바뀜)
 */
@Composable
fun IdAuthDialog(onDismiss: () -> Unit, start: String = "login") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) {
        var c = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        c as? Activity
    }
    var page by rememberSaveable { mutableStateOf(if (start == "signup") IdPage.SignUp else IdPage.Login) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // 입력값 (화면을 오가도 아이디는 이어서)
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var smsFailed by rememberSaveable { mutableStateOf(false) }
    var byPhone by rememberSaveable { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var resetStep by rememberSaveable { mutableStateOf(0) }   // 0 확인 전, 1 문자 보냄(새 비밀번호), 2 메일 보냄

    fun go(p: IdPage) { page = p; error = null; info = null; password = ""; confirm = ""; code = ""; codeSent = false; smsFailed = false; found = null; resetStep = 0 }
    fun run(block: suspend () -> Unit) { busy = true; error = null; scope.launch { try { block() } finally { busy = false } } }
    fun toast(msg: String) = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()

    fun sendCode(resend: Boolean) {
        val a = activity ?: return
        busy = true
        error = null
        IdAccount.sendCode(a, phone, resend,
            onSent = { busy = false; codeSent = true; info = "인증번호를 문자로 보냈어요" },
            onFail = { busy = false; error = it; smsFailed = true },
        )
    }

    val field = Modifier.fillMaxWidth()
    val pwKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(when (page) { IdPage.Login -> "로그인"; IdPage.SignUp -> "회원가입"; IdPage.FindId -> "아이디 찾기"; IdPage.Reset -> "비밀번호 찾기" })
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (page) {
                    IdPage.Login -> {
                        OutlinedTextField(username, { username = it }, field, label = { Text("아이디") }, singleLine = true)
                        OutlinedTextField(password, { password = it }, field, label = { Text("비밀번호") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = pwKeyboard)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(enabled = !busy, onClick = { go(IdPage.FindId) }) { Text("아이디 찾기", fontSize = 13.sp) }
                            TextButton(enabled = !busy, onClick = { go(IdPage.Reset) }) { Text("비밀번호 찾기", fontSize = 13.sp) }
                            TextButton(enabled = !busy, onClick = { go(IdPage.SignUp) }) { Text("회원가입", fontSize = 13.sp) }
                        }
                    }

                    IdPage.SignUp -> {
                        OutlinedTextField(username, { username = it.lowercase() }, field, label = { Text("아이디 (영어 소문자·숫자 4~20자)") }, singleLine = true)
                        OutlinedTextField(password, { password = it }, field, label = { Text("비밀번호 (영어+숫자 ${IdAccount.MIN_PASSWORD}자 이상)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = pwKeyboard)
                        OutlinedTextField(confirm, { confirm = it }, field, label = { Text("비밀번호 확인") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = pwKeyboard)
                        OutlinedTextField(email, { email = it }, field, label = { Text("이메일") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        OutlinedTextField(phone, { phone = it; codeSent = false; code = "" }, field, label = { Text("전화번호") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(enabled = !busy && activity != null && PhoneNumber.toE164(phone) != null, onClick = { sendCode(codeSent) }) {
                                Text(if (codeSent) "인증번호 다시 받기" else "인증번호 받기")
                            }
                        }
                        if (codeSent) OutlinedTextField(code, { code = it.filter(Char::isDigit).take(PhoneNumber.CODE_LENGTH) }, field,
                            label = { Text("인증번호 ${PhoneNumber.CODE_LENGTH}자리") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        Text(
                            if (smsFailed) "문자를 보내지 못하면 전화번호 인증 없이 가입할 수 있어요. 이때는 비밀번호를 이메일로만 바꿀 수 있어요."
                            else "전화번호는 문자로 인증해요. 인증한 번호로 아이디를 찾고 비밀번호를 바꿀 수 있어요.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    IdPage.FindId -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(byPhone, { byPhone = true; found = null }, label = { Text("전화번호") })
                            FilterChip(!byPhone, { byPhone = false; found = null }, label = { Text("이메일") })
                        }
                        if (byPhone) OutlinedTextField(phone, { phone = it }, field, label = { Text("가입한 전화번호") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                        else OutlinedTextField(email, { email = it }, field, label = { Text("가입한 이메일") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        found?.let { ids ->
                            Text(
                                if (ids.isEmpty()) "이 ${if (byPhone) "전화번호" else "이메일"}로 가입한 아이디가 없어요" else "가입한 아이디: " + ids.joinToString(", "),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (ids.isNotEmpty()) TextButton(onClick = { go(IdPage.Login) }) { Text("로그인하러 가기") }
                        }
                    }

                    IdPage.Reset -> {
                        if (resetStep == 0) {
                            OutlinedTextField(username, { username = it.lowercase() }, field, label = { Text("아이디") }, singleLine = true)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(byPhone, { byPhone = true }, label = { Text("전화번호") })
                                FilterChip(!byPhone, { byPhone = false }, label = { Text("이메일") })
                            }
                            if (byPhone) OutlinedTextField(phone, { phone = it }, field, label = { Text("가입한 전화번호") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                            else OutlinedTextField(email, { email = it }, field, label = { Text("가입한 이메일") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                            Text(
                                if (byPhone) "아이디와 전화번호가 맞으면 문자로 인증번호를 보내요. 인증하면 바로 새 비밀번호를 정해요."
                                else "아이디와 이메일이 맞으면 비밀번호를 바꾸는 링크를 메일로 보내요.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (resetStep == 1) {
                            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(PhoneNumber.CODE_LENGTH) }, field,
                                label = { Text("인증번호 ${PhoneNumber.CODE_LENGTH}자리") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                            OutlinedTextField(password, { password = it }, field, label = { Text("새 비밀번호 (영어+숫자 ${IdAccount.MIN_PASSWORD}자 이상)") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = pwKeyboard)
                            OutlinedTextField(confirm, { confirm = it }, field, label = { Text("새 비밀번호 확인") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = pwKeyboard)
                            TextButton(enabled = !busy && activity != null, onClick = { sendCode(true) }) { Text("인증번호 다시 받기") }
                        }
                        if (resetStep == 2) Text("${email.trim()} 로 비밀번호를 바꾸는 링크를 보냈어요. 메일의 링크에서 새 비밀번호를 정한 뒤 아이디로 로그인해 주세요.")
                    }
                }
                info?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            when (page) {
                IdPage.Login -> TextButton(enabled = !busy && username.isNotBlank() && password.isNotEmpty(), onClick = {
                    run {
                        val msg = IdAccount.login(context, username, password)
                        if (msg == null) { toast("로그인했어요"); onDismiss() } else error = msg
                    }
                }) { Text(if (busy) "로그인 중…" else "로그인") }

                IdPage.SignUp -> TextButton(enabled = !busy && (code.length == PhoneNumber.CODE_LENGTH || smsFailed), onClick = {
                    IdAccount.validateSignUp(username, password, confirm, email, phone)?.let { error = it; return@TextButton }
                    run {
                        val msg = IdAccount.signUp(context, username, password, email, phone, code.takeIf { codeSent && it.isNotEmpty() })
                        if (IdAccount.me.value.username != null) { toast(msg ?: "가입했어요. 환영해요!"); onDismiss() } else error = msg
                    }
                }) { Text(if (busy) "가입 중…" else "가입") }

                IdPage.FindId -> TextButton(enabled = !busy, onClick = {
                    run {
                        IdAccount.find(phone.takeIf { byPhone }, email.takeIf { !byPhone })
                            .onSuccess { found = it }.onFailure { error = it.message }
                    }
                }) { Text("찾기") }

                IdPage.Reset -> when (resetStep) {
                    0 -> TextButton(enabled = !busy && username.isNotBlank(), onClick = {
                        run {
                            IdAccount.checkReset(username, phone.takeIf { byPhone }, email.takeIf { !byPhone }).onSuccess { r ->
                                when {
                                    !r.ok -> error = "아이디와 ${if (byPhone) "전화번호" else "이메일"}가 맞지 않아요"
                                    byPhone && !r.phoneOk -> error = "이 아이디는 전화번호를 인증하지 않아서 이메일로만 바꿀 수 있어요"
                                    byPhone -> { resetStep = 1; sendCode(false) }
                                    else -> CloudAccount.sendPasswordReset(email).let { m -> if (m == null) resetStep = 2 else error = m }
                                }
                            }.onFailure { error = it.message }
                        }
                    }) { Text("확인") }
                    1 -> TextButton(enabled = !busy && code.length == PhoneNumber.CODE_LENGTH, onClick = {
                        run {
                            val msg = IdAccount.resetByPhone(context, username, code, password, confirm)
                            if (msg == null) { toast("비밀번호를 바꿨어요. 로그인됐어요"); onDismiss() } else error = msg
                        }
                    }) { Text("바꾸기") }
                    else -> TextButton(onClick = { go(IdPage.Login) }) { Text("로그인하러 가기") }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { if (page == IdPage.Login) onDismiss() else go(IdPage.Login) }) {
                Text(if (page == IdPage.Login) "닫기" else "뒤로")
            }
        },
    )
}
