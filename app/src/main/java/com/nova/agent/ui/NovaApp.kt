package com.nova.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nova.agent.NovaViewModel
import com.nova.agent.ChatMessage
import com.nova.agent.ReasoningStep
import com.nova.agent.ui.theme.*

@Composable
fun NovaApp(vm: NovaViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            AppHeader(busy = state.busy)
            when (state.mode) {
                "chat" -> ChatScreen(state, vm::send)
                "teach" -> TeachScreen(vm::teach)
                "autolearn" -> AutoLearnScreen(state, vm)
                "code" -> CodeScreen(state, vm::send)
                "mind" -> MindScreen(state)
            }
        }
        BottomNav(
            current = state.mode,
            onChange = vm::setMode,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun AppHeader(busy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pulsing orb (simplified)
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(
                Brush.radialGradient(listOf(PrimaryLight, Primary))
            )
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("NOVA", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (busy) "Thinking…" else "Online",
                color = OnSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(50),
            color = Success.copy(alpha = 0.15f),
        ) {
            Text(
                "Live",
                color = Success,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ChatScreen(state: NovaState, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Messages
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    Text(
                        "Hi — I'm NOVA. Ask me anything about coding.",
                        color = OnSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(state.messages) { msg ->
                MessageBubble(msg)
            }
            // Live reasoning trace
            if (state.busy && state.liveTrace.isNotEmpty()) {
                item { TraceCard(state.liveTrace) }
            }
        }
        // Input bar
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message NOVA…", color = OnSurfaceVariant) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Border,
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (input.isNotBlank()) { onSend(input); input = "" } },
                enabled = input.isNotBlank() && !state.busy,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) Primary else SurfaceVariant,
        ) {
            Text(
                msg.content,
                color = if (isUser) OnPrimary else OnBackground,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun TraceCard(steps: List<ReasoningStep>) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Reasoning", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            steps.lastOrNull()?.let { s ->
                Text(s.label, color = OnBackground, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                s.detail?.let { Text(it, color = OnSurfaceVariant, fontSize = 11.sp, maxLines = 3) }
            }
        }
    }
}

@Composable
private fun TeachScreen(onTeach: (String, String) -> Unit) {
    var topic by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Teach NOVA", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Tell me something I don't know. I'll store it as a permanent lesson.", color = OnSurfaceVariant, fontSize = 13.sp)
        OutlinedTextField(
            value = topic, onValueChange = { topic = it },
            placeholder = { Text("Topic (e.g. Rust ownership)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        )
        OutlinedTextField(
            value = content, onValueChange = { content = it },
            placeholder = { Text("What should I know?") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            shape = RoundedCornerShape(16.dp),
        )
        Button(
            onClick = { if (topic.isNotBlank() && content.isNotBlank()) { onTeach(topic, content); topic = ""; content = "" } },
            enabled = topic.isNotBlank() && content.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
        ) { Text("Teach") }
    }
}

@Composable
private fun AutoLearnScreen(state: NovaState, vm: NovaViewModel) {
    var topic by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Auto-Learn", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Give me a topic — I'll search the live web, read top results, and store what I learn.", color = OnSurfaceVariant, fontSize = 13.sp)
        OutlinedTextField(
            value = topic, onValueChange = { topic = it },
            placeholder = { Text("What is new in TypeScript 5.5?") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        )
        Button(
            onClick = { if (topic.isNotBlank()) { vm.send("Research: $topic"); topic = "" } },
            enabled = topic.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
        ) { Text("Research") }
    }
}

@Composable
private fun CodeScreen(state: NovaState, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Code Studio", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Describe a task — I'll plan it, write the code, self-review, and execute it.", color = OnSurfaceVariant, fontSize = 13.sp)
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            placeholder = { Text("Write a TypeScript debounce function…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
            shape = RoundedCornerShape(16.dp),
        )
        Button(
            onClick = { if (input.isNotBlank()) { onSend(input); input = "" } },
            enabled = input.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
        ) { Text("Generate") }
        if (state.messages.isNotEmpty()) {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages.takeLast(2)) { MessageBubble(it) }
            }
        }
    }
}

@Composable
private fun MindScreen(state: NovaState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("NOVA's Mind", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Memories", state.memoryCount.toString())
                StatCard("Skills", state.skillCount.toString())
                StatCard("Total XP", state.totalXp.toString())
            }
        }
        item { Text("Skill Tree", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        items(state.skills) { skill ->
            Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(skill.name, color = OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text("Lv ${skill.level}", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (skill.xp % 100) / 100f },
                        color = Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${skill.xp} XP", color = OnSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
        if (state.evolutionEvents.isNotEmpty()) {
            item { Text("Evolution", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            items(state.evolutionEvents.take(20)) { event ->
                Surface(shape = RoundedCornerShape(8.dp), color = SurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(event.summary, color = OnBackground, fontSize = 12.sp)
                        Text(event.kind, color = Primary, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariant, modifier = Modifier.weight(1f)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(label, color = OnSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BottomNav(current: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf("chat" to "Chat", "teach" to "Teach", "autolearn" to "Learn", "code" to "Code", "mind" to "Mind")
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        color = Background,
    ) {
        Row(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEach { (key, label) ->
                val selected = current == key
                TextButton(onClick = { onChange(key) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            label,
                            color = if (selected) Primary else OnSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
