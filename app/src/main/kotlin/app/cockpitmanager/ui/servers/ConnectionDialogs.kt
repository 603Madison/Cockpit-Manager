package app.cockpitmanager.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.cockpitmanager.cockpit.CertificateInfo
import app.cockpitmanager.cockpit.LoginStep
import app.cockpitmanager.data.ServerConfig
import app.cockpitmanager.ui.ConnectionState
import app.cockpitmanager.ui.components.CockpitIcons
import java.text.DateFormat
import java.util.Date

/** The dialog for whatever the sign-in is waiting on. */
@Composable
fun ConnectionDialogs(
    state: ConnectionState,
    onPassword: (ServerConfig, String, Boolean) -> Unit,
    onTrust: (ServerConfig, CertificateInfo) -> Unit,
    onAnswer: (String) -> Unit,
    onRetry: (ServerConfig) -> Unit,
    onEdit: (ServerConfig) -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        is ConnectionState.Connecting -> ProgressDialog(state.server, state.step, onCancel)
        is ConnectionState.NeedsPassword -> PasswordDialog(state.server, state.error, onPassword, onCancel)
        is ConnectionState.NeedsCertificate -> CertificateDialog(state.server, state.info, state.changed, onTrust, onCancel)
        is ConnectionState.NeedsAnswer -> PromptDialog(state.server, state.prompt, onAnswer, onCancel)
        is ConnectionState.Failed -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Couldn't connect to ${state.server.title}") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { onRetry(state.server) }) { Text("Try again") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onCancel(); onEdit(state.server) }) { Text("Edit server") }
                    TextButton(onClick = onCancel) { Text("Close") }
                }
            },
        )
        else -> Unit
    }
}

@Composable
private fun ProgressDialog(server: ServerConfig, step: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(server.title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text("$step…", style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(server: ServerConfig, error: String?, onSubmit: (ServerConfig, String, Boolean) -> Unit, onCancel: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = { if (password.isNotEmpty()) onSubmit(server, password, remember) }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(CockpitIcons.Key, contentDescription = null) },
        title = { Text("Sign in to ${server.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Password for ${server.subtitle}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Column {
                        Text("Remember password", style = MaterialTheme.typography.bodyMedium)
                        Text("Encrypted on this phone only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = submit, enabled = password.isNotEmpty()) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PromptDialog(server: ServerConfig, prompt: LoginStep.Prompt, onAnswer: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(prompt.id) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(prompt.id) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(CockpitIcons.Shield, contentDescription = null) },
        title = { Text(server.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                prompt.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(prompt.prompt.trimEnd(':', ' ').ifBlank { "Answer" }) },
                    singleLine = true,
                    isError = prompt.error != null,
                    supportingText = prompt.error?.let { { Text(it) } },
                    visualTransformation = if (prompt.echo) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (prompt.echo) KeyboardType.Text else KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onAnswer(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { Button(onClick = { onAnswer(text) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun CertificateDialog(server: ServerConfig, info: CertificateInfo, changed: Boolean, onTrust: (ServerConfig, CertificateInfo) -> Unit, onCancel: () -> Unit) {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(CockpitIcons.Shield, contentDescription = null, tint = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(if (changed) "Certificate changed" else "Trust this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (changed) {
                        "${server.title} presented a different certificate from the one you trusted before. That's expected if Cockpit's certificate was renewed or replaced, but it could also mean someone is intercepting the connection."
                    } else {
                        "${server.title} uses a certificate Android doesn't recognize. Cockpit creates a self-signed one when it's installed, so this is normal the first time."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "To be sure, compare this fingerprint with the one on the server:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        "sudo openssl x509 -noout -fingerprint -sha256 -in /etc/cockpit/ws-certs.d/0-self-signed.cert",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    )
                }
                Text("SHA-256", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(info.sha256, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
                Text(
                    buildString {
                        append("Issued to ${CertificateInfo.commonName(info.subject) ?: info.subject}")
                        if (!info.selfSigned) append(" by ${CertificateInfo.commonName(info.issuer) ?: info.issuer}") else append(" (self-signed)")
                        append(". Valid until ${date.format(Date(info.notAfterMillis))}.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onTrust(server, info) },
                colors = if (changed) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(if (changed) "Trust new certificate" else "Trust and connect") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
