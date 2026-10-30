package com.nova.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.nova.agent.ChatMessage
import com.nova.agent.NovaState
import com.nova.agent.NovaViewModel
import com.nova.agent.ReasoningStep
import com.nova.agent.ui.theme.Background
import com.nova.agent.ui.theme.Border
import com.nova.agent.ui.theme.Error
import com.nova.agent.ui.theme.OnBackground
import com.nova.agent.ui.theme.OnPrimary
import com.nova.agent.ui.theme.OnSurfaceVariant
import com.nova.agent.ui.theme.Primary
import com.nova.agent.ui.theme.PrimaryLight
import com.nova.agent.ui.theme.Success
import com.nova.agent.ui.theme.SurfaceVariant

@Composable
fun NovaApp(vm: NovaViewModel) {
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
                "settings" -> SettingsScreen(state, vm::setApiKey)
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
            if (state.busy && state.liveTrace.isNotEmpty()) {
                item { TraceCard(state.liveTrace) }
            }
        }
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
            shape = RoundedCornerShape(16.dp),
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
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        if (state.busy && state.liveTrace.isNotEmpty()) {
            TraceCard(state.liveTrace)
        }
    }
}

@Composable
private fun CodeScreen(state: NovaState, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                StatCard("Memories", state.memoryCount.toString(), Modifier.weight(1f))
                StatCard("Skills", state.skillCount.toString(), Modifier.weight(1f))
                StatCard("XP", state.totalXp.toString(), Modifier.weight(1f))
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
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariant, modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(label, color = OnSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BottomNav(current: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        "chat" to "Chat",
        "teach" to "Teach",
        "autolearn" to "Learn",
        "code" to "Code",
        "mind" to "Mind",
        "settings" to "Setup",
    )
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

@Composable
private fun SettingsScreen(state: NovaState, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Settings", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Text(
                "Z.ai API Key",
                color = OnBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Text(
                "Required for Chat, Auto-Learn, and Code screens. Stored only on this device — never uploaded to GitHub, never sent anywhere except Z.ai.",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        item {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; saved = false },
                placeholder = { Text("Paste your Z.ai API key…") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Border,
                ),
            )
        }
        item {
            Button(
                onClick = {
                    if (key.isNotBlank()) {
                        onSave(key)
                        saved = true
                    }
                },
                enabled = key.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) {
                Text(if (saved) "Saved ✓" else "Save Key")
            }
        }
        if (state.hasApiKey) {
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = Success.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "✓ API key set. NOVA is online.",
                        color = Success,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            item {
                Button(
                    onClick = { onSave(""); key = ""; saved = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Error),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                ) { Text("Remove Key") }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "How to get a free key",
                color = OnBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Text(
                "1. Open z.ai in Chrome\n2. Sign in (Google or email)\n3. Tap your avatar → API Keys\n4. Create Standard Key → copy\n5. Paste above → Save",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}
