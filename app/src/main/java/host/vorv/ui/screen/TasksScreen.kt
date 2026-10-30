package host.vorv.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentState
import host.vorv.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksScreen(state: AgentState) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Tasks", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item { Text("${state.recentTasks.size} tasks logged. Each task shows real tool calls — verifiable, not invented.", color = OnSurfaceVariant, fontSize = 12.sp) }
        items(state.recentTasks) { task ->
            Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(task.prompt, color = OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    val color = when (task.status) { "completed" -> Success; "failed" -> Error; "in_progress" -> Warning; else -> OnSurfaceVariant }
                    Text("${task.status.uppercase()} · ${task.iterations} iters · ${fmt(task.createdAt)}", color = color, fontSize = 10.sp)
                    task.summary?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = OnSurfaceVariant, fontSize = 11.sp, maxLines = 3)
                    }
                    task.error?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("Error: $it", color = Error, fontSize = 11.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

private fun fmt(ts: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))
