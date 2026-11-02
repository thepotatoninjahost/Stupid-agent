package host.vorv.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentState
import host.vorv.ui.theme.*

@Composable
fun MemoryScreen(state: AgentState, onTeach: (String, String) -> Unit) {
    var topic by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Memory", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item {
            Text("${state.memoryCount} facts stored · ${state.skillCount} skills tracked", color = OnSurfaceVariant, fontSize = 12.sp)
        }
        item {
            Text("Teach Vorv", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        item {
            OutlinedTextField(
                value = topic, onValueChange = { topic = it },
                placeholder = { Text("Topic — e.g. 'user prefers Vim'") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = content, onValueChange = { content = it },
                placeholder = { Text("What should Vorv remember?") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                shape = RoundedCornerShape(12.dp),
            )
        }
        item {
            Button(
                onClick = { if (topic.isNotBlank() && content.isNotBlank()) { onTeach(topic, content); topic = ""; content = "" } },
                enabled = topic.isNotBlank() && content.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) { Text("Store") }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { Text("Skills", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        items(state.skills) { skill ->
            Surface(shape = RoundedCornerShape(10.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row {
                        Text(skill.name, color = OnBackground, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text("${skill.successCount}✓ ${skill.failCount}✗", color = OnSurfaceVariant, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (skill.xp % 100) / 100f },
                        color = Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${skill.xp} XP · Lv ${skill.level}", color = OnSurfaceVariant, fontSize = 9.sp)
                }
            }
        }
    }
}
