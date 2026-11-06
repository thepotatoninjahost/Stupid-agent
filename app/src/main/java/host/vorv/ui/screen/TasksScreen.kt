package host.vorv.ui.screen

import androidx.compose.foundation.clickable
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

/**
 * Tasks screen (audit findings M-7 + acceptance test #8): each task card is
 * expandable to its verifiable tool-call log — real input, real output
 * preview, real duration, real exit code. When the full output was persisted
 * to a file, a button opens the complete text.
 */
@Composable
fun TasksScreen(
    state: AgentState,
    onToggleDetails: (String) -> Unit,
    onViewFullOutput: (title: String, path: String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Tasks", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item {
            Text(
                "${state.recentTasks.size} tasks logged. Tap a task to see its verifiable tool-call log — real input, real output, real duration.",
                color = OnSurfaceVariant, fontSize = 12.sp
            )
        }
        items(state.recentTasks, key = { it.id }) { task ->
            val expanded = state.expandedTaskId == task.id
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onToggleDetails(task.id) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(task.prompt, color = OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    val color = when (task.status) { "completed" -> Success; "failed" -> Error; "in_progress" -> Warning; else -> OnSurfaceVariant }
                    Text(
                        "${task.status.uppercase()} · ${task.iterations} iters · ${fmt(task.createdAt)} · ${if (expanded) "▾" else "▸"}",
                        color = color, fontSize = 10.sp
                    )
                    task.summary?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = OnSurfaceVariant, fontSize = 11.sp, maxLines = 3)
                    }
                    task.error?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("Error: $it", color = Error, fontSize = 11.sp, maxLines = 2)
                    }

                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        if (state.toolCallsForTask.isEmpty()) {
                            Text("No tool calls recorded for this task.", color = OnSurfaceVariant, fontSize = 11.sp)
                        } else {
                            Text("Tool calls (${state.toolCallsForTask.size})", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            state.toolCallsForTask.forEach { call ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Background,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row {
                                            Text("iter ${call.iteration} · ${call.toolName}",
                                                color = OnBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                if (call.exitCode == 0) "✓ ${call.durationMs}ms" else "✗ ${call.durationMs}ms",
                                                color = if (call.exitCode == 0) Success else Error,
                                                fontSize = 10.sp
                                            )
                                        }
                                        if (call.outputJson.isNotBlank()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                call.outputJson.take(300) + if (call.outputJson.length > 300) "…" else "",
                                                color = OnSurfaceVariant, fontSize = 9.sp, maxLines = 4
                                            )
                                        }
                                        call.errorMessage?.let { err ->
                                            Text("error: $err", color = Error, fontSize = 9.sp, maxLines = 2)
                                        }
                                        call.outputFilePath?.let { path ->
                                            TextButton(
                                                onClick = { onViewFullOutput("Full output — ${call.toolName} (iter ${call.iteration})", path) },
                                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                            ) {
                                                Text("View full output", color = Primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(ts: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))
