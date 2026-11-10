package app.cockpitmanager.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.cockpitmanager.cockpit.ServerUrl
import app.cockpitmanager.data.ServerConfig
import app.cockpitmanager.ui.components.Hint
import app.cockpitmanager.ui.components.LabeledSwitch
import app.cockpitmanager.ui.components.PageScaffold
import app.cockpitmanager.ui.components.SectionCard

/** Problems with an address as typed, or null when it's usable. */
fun addressProblem(address: String): String? {
    if (address.isBlank()) return null
    val url = ServerUrl.parse(address) ?: return "That doesn't look like an address"
    if (!url.cleartextAllowed) return "Use https:// — plain http:// would send your password unencrypted over the internet"
    return null
}

@Composable
fun ServerEditScreen(
    initial: ServerConfig?,
    hasSavedPassword: Boolean,
    onSave: (ServerConfig, String?, Boolean) -> Unit,
    onForgetCertificate: (ServerConfig) -> Unit,
    onBack: () -> Unit,
) {
    val base = initial ?: ServerConfig()
    var address by rememberSaveable { mutableStateOf(base.address) }
    var user by rememberSaveable { mutableStateOf(base.user) }
    var name by rememberSaveable { mutableStateOf(base.name) }
    var password by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(initial == null || hasSavedPassword) }
    var admin by rememberSaveable { mutableStateOf(base.adminAccess) }
    var pinned by rememberSaveable { mutableStateOf(base.pinnedSha256) }

    val problem = addressProblem(address)
    val valid = address.isNotBlank() && problem == null && user.isNotBlank()
    val parsed = ServerUrl.parse(address)
    val save = {
        if (valid) {
            val config = base.copy(
                name = name.trim(),
                address = address.trim(),
                user = user.trim(),
                adminAccess = admin,
                // A different host means a different certificate.
                pinnedSha256 = pinned?.takeIf { initial?.url?.host == parsed?.host && initial?.url?.port == parsed?.port },
            )
            onSave(config, password.ifEmpty { null }, remember)
            onBack()
        }
    }

    PageScaffold(
        title = if (initial == null) "Add server" else "Edit ${initial.title}",
        onBack = onBack,
        actions = { TextButton(onClick = save, enabled = valid) { Text("Save") } },
    ) {
        SectionCard("Server") {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address") },
                placeholder = { Text("server.local") },
                singleLine = true,
                isError = problem != null,
                supportingText = {
                    Text(
                        problem ?: parsed?.let { "Connects to ${it.display}" } ?: "A host name or IP address. Cockpit's port 9090 is assumed.",
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                placeholder = { Text(parsed?.host ?: "Home server") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SectionCard("Account") {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it.trim() },
                label = { Text("User name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                supportingText = { Text("Your Linux account on the server. Cockpit doesn't allow root by default.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (hasSavedPassword) "New password (optional)" else "Password (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                supportingText = { Text(if (hasSavedPassword) "A password is saved. Leave empty to keep it." else "Leave empty to be asked when you connect.") },
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledSwitch(
                title = "Remember password",
                subtitle = "Encrypted with a key that never leaves this phone",
                checked = remember,
                onCheckedChange = { remember = it },
            )
            LabeledSwitch(
                title = "Administrative access",
                subtitle = "Use your password for sudo, to manage services, read all logs and restart the server. Like \"Reuse my password for privileged tasks\" on Cockpit's sign-in page.",
                checked = admin,
                onCheckedChange = { admin = it },
            )
        }
        pinned?.let { sha ->
            SectionCard("Trusted certificate") {
                Hint("You chose to trust this server's own certificate. If it changes, you'll be asked again.")
                SelectionContainer {
                    Text(sha, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
                OutlinedButton(onClick = {
                    pinned = null
                    initial?.let(onForgetCertificate)
                }) { Text("Forget certificate") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Button(onClick = save, enabled = valid) { Text(if (initial == null) "Add server" else "Save") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint("Cockpit must be running on the server: sudo systemctl enable --now cockpit.socket, and port 9090 open in the firewall (sudo firewall-cmd --add-service=cockpit --permanent && sudo firewall-cmd --reload).")
        }
        Spacer(Modifier.height(8.dp).width(1.dp))
    }
}
