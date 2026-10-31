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
import com.eunho.tagohub.ui.map.MapViewModel
import kotlinx.coroutines.launch

/**
 * 이메일 가입·로그인 창. 가입하면 지금까지의 즐겨찾기·설정이 그 계정에 그대로 이어지고,
 * 로그인하면 그 계정에 저장된 것을 받아 옴. 구글 계정으로도 할 수 있음. 성공하면 [onSuccess].
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
    var signUp by rememberSaveable { mutableStateOf(true) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> String?) {
        busy = true
        error = null
        scope.launch {
            val msg = block()
            busy = false
            if (msg == null) onSuccess() else error = msg
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (signUp) "회원가입" else "로그인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = signUp, onClick = { signUp = true; error = null }, label = { Text("가입") })
                    FilterChip(selected = !signUp, onClick = { signUp = false; error = null }, label = { Text("로그인") })
                }
                OutlinedTextField(
                    email, { email = it; error = null }, label = { Text("이메일") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    password, { password = it; error = null },
                    label = { Text(if (signUp) "비밀번호 (${EmailAuth.MIN_PASSWORD}자 이상)" else "비밀번호") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                if (signUp) Text(
                    "가입하면 지금 저장된 즐겨찾기와 설정이 계정에 이어져요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!signUp) TextButton(enabled = !busy, onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val msg = CloudAccount.sendPasswordReset(email)
                            busy = false
                            if (msg == null) info = "재설정 메일을 보냈어요. 메일함을 확인해 주세요" else error = msg
                        }
                    }) { Text("비밀번호 재설정") }
                    TextButton(enabled = !busy && activity != null, onClick = {
                        val a = activity ?: return@TextButton
                        run {
                            val msg = CloudAccount.signInWithGoogle(a)
                            if (msg?.startsWith("다른 계정") == true) null else msg   // 다른 계정으로 바뀐 안내는 성공으로 봄
                        }
                    }) { Text("구글로 계속") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                run { if (signUp) CloudAccount.signUpWithEmail(context, email, password) else CloudAccount.signInWithEmail(context, email, password) }
            }) { Text(if (busy) "처리 중…" else if (signUp) "가입" else "로그인") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("취소") } },
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
