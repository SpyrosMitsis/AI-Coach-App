package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import com.workoutmaker.app.ui.components.GhostButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.ChatMessage
import com.workoutmaker.app.data.CoachChatRequest
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.ui.collectAsStateSafe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

private const val GREETING =
    "Hey! I'm your coach — and I can see your real data. Ask me things like " +
        "“how's my fitness looking?”, “plan my week”, “am I overtraining?”, or " +
        "“set my goal race to the Berlin marathon on 2026-09-27”. I'll check your " +
        "numbers, then plan, generate, or adjust your training for you."

// Live progress line while the agentic loop runs ("checking your fitness…").
internal fun friendlyToolProgress(tool: String): String = when (tool) {
    "get_fitness" -> "Checking your fitness…"
    "get_recent_activities" -> "Reviewing recent activities…"
    "get_planned_week" -> "Looking at your week…"
    "get_strength_summary" -> "Reviewing your lifting…"
    "get_profile" -> "Checking your profile…"
    "get_readiness" -> "Checking today's readiness…"
    "get_execution_analysis" -> "Reviewing how recent sessions went…"
    "plan_week" -> "Planning your week (this can take ~30s)…"
    "generate_workout" -> "Creating your workout…"
    "move_workout" -> "Moving the session…"
    "set_goal_race" -> "Setting your goal race…"
    "remember" -> "Noting that down…"
    else -> "Working…"
}

// Maps tool names the agentic coach used into a friendly "what I just did" note.
private fun friendlyTools(tools: List<String>): String {
    val label = { t: String ->
        when (t) {
            "get_fitness" -> "checked your fitness"
            "get_recent_activities" -> "reviewed recent activities"
            "get_planned_week" -> "looked at your week"
            "get_strength_summary" -> "reviewed your lifting"
            "get_profile" -> "checked your profile"
            "plan_week" -> "planned your week"
            "generate_workout" -> "created a workout"
            "get_readiness" -> "checked your readiness"
            "get_execution_analysis" -> "reviewed your execution"
            "move_workout" -> "moved a session"
            "set_goal_race" -> "set your goal race"
            "remember" -> "noted that for next time"
            else -> t
        }
    }
    return tools.distinct().joinToString(" · ", transform = label)
}

@HiltViewModel
class CoachViewModel @Inject constructor(private val repo: WorkoutRepository) : ViewModel() {
    val messages = MutableStateFlow(listOf(ChatMessage("assistant", GREETING)))
    val sending = MutableStateFlow(false)
    val banner = MutableStateFlow<String?>(null)
    // Live tool-progress line while the agentic loop runs.
    val liveStatus = MutableStateFlow<String?>(null)
    // After the coach changes the calendar: this week's sessions for the result card.
    val actionWeek = MutableStateFlow<List<com.workoutmaker.app.data.PlannedWorkout>?>(null)
    // Contextual conversation starters, built from the cached dashboard.
    val suggestions = MutableStateFlow(listOf("How's my fitness looking?", "Plan my week"))
    private var conversationId: String? = null

    init {
        viewModelScope.launch {
            val cached = runCatching { repo.cachedDailySummary() }.getOrNull()?.first
            val chips = mutableListOf("How's my fitness looking?")
            val today = cached?.today_workout
            if (today?.workout_json != null && !today.completed) {
                chips += "Explain today's workout"
            } else if (today == null) {
                chips += "Make me a workout for today"
            }
            chips += "Plan my week"
            cached?.goal?.let { g ->
                g.weeks_to_goal?.let { w -> chips += "Am I on track for ${g.goal} ($w weeks out)?" }
            }
            chips += "How did my last workouts go?"
            suggestions.value = chips.take(5)
        }
    }

    fun dismissActionCard() { actionWeek.value = null }

    // After plan_week / generate_workout / move_workout: show what's actually on
    // the calendar now, fetched fresh from the source of truth.
    private fun loadActionWeek() = viewModelScope.launch {
        runCatching {
            val monday = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY)
            val sunday = monday.plusDays(6)
            repo.plannedWorkouts(monday.toString())
                .filter { it.date <= sunday.toString() }
                .sortedBy { it.date }
        }.onSuccess { actionWeek.value = it }
    }

    // Chat history
    val conversations = MutableStateFlow<List<com.workoutmaker.app.data.CoachConversation>>(emptyList())
    val showHistory = MutableStateFlow(false)

    fun openHistory() {
        showHistory.value = true
        viewModelScope.launch {
            runCatching { repo.coachConversations() }.onSuccess { list ->
                conversations.value = list.sortedWith(
                    compareByDescending<com.workoutmaker.app.data.CoachConversation> { it.pinned }
                        .thenByDescending { it.updated_at ?: "" },
                )
            }
        }
    }
    fun closeHistory() { showHistory.value = false }

    fun deleteConversation(c: com.workoutmaker.app.data.CoachConversation) {
        conversations.value = conversations.value.filterNot { it.id == c.id }
        if (conversationId == c.id) newChat()
        viewModelScope.launch { runCatching { repo.deleteCoachConversation(c.id) } }
    }

    fun togglePin(c: com.workoutmaker.app.data.CoachConversation) {
        val next = !c.pinned
        // Optimistic: flip locally and re-sort (pinned first, then by updated_at).
        conversations.value = conversations.value
            .map { if (it.id == c.id) it.copy(pinned = next) else it }
            .sortedWith(compareByDescending<com.workoutmaker.app.data.CoachConversation> { it.pinned }
                .thenByDescending { it.updated_at ?: "" })
        viewModelScope.launch { runCatching { repo.setCoachConversationPinned(c.id, next) } }
    }

    /** Load a past conversation to read or continue it. */
    fun openConversation(c: com.workoutmaker.app.data.CoachConversation) {
        conversationId = c.id
        messages.value = listOf(ChatMessage("assistant", GREETING)) + c.messages
        banner.value = null
        showHistory.value = false
    }

    /** Start a fresh thread. */
    fun newChat() {
        conversationId = null
        messages.value = listOf(ChatMessage("assistant", GREETING))
        banner.value = null
        showHistory.value = false
    }

    fun send(text: String) {
        val outgoing = messages.value + ChatMessage("user", text)
        messages.value = outgoing
        sending.value = true
        banner.value = null
        liveStatus.value = "Thinking…"
        val history = outgoing.drop(1) // drop the local greeting

        // Agentic + streamed: the server emits a progress event per tool while
        // the loop runs, then the reply. Falls back to the plain request if the
        // stream fails before any reply arrived.
        viewModelScope.launch {
            var gotReply = false
            fun appendToken(tok: String) {
                if (!gotReply) {
                    gotReply = true
                    messages.value = messages.value + ChatMessage("assistant", tok)
                } else {
                    val last = messages.value.last()
                    messages.value = messages.value.dropLast(1) +
                        ChatMessage(last.role, last.content + tok)
                }
            }
            val streamed = runCatching {
                repo.coachAgenticStream(
                    history, conversationId,
                    onTool = { liveStatus.value = friendlyToolProgress(it) },
                    onToken = { appendToken(it) },
                )
            }
            streamed.onSuccess { r ->
                conversationId = r.conversationId ?: conversationId
                r.error?.let { err ->
                    if (!gotReply) messages.value = messages.value + ChatMessage("assistant", "⚠️ $err")
                }
                if (r.toolsUsed.isNotEmpty()) banner.value = "🔧 " + friendlyTools(r.toolsUsed)
                if (r.toolsUsed.any { it == "plan_week" || it == "generate_workout" || it == "move_workout" }) {
                    loadActionWeek()
                }
            }.onFailure {
                if (!gotReply) {
                    // Stream transport failed — retry once over the plain endpoint.
                    runCatching {
                        repo.coachChat(
                            CoachChatRequest(messages = history, mode = "chat", conversationId = conversationId, purpose = "setup"),
                        )
                    }.onSuccess { reply ->
                        conversationId = reply.conversation_id ?: conversationId
                        messages.value = messages.value + ChatMessage("assistant", reply.reply ?: "(no reply)")
                        if (reply.tools_used.isNotEmpty()) banner.value = "🔧 " + friendlyTools(reply.tools_used)
                        if (reply.tools_used.any { t -> t == "plan_week" || t == "generate_workout" || t == "move_workout" }) {
                            loadActionWeek()
                        }
                    }.onFailure { e2 ->
                        banner.value = e2.message
                        messages.value = messages.value + ChatMessage("assistant", "⚠️ ${e2.message}")
                    }
                }
            }
            sending.value = false
            liveStatus.value = null
        }
    }

    fun finalize(kind: String) {
        sending.value = true
        banner.value = null
        viewModelScope.launch {
            runCatching {
                repo.coachFinalizeRaw(
                    CoachChatRequest(
                        messages = messages.value.drop(1),
                        mode = "finalize",
                        finalizeKind = kind,
                        conversationId = conversationId,
                        save = true,
                    ),
                )
            }.onSuccess {
                banner.value = if (kind == "plan")
                    "✓ Saved a multi-week plan to your templates."
                else "✓ Saved a workout template you can reuse."
            }.onFailure { banner.value = "Couldn't finalize: ${it.message}" }
            sending.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachScreen(vm: CoachViewModel = hiltViewModel(), onOpenCalendar: () -> Unit = {}) {
    val messages by vm.messages.collectAsStateSafe()
    val sending by vm.sending.collectAsStateSafe()
    val banner by vm.banner.collectAsStateSafe()
    val liveStatus by vm.liveStatus.collectAsStateSafe()
    val actionWeek by vm.actionWeek.collectAsStateSafe()
    val suggestions by vm.suggestions.collectAsStateSafe()
    val showHistory by vm.showHistory.collectAsStateSafe()
    val conversations by vm.conversations.collectAsStateSafe()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { vm.closeHistory() },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Text(
                "Chat history",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (conversations.isEmpty()) {
                Text(
                    "No past conversations yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(conversations) { c ->
                    ConversationRow(
                        c,
                        onClick = { vm.openConversation(c) },
                        onPin = { vm.togglePin(c) },
                        onDelete = { vm.deleteConversation(c) },
                    )
                }
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Coach",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.openHistory() }) {
                Icon(Icons.Filled.History, contentDescription = "Chat history")
            }
            IconButton(onClick = { vm.newChat() }) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { msg -> Bubble(msg) }
            actionWeek?.let { week ->
                item { CalendarResultCard(week, onOpen = onOpenCalendar, onDismiss = { vm.dismissActionCard() }) }
            }
            if (sending) {
                item {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text(liveStatus ?: "Coach is thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Contextual conversation starters on a fresh thread.
        if (messages.size <= 1 && !sending) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(suggestions) { sgn ->
                    androidx.compose.material3.AssistChip(onClick = { vm.send(sgn) }, label = { Text(sgn) })
                }
            }
        }

        banner?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Primary action: put what was discussed onto the REAL calendar (the
        // coach uses its plan_week / generate_workout tools). Saving a reusable
        // template is the secondary path, tucked into a small menu. Only shown
        // while the coach is actually proposing sessions — not under plain Q&A,
        // and not when the action already landed (the calendar card shows then).
        val lastAssistant = messages.lastOrNull { it.role == "assistant" }?.content ?: ""
        if (messages.size > 2 && actionWeek == null && looksLikeWorkoutProposal(lastAssistant)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton(
                    onClick = {
                        vm.send("Yes — apply that to my real calendar now and push it to my watch, then confirm exactly what you scheduled.")
                    },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                ) { Text("📅 Apply to my calendar") }
                var templateMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { templateMenu = true }, enabled = !sending) {
                        Text("Save ▾", style = MaterialTheme.typography.labelMedium)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = templateMenu,
                        onDismissRequest = { templateMenu = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Save as workout template") },
                            onClick = { templateMenu = false; vm.finalize("workout") },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Save as plan template") },
                            onClick = { templateMenu = false; vm.finalize("plan") },
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message your coach…") },
                shape = RoundedCornerShape(24.dp),
            )
            val canSend = !sending && input.isNotBlank()
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = { if (input.isNotBlank()) { vm.send(input.trim()); input = "" } },
                    enabled = canSend,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    c: com.workoutmaker.app.data.CoachConversation,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = c.title?.takeIf { it.isNotBlank() }
        ?: c.messages.firstOrNull { it.role == "user" }?.content?.take(60)
        ?: "Conversation"
    val preview = c.messages.lastOrNull { it.role == "assistant" }?.content
        ?.let { previewText(it) }
        ?.take(80).orEmpty()
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            c.updated_at?.let { ts ->
                Text(
                    ts.take(10),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        IconButton(onClick = onPin) {
            Icon(
                if (c.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (c.pinned) "Unpin" else "Pin",
                tint = if (c.pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { confirmDelete = true }) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete conversation?") },
            text = { Text("This permanently removes “$title”.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// Shown after the coach changes the calendar: the week as it now actually is,
// straight from planned_workouts, with a jump into the Calendar tab.
@Composable
private fun CalendarResultCard(
    week: List<com.workoutmaker.app.data.PlannedWorkout>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.workoutmaker.app.ui.components.SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.workoutmaker.app.ui.components.SectionLabel("Now on your calendar")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Hide", style = MaterialTheme.typography.labelMedium) }
        }
        if (week.isEmpty()) {
            Text(
                "Nothing scheduled this week yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        week.forEach { w ->
            val day = runCatching {
                java.time.LocalDate.parse(w.date).dayOfWeek
                    .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
            }.getOrDefault(w.date)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day,
                    Modifier.widthIn(min = 44.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (w.type == "rest") "Rest" else w.workout_json.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                val tss = w.workout_json.tss_estimate
                if (tss > 0) {
                    Text(
                        "${tss.toInt()} TSS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        GhostButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("View in calendar") }
    }
}

@Composable
private fun Bubble(msg: ChatMessage) {
    val isUser = msg.role == "user"

    // Some agentic replies leak raw JSON — either the protocol envelope
    // ({action, message}) or a data blob. Unwrap the prose; render data as a card.
    if (!isUser && looksLikeJson(msg.content)) {
        val obj = runCatching { coachJson.parseToJsonElement(msg.content) }.getOrNull() as? JsonObject
        val unwrapped = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        when {
            unwrapped != null -> AssistantText(unwrapped)
            obj != null && isWorkoutShape(obj) -> WorkoutCard(obj)
            else -> DataCard(msg.content)
        }
        return
    }

    val shape = if (isUser)
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    else
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .then(
                    if (isUser) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (isUser) {
                Text(
                    msg.content,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // The model writes markdown (bold, lists, headers) — render it.
                com.workoutmaker.app.ui.components.MarkdownText(
                    msg.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AssistantText(text: String) {
    val shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            com.workoutmaker.app.ui.components.MarkdownText(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// --- workout rendering -------------------------------------------------------

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = (this[k] as? JsonPrimitive)?.contentOrNull
        if (!v.isNullOrBlank() && v != "null") return v
    }
    return null
}

// A workout payload either is a Workout ({title,type,sections}) or wraps one in
// `structure` (the finalize shape: {name,description,structure,coach_note}).
private fun isWorkoutShape(obj: JsonObject): Boolean {
    val core = obj.obj("structure") ?: obj
    return core.arr("sections") != null
}

@Composable
private fun WorkoutCard(obj: JsonObject) {
    val core = obj.obj("structure") ?: obj
    val title = core.str("title") ?: obj.str("name") ?: "Workout"
    val type = core.str("type") ?: obj.str("kind")
    val duration = core.str("duration_minutes")
    val tss = core.str("tss_estimate")
    val rpe = core.str("rpe_target")
    val desc = obj.str("description") ?: core.str("description")
    val note = obj.str("coach_note", "coach note") ?: core.str("coach_note", "coach note")
    val sections = core.arr("sections").orEmpty()
    val shape = RoundedCornerShape(16.dp)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Meta chips: type · duration · TSS · RPE
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                type?.let { MetaChip(it.replaceFirstChar { c -> c.uppercase() }) }
                duration?.let { MetaChip("$it min") }
                tss?.let { MetaChip("TSS $it") }
                rpe?.let { MetaChip("RPE $it") }
            }
            desc?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            sections.forEachIndexed { i, secEl ->
                val sec = secEl as? JsonObject ?: return@forEachIndexed
                val secName = sec.str("name") ?: "Section ${i + 1}"
                val secDur = sec.str("duration_minutes")
                Text(
                    if (secDur != null) "$secName · $secDur min" else secName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                sec.arr("exercises").orEmpty().forEach { exEl ->
                    val ex = exEl as? JsonObject ?: return@forEach
                    ExerciseLine(ex)
                }
            }
            note?.let {
                Text(
                    "💡 $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ExerciseLine(ex: JsonObject) {
    val name = ex.str("name") ?: "Exercise"
    val sets = ex.str("sets")
    val reps = ex.str("reps")
    val weight = ex.str("weight_kg")
    val zone = ex.str("pace_zone", "hr_zone", "zone")
    val rest = ex.str("rest_seconds")
    val notes = ex.str("notes")

    // "3×8" / "1×continuous" / "8" depending on what's present.
    val dose = when {
        sets != null && reps != null -> "$sets×$reps"
        reps != null -> reps
        sets != null -> "$sets sets"
        else -> null
    }
    val meta = listOfNotNull(
        dose,
        weight?.let { "$it kg" },
        zone,
        rest?.let { "${it}s rest" },
    ).joinToString(" · ")

    Column(Modifier.padding(top = 4.dp)) {
        Row {
            Text(
                "•  ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (meta.isNotBlank()) {
                Text(
                    "  $meta",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        notes?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// Renders a leaked JSON data blob as a collapsed card the user can expand into
// readable "Label: value" rows instead of dumping raw braces into the chat.
@Composable
private fun DataCard(raw: String) {
    var expanded by remember { mutableStateOf(false) }
    val rows = remember(raw) { flattenJson(raw) }
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                if (expanded) "📊 Coach data  ▴" else "📊 Coach data  ▾",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (expanded) {
                if (rows.isEmpty()) {
                    Text(
                        raw,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Column(Modifier.padding(top = 8.dp)) {
                        rows.forEach { (label, value) ->
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    "$label  ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val coachJson = Json { ignoreUnknownKeys = true; isLenient = true }

// Heuristic: is this assistant reply proposing a concrete workout or week plan
// (structure markers / day-by-day breakdown) rather than analysis or Q&A?
internal fun looksLikeWorkoutProposal(text: String): Boolean {
    if (looksLikeJson(text)) {
        val obj = runCatching { coachJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
        return obj != null && isWorkoutShape(obj)
    }
    if (text.length < 80) return false
    val t = text.lowercase()
    val structure = listOf(
        "warm-up", "warmup", "main set", "cool-down", "cooldown", "×", " sets",
        " reps", "interval", "tempo", "easy run", "long run", "rest day",
    )
    val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    return structure.count { t.contains(it) } >= 2 || days.count { t.contains(it) } >= 3
}

private fun looksLikeJson(s: String): Boolean {
    val t = s.trim()
    return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
}

// A readable one-liner for history previews: unwrap envelopes, name workouts,
// otherwise show the prose.
private fun previewText(content: String): String {
    if (!looksLikeJson(content)) return content
    val obj = runCatching { coachJson.parseToJsonElement(content) }.getOrNull() as? JsonObject
        ?: return "📊 Coach data"
    (obj["message"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    if (isWorkoutShape(obj)) {
        val core = obj.obj("structure") ?: obj
        return "🏋️ " + (core.str("title") ?: obj.str("name") ?: "Workout")
    }
    return "📊 Coach data"
}

private fun prettyKey(k: String): String =
    k.replace('_', ' ').replaceFirstChar { it.uppercase() } + ":"

// Flatten a JSON value into readable label/value rows (one level of nesting),
// turning snake_case keys into Title Case. Returns empty on parse failure.
private fun flattenJson(raw: String): List<Pair<String, String>> {
    val el = runCatching { coachJson.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    fun scalar(e: JsonElement): String? = (e as? JsonPrimitive)?.let {
        if (it.isString) it.content else it.content
    }
    when (el) {
        is JsonObject -> el.forEach { (k, v) ->
            when (v) {
                is JsonPrimitive -> out += prettyKey(k) to (scalar(v) ?: v.toString())
                is JsonArray -> out += prettyKey(k) to "${v.size} item(s)"
                is JsonObject -> v.forEach { (k2, v2) ->
                    val label = "${prettyKey(k)} ${prettyKey(k2).removeSuffix(":")}"
                    out += label to when (v2) {
                        is JsonPrimitive -> scalar(v2) ?: v2.toString()
                        is JsonArray -> "${v2.size} item(s)"
                        else -> "…"
                    }
                }
            }
        }
        is JsonArray -> el.take(20).forEachIndexed { i, item ->
            val summary = (item as? JsonObject)?.entries?.joinToString(", ") { (k, v) ->
                "${k.replace('_', ' ')} ${(v as? JsonPrimitive)?.let { scalar(it) } ?: v}"
            } ?: scalar(item) ?: item.toString()
            out += "${i + 1}." to summary
        }
        else -> {}
    }
    return out
}
