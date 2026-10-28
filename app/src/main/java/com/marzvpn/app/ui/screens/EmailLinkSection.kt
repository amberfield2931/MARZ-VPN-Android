package com.marzvpn.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.marzvpn.app.api.EmailLinkFlow
import com.marzvpn.app.model.Account
import com.marzvpn.app.model.Identity
import com.marzvpn.app.ui.BigButton
import com.marzvpn.app.ui.BigOutlinedButton
import com.marzvpn.app.ui.ltr
import io.nekohasekai.sfa.R

/** Only the account card. Proofs are not saveable state or navigation arguments. */
@Composable
fun EmailLinkSection(account: Account, state: EmailLinkFlow.State,
                     onSend: (String) -> Unit, onConfirm: (String) -> Unit,
                     onReset: () -> Unit, onRefresh: () -> Unit, onUnlink: (String) -> Unit) {
    var expanded by remember(account.id) { mutableStateOf(false) }
    var email by remember(account.id) { mutableStateOf("") }
    var code by remember(account.id, state.email) { mutableStateOf("") }
    var removal by remember(account.id) { mutableStateOf<String?>(null) }
    if (state.busy) {
        CircularProgressIndicator()
        Text(stringResource(R.string.marz_email_link_checking))
    }
    if (state.checked) Text(stringResource(R.string.marz_email_link_checked), color = MaterialTheme.colorScheme.primary)
    val linked = account.identities.filter { it.kind == Identity.Kind.Email }
    if (linked.isNotEmpty()) {
        Text(stringResource(R.string.marz_email_link_status), style = MaterialTheme.typography.bodyMedium)
        linked.filter { it.id.isNotEmpty() }.forEach { identity ->
            TextButton(onClick = { removal = identity.id }, enabled = !state.busy) {
                Text(stringResource(R.string.marz_email_link_remove, ltr(identity.display)))
            }
        }
    } else if (!expanded) {
        BigOutlinedButton(stringResource(R.string.marz_email_link_add), { expanded = true }, enabled = !state.busy)
    } else {
        Text(stringResource(R.string.marz_email_link_hint), style = MaterialTheme.typography.bodyMedium)
        if (state.email == null) {
            OutlinedTextField(email, { email = it.trim() }, label = { Text(stringResource(R.string.marz_email_label)) },
                singleLine = true, enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            BigButton(stringResource(R.string.marz_login_email_send_code), { onSend(email) }, enabled = !state.busy && email.contains('@'))
        } else {
            Text(stringResource(R.string.marz_login_email_code_sent, ltr(state.email)))
            OutlinedTextField(code, { code = it.filter { c -> c in '0'..'9' }.take(6) },
                label = { Text(stringResource(R.string.marz_login_email_code_label)) }, singleLine = true, enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            BigButton(stringResource(R.string.marz_email_link_confirm), { onConfirm(code) }, enabled = !state.busy && code.length == 6)
            var waitSeconds by remember(state.sentAt) { mutableStateOf(60L) }
            LaunchedEffect(state.sentAt) {
                do {
                    waitSeconds = ((60_000 - (android.os.SystemClock.elapsedRealtime() - state.sentAt) + 999) / 1000).coerceAtLeast(0)
                    if (waitSeconds > 0) kotlinx.coroutines.delay(1000)
                } while (waitSeconds > 0)
            }
            TextButton(onClick = { code = "";onSend(state.email) }, enabled = !state.busy && waitSeconds == 0L) {
                Text(if (waitSeconds > 0) stringResource(R.string.marz_login_email_resend_wait, waitSeconds.toInt()) else stringResource(R.string.marz_login_email_resend))
            }
        }
        TextButton(onClick = { code = "";email = "";expanded = false;onReset() }, enabled = !state.busy) { Text(stringResource(R.string.marz_email_link_cancel)) }
    }
    if (state.error != null) {
        val text = when (state.error) {
            "email_linking_disabled", "email_owner_pilot_only", "email_login_disabled" -> stringResource(R.string.marz_email_link_unavailable)
            "identity_already_linked", "identity_belongs_to_other_account", "email_link_scope_conflict", "email_link_proof_required", "session_origin_unknown", "email_not_explicit_alias" -> stringResource(R.string.marz_email_link_conflict)
            "telegram_reauth_required", "account_changed", "telegram_profile_changed", "telegram_profile_unavailable" -> stringResource(R.string.marz_email_link_telegram)
            else -> loginError(state.error, codeEntered = state.email != null)
        }
        ErrorText(text)
    }
    if (expanded || linked.isNotEmpty() || state.error != null) {
        TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.marz_email_link_refresh)) }
    }
    if (removal != null) AlertDialog(onDismissRequest = { removal = null },
        title = { Text(stringResource(R.string.marz_email_link_remove_title)) },
        text = { Text(stringResource(R.string.marz_email_link_remove_hint)) },
        confirmButton = { TextButton(onClick = { removal?.let(onUnlink);removal = null }) { Text(stringResource(R.string.marz_email_link_remove_title)) } },
        dismissButton = { TextButton(onClick = { removal = null }) { Text(stringResource(R.string.marz_email_link_cancel)) } })
}
