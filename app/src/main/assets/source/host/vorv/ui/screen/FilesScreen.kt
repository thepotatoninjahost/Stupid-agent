package host.vorv.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentState
import host.vorv.ui.theme.*

@Composable
fun FilesScreen(state: AgentState, onSetWorkspace: (String) -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistable permission so URI survives reboots
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                ctx.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {}
            onSetWorkspace(uri.toString())
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Files", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Pick a workspace folder. The agent can read/write/list files in this folder — and nowhere else.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
        )
        if (state.workspaceUri.isNotBlank()) {
            Surface(shape = RoundedCornerShape(12.dp), color = Success.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Workspace granted", color = Success, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(state.workspaceUri, color = OnSurfaceVariant, fontSize = 10.sp, maxLines = 2)
                }
            }
            Button(
                onClick = { launcher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) { Text("Pick a different folder") }
        } else {
            Surface(shape = RoundedCornerShape(12.dp), color = Warning.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("No workspace yet", color = Warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("File tools (file_read, file_write, file_list) will fail until you grant one.", color = OnSurfaceVariant, fontSize = 11.sp)
                }
            }
            Button(
                onClick = { launcher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) { Text("Grant workspace access") }
        }
    }
}
