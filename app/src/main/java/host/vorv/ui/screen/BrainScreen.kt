package host.vorv.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentState
import host.vorv.learn.BrainReport
import host.vorv.ui.theme.*

/**
 * Step 9 (screen): the brain, told plainly.
 *
 * Every number on this screen is read back from disk or from the run log. When
 * something has not happened, the screen says that in words instead of showing
 * a zero that could be read as "fine". Training and rollback both report what
 * they did — including a refusal.
 */
@Composable
fun BrainScreen(
    state: AgentState,
    onTrainNow: () -> Unit,
    onRollback: () -> Unit,
    onRefresh: () -> Unit,
) {
    val view = state.brain
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Brain", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item {
            Text(
                "The on-device model learns from your own ledger. It is judged by a fixed test suite, " +
                    "and a new generation only serves if it wins.",
                color = OnSurfaceVariant, fontSize = 12.sp,
            )
        }
        if (state.brainNote.isNotBlank()) {
            item {
                Surface(shape = RoundedCornerShape(10.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(state.brainNote, color = OnBackground, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                }
            }
        }
        item {
            Text(view?.headline ?: "Reading the brain…", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTrainNow,
                    enabled = !state.brainBusy && state.ledgerRows > 0,
                    shape = RoundedCornerShape(50),
                ) { Text(if (state.brainBusy) "Training…" else "Train now") }
                OutlinedButton(onClick = onRollback, enabled = view?.rollbackTarget != null, shape = RoundedCornerShape(50)) {
                    Text("Go back one")
                }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
        item {
            Text(
                when {
                    state.ledgerRows == 0 -> "The ledger is empty. Talk to Vorv first; each turn becomes a row."
                    state.brainBusy -> "A run is going. It keeps going while the phone charges."
                    else -> "${state.ledgerRows} ledger rows (${state.ledgerExamples} answerable)."
                },
                color = OnSurfaceVariant, fontSize = 12.sp,
            )
        }
        item {
            Text(view?.rollbackNote ?: "", color = OnSurfaceVariant, fontSize = 12.sp)
        }
        if (view != null && view.chart.size >= 2) {
            item {
                Text(BrainReport.chartLine(view.chart), color = OnBackground, fontSize = 12.sp)
            }
            item { LossChart(view.chart) }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text("Generations", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (view == null || view.generations.isEmpty()) {
            item { Text("None yet. The first run makes generation 1.", color = OnSurfaceVariant, fontSize = 12.sp) }
        } else {
            items(view.generations) { gen ->
                Surface(shape = RoundedCornerShape(10.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(BrainReport.generationLine(gen), color = OnBackground, fontSize = 12.sp)
                        if (!gen.accepted) {
                            Text(gen.decision, color = Warning, fontSize = 11.sp)
                        } else if (gen.serving) {
                            Text(gen.decision, color = Success, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text("Training runs", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (view == null || view.runs.isEmpty()) {
            item { Text("No run has happened yet.", color = OnSurfaceVariant, fontSize = 12.sp) }
        } else {
            items(view.runs) { run ->
                Surface(shape = RoundedCornerShape(10.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${run.state.uppercase()} · ${run.corpusRows} rows · ${run.seconds?.let { "%.0f s".format(it) } ?: "no time"}",
                            color = if (run.state == "promoted") Success else OnSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(run.message, color = OnBackground, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Held-out loss per accepted generation; lower is better and the newest is on the right. */
@Composable
private fun LossChart(chart: List<Pair<Int, Double>>) {
    val top = chart.maxOf { it.second }
    val bottom = chart.minOf { it.second }
    val span = (top - bottom).takeIf { it > 1e-6 } ?: 1.0
    Row(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
    ) {
        chart.forEach { (id, loss) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((12 + 56 * ((top - loss) / span)).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.verticalGradient(listOf(Primary, Primary.copy(alpha = 0.4f))),
                        ),
                )
                Text("$id", color = OnSurfaceVariant, fontSize = 9.sp)
            }
        }
    }
}
