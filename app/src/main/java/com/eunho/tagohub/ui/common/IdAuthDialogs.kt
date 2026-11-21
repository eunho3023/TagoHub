package com.eunho.tagohub.ui.common

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.eunho.tagohub.data.MapEdits
import com.eunho.tagohub.data.PhoneNumber
import kotlinx.coroutines.launch

/** 아이디 회원 창의 화면들 */
/** 회원가입 이메일 뒷자리 고르기 (웹 account.js EMAIL_DOMAINS 와 같음). 빈 글자 = 직접 입력 */
internal val EMAIL_DOMAINS = listOf("naver.com", "gmail.com", "daum.net", "hanmail.net", "kakao.com", "nate.com", "")

/** "gidrl" + "naver.com" → "gidrl@naver.com" (앞자리에 @ 가 있거나 뒷자리가 비었으면 앞자리 그대로) */
internal fun joinEmail(id: String, domain: String): String {
    val a = id.trim()
    val d = domain.trim().removePrefix("@")
    return if ('@' in a || d.isEmpty()) a else "$a@$d"
}

private enum class IdPage { Login, SignUp, FindId, Reset, Admin }

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
 * 아이디 로그인 화면 (따로 된 전체 화면). 아래에서 아이디 찾기·비밀번호 찾기·회원가입으로 넘어감 (뒤로 가기는 로그인 화면으로)
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
    val isAdmin by MapEdits.admin.collectAsStateWithLifecycle()
    var resetStep by rememberSaveable { mutableStateOf(0) }
    // 회원가입 이메일: 앞자리 + 뒷자리 고르기(직접 넣기). 앞 화면에서 넣은 이메일이 있으면 나눠 이어 씀
    var emailId by rememberSaveable { mutableStateOf("") }
    var emailDomain by rememberSaveable { mutableStateOf(EMAIL_DOMAINS.first()) }
    var emailCustom by rememberSaveable { mutableStateOf("") }
    var domainOpen by remember { mutableStateOf(false) }
    /** 아이디 중복확인 결과 (확인한 아이디, 쓸 수 있으면 true · 이미 쓰면 false · 확인 못 하면 null) */
    var idCheck by remember { mutableStateOf<Pair<String, Boolean?>?>(null) }
    fun signupEmail(): String = joinEmail(emailId, emailDomain.ifEmpty { emailCustom })   // 0 확인 전, 1 문자 보냄(새 비밀번호), 2 메일 보냄

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

    val title = when (page) { IdPage.Login -> "로그인"; IdPage.SignUp -> "회원가입"; IdPage.FindId -> "아이디 찾기"; IdPage.Reset -> "비밀번호 찾기"; IdPage.Admin -> "관리자로 로그인" }
    fun back() { if (!busy) { if (page == IdPage.Login) onDismiss() else go(IdPage.Login) } }

    // 따로 된 전체 화면 창: 위에 뒤로·제목, 가운데 입력칸, 아래 큰 버튼
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = !busy, onClick = { back() }) {
                        Icon(if (page == IdPage.Login) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (page == IdPage.Login) "닫기" else "뒤로")
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    if (page == IdPage.Login) Text("TagoHub 아이디로 로그인해요", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    when (page) {
                        IdPage.Login -> {
                            OutlinedTextField(username, { username = it }, field, label = { Text("아이디") }, singleLine = true)
                            OutlinedTextField(password, { password = it }, field, label = { Text("비밀번호") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = pwKeyboard)
                        }

                        // 관리자 로그인: 관리자 아이디(서버 비밀 값 DEV_IDS)와 비밀번호. 관리자인지는 서버가 정함
                        IdPage.Admin -> {
                            if (isAdmin) Text("관리자 모드가 켜져 있어요. 노선·정류장·역 화면에서 지도 고치기를 할 수 있어요.")
                            else {
                                OutlinedTextField(username, { username = it }, field, label = { Text("관리자 아이디") }, singleLine = true)
                                OutlinedTextField(password, { password = it }, field, label = { Text("비밀번호") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = pwKeyboard)
                                Text("관리자 아이디로 로그인하면 이 기기에서 지도 고치기(노선 모양·정류장·역 위치)를 할 수 있어요.",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        IdPage.SignUp -> {
                            LaunchedEffect(Unit) {
                                if (email.isNotBlank() && emailId.isEmpty()) {
                                    val parts = email.trim().split("@", limit = 2)
                                    emailId = parts[0]
                                    parts.getOrNull(1)?.let { d -> if (d in EMAIL_DOMAINS) emailDomain = d else { emailDomain = ""; emailCustom = d } }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(username, { username = it.lowercase() }, Modifier.weight(1f), label = { Text("아이디 (영어 소문자·숫자 4~20자)") }, singleLine = true)
                                OutlinedButton(enabled = !busy && IdAccount.normUsername(username) != null, onClick = {
                                    val u = IdAccount.normUsername(username) ?: return@OutlinedButton
                                    scope.launch { idCheck = u to IdAccount.isFree(u) }
                                }) { Text("중복확인") }
                            }
                            val u = IdAccount.normUsername(username)
                            val idMsg: Pair<String, Boolean>? = when {
                                username.isEmpty() -> null
                                u == null -> "영어 소문자·숫자·밑줄(_)로 4~20자예요" to false
                                idCheck?.first != u -> "중복확인을 눌러 주세요" to true
                                idCheck?.second == true -> "쓸 수 있는 아이디예요" to true
                                idCheck?.second == false -> "이미 쓰는 아이디예요" to false
                                else -> "지금은 확인할 수 없어요. 잠시 뒤에 다시 눌러 주세요" to false
                            }
                            idMsg?.let { (t, ok) -> Text(t, fontSize = 12.sp, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                            OutlinedTextField(password, { password = it }, field, label = { Text("비밀번호 (영어+숫자 ${IdAccount.MIN_PASSWORD}자 이상)") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = pwKeyboard)
                            OutlinedTextField(confirm, { confirm = it }, field, label = { Text("비밀번호 확인") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = pwKeyboard)
                            // 비밀번호 확인: 넣는 대로 같은지 다른지
                            if (confirm.isNotEmpty()) Text(if (confirm == password) "비밀번호가 같아요" else "비밀번호가 달라요", fontSize = 12.sp,
                                color = if (confirm == password) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            // 이메일: 앞자리 @ 뒷자리(고르기, 직접 입력)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(emailId, { v ->
                                    // 앞자리에 @ 를 넣으면 뒷자리를 나눠 받음
                                    if ('@' in v) {
                                        val parts = v.split("@", limit = 2)
                                        emailId = parts[0]
                                        val d = parts[1]
                                        if (d in EMAIL_DOMAINS) emailDomain = d else { emailDomain = ""; emailCustom = d }
                                    } else emailId = v
                                }, Modifier.weight(1f), label = { Text("이메일") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                                Text("@", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box {
                                    OutlinedButton(onClick = { domainOpen = true }) { Text(emailDomain.ifEmpty { "직접 입력" }) }
                                    DropdownMenu(expanded = domainOpen, onDismissRequest = { domainOpen = false }) {
                                        EMAIL_DOMAINS.forEach { d ->
                                            DropdownMenuItem(text = { Text(d.ifEmpty { "직접 입력" }) }, onClick = { emailDomain = d; domainOpen = false })
                                        }
                                    }
                                }
                            }
                            if (emailDomain.isEmpty()) OutlinedTextField(emailCustom, { emailCustom = it.trim().removePrefix("@") }, field,
                                label = { Text("이메일 뒷자리 (예: company.co.kr)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                            OutlinedTextField(phone, { phone = it; codeSent = false; code = "" }, field, label = { Text("전화번호") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Spacer(Modifier.height(8.dp))
                    when (page) {
                        IdPage.Login -> Button(modifier = field.height(52.dp), enabled = !busy && username.isNotBlank() && password.isNotEmpty(), onClick = {
                            run {
                                val msg = IdAccount.login(context, username, password)
                                if (msg == null) { toast("로그인했어요"); onDismiss() } else error = msg
                            }
                        }) { Text(if (busy) "로그인 중…" else "로그인") }

                        IdPage.SignUp -> Button(modifier = field.height(52.dp), enabled = !busy && (code.length == PhoneNumber.CODE_LENGTH || smsFailed), onClick = {
                            val mail = signupEmail()
                            email = mail
                            IdAccount.validateSignUp(username, password, confirm, mail, phone)?.let { error = it; return@Button }
                            run {
                                // 중복확인을 안 했거나 아이디를 바꿨으면 지금 확인
                                val u = IdAccount.normUsername(username)
                                if (u != null && idCheck?.first != u) idCheck = u to IdAccount.isFree(u)
                                if (u != null && idCheck?.second == false) { error = "이미 쓰는 아이디예요"; return@run }
                                val msg = IdAccount.signUp(context, username, password, mail, phone, code.takeIf { codeSent && it.isNotEmpty() })
                                if (IdAccount.me.value.username != null) { toast(msg ?: "가입했어요. 환영해요!"); onDismiss() } else error = msg
                            }
                        }) { Text(if (busy) "가입 중…" else "가입") }

                        IdPage.Admin -> if (isAdmin) {
                            Button(modifier = field.height(52.dp), enabled = !busy, onClick = { onDismiss() }) { Text("닫기") }
                            TextButton(modifier = field, enabled = !busy, onClick = {
                                run {
                                    if (MapEdits.adminKeySet()) MapEdits.setAdminKey(context, "")
                                    if (IdAccount.me.value.dev) activity?.let { CloudAccount.signOut(it) }
                                    toast("관리자 모드를 껐어요"); go(IdPage.Admin)
                                }
                            }) { Text("관리자 로그아웃") }
                        } else Button(modifier = field.height(52.dp), enabled = !busy && username.isNotBlank() && password.isNotEmpty(), onClick = {
                            run {
                                val msg = IdAccount.login(context, username, password)
                                when {
                                    msg != null -> error = msg
                                    !IdAccount.me.value.dev -> { activity?.let { CloudAccount.signOut(it) }; error = "관리자 아이디가 아니에요" }
                                    else -> { toast("관리자로 로그인했어요"); onDismiss() }
                                }
                            }
                        }) { Text(if (busy) "로그인 중…" else "관리자로 로그인") }

                        IdPage.FindId -> Button(modifier = field.height(52.dp), enabled = !busy, onClick = {
                            run {
                                IdAccount.find(phone.takeIf { byPhone }, email.takeIf { !byPhone })
                                    .onSuccess { found = it }.onFailure { error = it.message }
                            }
                        }) { Text("찾기") }

                        IdPage.Reset -> when (resetStep) {
                            0 -> Button(modifier = field.height(52.dp), enabled = !busy && username.isNotBlank(), onClick = {
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
                            1 -> Button(modifier = field.height(52.dp), enabled = !busy && code.length == PhoneNumber.CODE_LENGTH, onClick = {
                                run {
                                    val msg = IdAccount.resetByPhone(context, username, code, password, confirm)
                                    if (msg == null) { toast("비밀번호를 바꿨어요. 로그인됐어요"); onDismiss() } else error = msg
                                }
                            }) { Text("바꾸기") }
                            else -> Button(modifier = field.height(52.dp), onClick = { go(IdPage.Login) }) { Text("로그인하러 가기") }
                        }
                    }
                    if (page == IdPage.Login) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(enabled = !busy, onClick = { go(IdPage.FindId) }) { Text("아이디 찾기", fontSize = 13.sp) }
                            TextButton(enabled = !busy, onClick = { go(IdPage.Reset) }) { Text("비밀번호 찾기", fontSize = 13.sp) }
                            TextButton(enabled = !busy, onClick = { go(IdPage.SignUp) }) { Text("회원가입", fontSize = 13.sp) }
                        }
                        TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { go(IdPage.Admin) }) {
                            Text(if (isAdmin) "관리자 모드 켜짐" else "관리자로 로그인", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
