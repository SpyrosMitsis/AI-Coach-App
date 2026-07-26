package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import com.workoutmaker.app.ui.components.EmptyState
import com.workoutmaker.app.ui.components.GhostButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    "Hey! I'm your coach, and I can see your real data. Ask me things like " +
        "“how's my fitness looking?”, “plan my week”, “am I overtraining?”, or " +
        "“set my goal race to the Berlin marathon on 2026-09-27”. I'll check your " +
        "numbers, then plan, generate, or adjust your training for you."

// A conversation starter: the short text shown on the chip, plus the richer,
// directive prompt actually sent to the coach. The chip stays terse; the prompt
// under the hood tells the coach to read the data and drive to a concrete
// outcome in one turn — so the user doesn't have to keep nudging "go on".
data class CoachStarter(val label: String, val prompt: String)

// The directive prompts behind the starter chips. Each one tells the coach to
// pull the relevant data itself and finish the job in a single turn — give the
// answer, take the action, and end with a clear next step — instead of asking a
// question and waiting. This is what removes the "I have to keep saying go on".
private const val STARTER_FITNESS =
    "Give me a full read on my fitness right now. Pull my CTL/ATL/TSB, this week's " +
        "load and my readiness, then tell me what shape I'm in, what's trending up or " +
        "down, and the one thing I should focus on this week. Use the real numbers."
private const val STARTER_PLAN_WEEK =
    "Plan my full training week. Check my fitness, readiness, recent sessions and goal " +
        "first, then build the complete week and put it on my calendar, don't ask me to " +
        "confirm each day. When it's scheduled, summarize the week and why it's built that way."
private const val STARTER_EXPLAIN_TODAY =
    "Look at today's planned workout and explain exactly why it's the right session for me " +
        "today, how it fits my current fitness, fatigue and goal. If it doesn't match how " +
        "I'm likely feeling, propose a specific adjustment and offer to apply it."
private const val STARTER_MAKE_TODAY =
    "Make me today's workout. Check my readiness, recent training and goal, pick the right " +
        "type and intensity yourself, generate it and put it on my calendar, then tell me the plan."
private const val STARTER_RECENT =
    "Review how my recent training has actually gone, how I executed the sessions versus the " +
        "plan. Tell me what's going well, what's lagging, and the most useful change to make next."

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
    "set_rest_day" -> "Setting a rest day…"
    "make_easier" -> "Making the session easier…"
    "set_goal_race" -> "Setting your goal race…"
    "remember" -> "Noting that down…"
    "update_profile" -> "Updating your profile…"
    "update_app_settings" -> "Updating your app settings…"
    else -> "Working…"
}

// Tools that mutate the calendar/plan — used to drive the "✓ Updated" result card.
private val WRITE_TOOL_NAMES = setOf(
    "plan_week", "generate_workout", "move_workout", "set_goal_race", "set_rest_day", "make_easier",
)

// Contextual quick replies for the turn that just finished. Deterministic from
// the write tools used: after a concrete action the athlete predictably wants
// one of a few follow-ups; after a plain answer, chips would be noise (none).
// Directive prompts, same philosophy as the starters: finish the job in one turn.
internal fun followUpChips(toolsUsed: List<String>): List<CoachStarter> = when {
    // Order matters: a week plan is the bigger action, its follow-ups win even
    // when a single workout was also generated in the same turn.
    "plan_week" in toolsUsed -> listOf(
        CoachStarter(
            "Explain the week",
            "Walk me through the week you just planned, day by day, and why it's structured that way for my goal and current fitness.",
        ),
        CoachStarter(
            "Make it easier",
            "The planned week feels like too much. Reduce the overall load, keep the structure sensible, apply the changes, and summarize what moved.",
        ),
        CoachStarter(
            "Swap two days",
            "Swap the two days in this week's plan that would fit me better the other way around. Pick them yourself, apply the swap, and tell me which ones and why.",
        ),
    )
    "generate_workout" in toolsUsed || "make_easier" in toolsUsed -> listOf(
        CoachStarter(
            "Make it easier",
            "Make the workout you just created easier. Reduce the intensity or volume, apply the change, and tell me what you changed.",
        ),
        CoachStarter(
            "Move it",
            "Move the workout you just created to a better day this week. Look at my week, pick the day yourself, apply the move, and tell me why.",
        ),
        CoachStarter(
            "Why this session?",
            "Explain why the session you just created is the right one for me now, based on my fitness, fatigue and goal. Use the real numbers.",
        ),
    )
    "set_goal_race" in toolsUsed -> listOf(
        CoachStarter(
            "Plan toward it",
            "Now that my goal race is set, plan this week so it starts building toward it. Put the week on my calendar and summarize the focus.",
        ),
    )
    "move_workout" in toolsUsed || "set_rest_day" in toolsUsed -> listOf(
        CoachStarter(
            "Rebalance the week",
            "After that change, check this week's balance, hard-day spacing, load and recovery, and fix anything that no longer makes sense. Apply what you change.",
        ),
    )
    else -> emptyList()
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
            "set_rest_day" -> "set a rest day"
            "make_easier" -> "made the session easier"
            "set_goal_race" -> "set your goal race"
            "remember" -> "noted that for next time"
            "update_profile" -> "updated your profile"
            "update_app_settings" -> "updated your app settings"
            else -> t
        }
    }
    return tools.distinct().joinToString(" · ", transform = label)
}

@HiltViewModel
class CoachViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val planChanges: com.workoutmaker.app.data.PlanChangeBus,
) : ViewModel() {
    val messages = MutableStateFlow(listOf(ChatMessage("assistant", GREETING)))
    val sending = MutableStateFlow(false)
    val banner = MutableStateFlow<String?>(null)
    // When a send fails outright, the failed text is parked here so the screen can
    // refill the input box — the athlete retries with one tap, not a retype.
    val draftRestore = MutableStateFlow<String?>(null)
    // Live tool-progress line while the agentic loop runs.
    val liveStatus = MutableStateFlow<String?>(null)
    // The agentic turn as visible steps: each tool event appends a running row,
    // and the next event checks the previous one off. Collapses to the banner
    // summary when the turn ends. Write tools are accented in the UI.
    data class ToolStep(val label: String, val write: Boolean, val done: Boolean)
    val toolSteps = MutableStateFlow<List<ToolStep>>(emptyList())
    private fun advanceTool(tool: String) {
        liveStatus.value = null // the step list replaces the generic line
        toolSteps.value = toolSteps.value.map { it.copy(done = true) } +
            ToolStep(friendlyToolProgress(tool), tool in WRITE_TOOL_NAMES, done = false)
    }
    // Contextual quick replies after a coach turn (never after a plain answer).
    val followUps = MutableStateFlow<List<CoachStarter>>(emptyList())
    fun dismissFollowUps() { followUps.value = emptyList() }
    // After the coach changes the calendar: this week's sessions for the result card.
    val actionWeek = MutableStateFlow<List<com.workoutmaker.app.data.PlannedWorkout>?>(null)
    // Plain-language summary of the write actions the coach just took (card subtitle).
    val lastAction = MutableStateFlow<String?>(null)
    // Whether a full week was (re)planned this turn — gates the card's "Re-plan" escape hatch.
    val showReplan = MutableStateFlow(false)
    // Contextual conversation starters, built from the cached dashboard.
    val suggestions = MutableStateFlow(
        listOf(
            CoachStarter("How's my fitness?", STARTER_FITNESS),
            CoachStarter("Plan my week", STARTER_PLAN_WEEK),
        ),
    )
    private var conversationId: String? = null

    // Incognito: this thread is never saved (server persists nothing, history
    // never lists it). Toggling starts a fresh thread so the semantics are
    // clean — a chat is either fully saved or fully ephemeral.
    val incognito = MutableStateFlow(false)
    fun toggleIncognito() {
        incognito.value = !incognito.value
        newChat(keepIncognito = true)
    }

    // ---- typewriter reveal ---------------------------------------------------
    // The server sends the whole reply as ONE token event (true LLM streaming
    // would blind the hosted cost logging), so the "typing" feel is client-side:
    // arriving text queues here and a job reveals it a few words at a time.
    // The screen sets [animateReplies] from rememberAnimationsEnabled(), so the
    // system remove-animations setting shows replies instantly.
    val animateReplies = MutableStateFlow(true)
    // True while the typewriter is still revealing text. The screen holds back
    // everything that would otherwise pop in under the growing message (result
    // card, banner, follow-up chips) until this settles, so the reply types
    // into a stable layout instead of one that keeps reflowing.
    val revealing = MutableStateFlow(false)
    private var revealJob: kotlinx.coroutines.Job? = null
    private val revealQueue = StringBuilder()

    /** Append [text] to the trailing assistant message, creating it if needed. */
    private fun appendVisible(text: String) {
        val msgs = messages.value
        val last = msgs.lastOrNull()
        messages.value = if (last?.role == "assistant" && revealTarget) {
            msgs.dropLast(1) + ChatMessage("assistant", last.content + text)
        } else {
            revealTarget = true
            msgs + ChatMessage("assistant", text)
        }
    }

    // True while the trailing assistant message is this turn's reply (so
    // appendVisible knows to extend it rather than start a new bubble).
    private var revealTarget = false

    /** Drop anything queued without showing it (thread switch / new chat). */
    private fun cancelReveal() {
        revealJob?.cancel()
        revealJob = null
        revealQueue.setLength(0)
        revealTarget = false
        revealing.value = false
    }

    /** Show everything still queued at once (new send, screen exit, no-anim). */
    private fun flushReveal() {
        revealJob?.cancel()
        revealJob = null
        if (revealQueue.isNotEmpty()) {
            appendVisible(revealQueue.toString())
            revealQueue.setLength(0)
        }
        revealing.value = false
    }

    private fun queueReveal(tok: String) {
        // JSON replies render as a card (WorkoutCard/DataCard) only once whole;
        // typing them out would flash broken JSON as text first. Show at once.
        val jsonStart = !revealTarget && revealQueue.isEmpty() && tok.trimStart().startsWith("{")
        if (!animateReplies.value || jsonStart) {
            flushReveal()
            appendVisible(tok)
            return
        }
        revealQueue.append(tok)
        if (revealJob == null) {
            revealing.value = true
            revealJob = viewModelScope.launch {
                while (revealQueue.isNotEmpty()) {
                    // Reveal WHOLE LINES. MarkdownText renders one Text per line,
                    // so a complete line styles once and never again — revealing
                    // mid-line made bullets/bold flip styling on every tick,
                    // which read as the whole message flickering. Only a final
                    // unterminated line (no newline left) reveals by word chunks,
                    // confining any style flip to that single line.
                    val nl = revealQueue.indexOf('\n')
                    val cut = if (nl >= 0) {
                        nl + 1
                    } else {
                        var c = minOf(40, revealQueue.length)
                        while (c < revealQueue.length && !revealQueue[c].isWhitespace()) c++
                        c
                    }
                    val chunk = revealQueue.substring(0, cut)
                    revealQueue.delete(0, cut)
                    appendVisible(chunk)
                    kotlinx.coroutines.delay(if (nl >= 0) 120 else 45)
                }
                revealJob = null
                revealing.value = false
            }
        }
    }

    init {
        viewModelScope.launch {
            val cached = runCatching { repo.cachedDailySummary() }.getOrNull()?.first
            val chips = mutableListOf(CoachStarter("How's my fitness?", STARTER_FITNESS))
            val today = cached?.today_workout
            if (today?.workout_json != null && !today.completed) {
                chips += CoachStarter("Explain today's workout", STARTER_EXPLAIN_TODAY)
            } else if (today == null) {
                chips += CoachStarter("Workout for today", STARTER_MAKE_TODAY)
            }
            chips += CoachStarter("Plan my week", STARTER_PLAN_WEEK)
            cached?.goal?.let { g ->
                g.weeks_to_goal?.let { w ->
                    chips += CoachStarter(
                        "On track for ${g.goal}?",
                        "Am I on track for my goal of ${g.goal}, $w weeks out? Read my fitness " +
                            "trend (CTL/ATL/TSB), recent volume and how I've been executing, then tell " +
                            "me honestly if I'm ahead, on pace, or behind, and the single most important " +
                            "thing to adjust over the next two weeks.",
                    )
                }
            }
            chips += CoachStarter("How did my training go?", STARTER_RECENT)
            suggestions.value = chips.take(5)
        }
    }

    /** Ask the Calendar tab to open on [date] (the nav itself is the screen's). */
    fun focusCalendar(date: String) {
        planChanges.focusDate.value = date
    }

    fun dismissActionCard() {
        actionWeek.value = null
        lastAction.value = null
        showReplan.value = false
    }

    // Re-run the weekly plan from the result card — the same plan-week action the
    // Calendar tab uses, as a quick "not happy with this? regenerate" escape hatch.
    fun rePlanWeek() {
        sending.value = true
        liveStatus.value = "Re-planning your week…"
        viewModelScope.launch {
            runCatching {
                val monday = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY)
                repo.planWeek(com.workoutmaker.app.data.PlanWeekRequest(start_date = monday.toString()))
            }.onSuccess {
                lastAction.value = "re-planned your week"
                banner.value = "✓ Re-planned your week"
                loadActionWeek()
                planChanges.emit("coach")
            }.onFailure {
                com.workoutmaker.app.util.AppLog.w("coach", "re-plan failed", it)
                banner.value = com.workoutmaker.app.util.friendlyFnError(
                    it, "Couldn't re-plan your week. Try again.",
                )
            }
            sending.value = false
            liveStatus.value = null
        }
    }

    // Reflect any write actions the coach took this turn: a plain-language card
    // subtitle, the "Re-plan" escape hatch (only after a full week plan), and a
    // fresh pull of the real calendar for the result card.
    private fun onToolsUsed(tools: List<String>) {
        val writes = tools.filter { it in WRITE_TOOL_NAMES }
        followUps.value = followUpChips(tools)
        if (writes.isNotEmpty()) {
            lastAction.value = friendlyTools(writes)
            showReplan.value = writes.contains("plan_week")
            // Home and Calendar survive tab switches; tell them the plan moved.
            planChanges.emit("coach")
        }
        if (tools.any { it == "plan_week" || it == "generate_workout" || it == "move_workout" }) {
            loadActionWeek()
        }
    }

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
        cancelReveal() // never type a stale reply into the newly opened thread
        incognito.value = false // saved threads are by definition not incognito
        conversationId = c.id
        messages.value = listOf(ChatMessage("assistant", GREETING)) + c.messages
        banner.value = null
        showHistory.value = false
    }

    /** Start a fresh thread. */
    fun newChat(keepIncognito: Boolean = false) {
        cancelReveal()
        if (!keepIncognito) incognito.value = false
        conversationId = null
        messages.value = listOf(ChatMessage("assistant", GREETING))
        banner.value = null
        showHistory.value = false
    }

    fun send(text: String) {
        flushReveal() // never animate two replies at once
        revealTarget = false
        val outgoing = messages.value + ChatMessage("user", text)
        messages.value = outgoing
        sending.value = true
        banner.value = null
        lastAction.value = null
        showReplan.value = false
        followUps.value = emptyList()
        toolSteps.value = emptyList()
        liveStatus.value = "Thinking…"
        val history = outgoing.drop(1) // drop the local greeting

        // Agentic + streamed: the server emits a progress event per tool while
        // the loop runs, then the reply. Falls back to the plain request if the
        // stream fails before any reply arrived.
        viewModelScope.launch {
            var gotReply = false
            fun appendToken(tok: String) {
                gotReply = true
                queueReveal(tok)
            }
            suspend fun applySettings(changes: List<com.workoutmaker.app.data.ChatSettingChange>) {
                if (changes.isEmpty()) return
                val applied = repo.applyChatSettings(changes)
                if (applied.isNotEmpty()) banner.value = "✓ Settings updated: " + applied.joinToString(", ")
            }
            val streamed = runCatching {
                repo.coachAgenticStream(
                    history, conversationId, incognito.value,
                    onTool = { advanceTool(it) },
                    onToken = { appendToken(it) },
                )
            }
            streamed.onSuccess { r ->
                conversationId = r.conversationId ?: conversationId
                r.error?.let { err ->
                    if (!gotReply) messages.value = messages.value + ChatMessage("assistant", "⚠️ $err")
                }
                applySettings(r.settingsChanges)
                onToolsUsed(r.toolsUsed)
            }.onFailure {
                if (!gotReply) {
                    // Stream transport failed — retry once over the plain endpoint.
                    runCatching {
                        repo.coachChat(
                            CoachChatRequest(
                                messages = history, mode = "chat", conversationId = conversationId,
                                purpose = "setup", incognito = incognito.value,
                            ),
                        )
                    }.onSuccess { reply ->
                        conversationId = reply.conversation_id ?: conversationId
                        appendToken(reply.reply ?: "(no reply)")
                        applySettings(reply.settings_changes)
                        onToolsUsed(reply.tools_used)
                    }.onFailure { e2 ->
                        com.workoutmaker.app.util.AppLog.w("coach", "both transports failed", e2)
                        banner.value = com.workoutmaker.app.util.friendlyFnError(
                            e2, "The coach didn't answer. Tap send to try again.",
                        )
                        // Both transports failed — revert the stranded user turn and
                        // hand the text back to the input box so retry is one tap.
                        if (messages.value.lastOrNull()?.role == "user") {
                            messages.value = messages.value.dropLast(1)
                        }
                        draftRestore.value = text
                    }
                }
            }
            sending.value = false
            liveStatus.value = null
            // The step list collapses into the banner summary; the reveal job
            // (if still typing) carries on, it owns only message text.
            toolSteps.value = emptyList()
        }
    }

    // One-tap retry after a failed turn: drop the trailing error bubble and
    // re-send the last user message (send() re-appends it).
    fun retryLast() {
        val lastUser = messages.value.lastOrNull { it.role == "user" }?.content ?: return
        messages.value = messages.value.dropLastWhile { it.role != "user" }.dropLast(1)
        send(lastUser)
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
            }.onFailure {
                com.workoutmaker.app.util.AppLog.w("coach", "finalize failed", it)
                banner.value = com.workoutmaker.app.util.friendlyFnError(
                    it, "Couldn't save that. Try again.",
                )
            }
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
    val lastAction by vm.lastAction.collectAsStateSafe()
    val showReplan by vm.showReplan.collectAsStateSafe()
    val suggestions by vm.suggestions.collectAsStateSafe()
    val showHistory by vm.showHistory.collectAsStateSafe()
    val conversations by vm.conversations.collectAsStateSafe()
    val toolSteps by vm.toolSteps.collectAsStateSafe()
    val followUps by vm.followUps.collectAsStateSafe()
    // While the reply is typing out, hold back everything that would pop in
    // beneath it (result card, banner, chips): each arrival reflowed the layout
    // under the growing text and read as flicker.
    val revealing by vm.revealing.collectAsStateSafe()
    // Saveable: a half-typed coach message must survive rotation.
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Respect the system remove-animations setting: replies appear at once.
    val animationsOn = com.workoutmaker.app.ui.components.rememberAnimationsEnabled()
    LaunchedEffect(animationsOn) { vm.animateReplies.value = animationsOn }

    // A soft tick when the coach finishes answering, same language as the rest
    // of the app (set-done, PRs). "Finished" = the turn is done AND the
    // typewriter has settled, so the tick lands on the completed reply.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val busy = sending || revealing
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (wasBusy && !busy && messages.lastOrNull()?.role == "assistant") {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
        wasBusy = busy
    }

    // A failed send parks its text here — pull it back into the box (unless the
    // athlete already started typing something new) so retry is one tap.
    val draftRestore by vm.draftRestore.collectAsStateSafe()
    LaunchedEffect(draftRestore) {
        draftRestore?.let {
            if (input.isBlank()) input = it
            vm.draftRestore.value = null
        }
    }

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
                EmptyState(
                    title = "No conversations yet",
                    subtitle = "Your past coaching chats will show up here.",
                    icon = Icons.Filled.History,
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

    // Whether the reader is pinned to the newest content (also drives the FAB).
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            lastVisible == null || lastVisible.index >= info.totalItemsCount - 1
        }
    }

    // Follow new messages with one animated scroll; follow the typewriter's
    // growth with instant, non-animated pins. Restarting animateScrollToItem on
    // every reveal tick fought its own animation and flickered the whole list.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    val lastContentLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(lastContentLength) {
        // Never yank the reader back down if they scrolled up mid-reveal.
        if (messages.isEmpty() || !atBottom) return@LaunchedEffect
        // Pin the growing tail by scrolling EXACTLY the overflow: how far the
        // last item's bottom now pokes past the viewport. (scrollToItem with a
        // huge offset overshot the content: one blank frame per tick.)
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val overflow = last.offset + last.size - info.viewportEndOffset
        if (overflow > 0) listState.scrollBy(overflow.toFloat())
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        val incognito by vm.incognito.collectAsStateSafe()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                com.workoutmaker.app.ui.components.SectionLabel(if (incognito) "AI COACH · INCOGNITO" else "AI COACH")
                Text(
                    "Coach",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            IconButton(onClick = { vm.toggleIncognito() }) {
                Icon(
                    if (incognito) Icons.Filled.VisibilityOff else Icons.Outlined.VisibilityOff,
                    contentDescription = if (incognito) "Leave incognito" else "Incognito chat",
                    tint = if (incognito) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.openHistory() }) {
                Icon(Icons.Filled.History, contentDescription = "Chat history")
            }
            IconButton(onClick = { vm.newChat() }) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }
        if (incognito) {
            Text(
                "Incognito: this chat won't be saved to history or remembered by your coach.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Positional keys: only ever appended to (or the tail mutated),
                // so the index is a stable identity. Without keys every list
                // rebuild re-established item identity and the reveal flickered.
                itemsIndexed(messages, key = { i, _ -> i }) { i, msg ->
                    // Consecutive assistant messages group under one avatar.
                    val prevRole = messages.getOrNull(i - 1)?.role
                    // animateItem() smooths a settled bubble being replaced in place,
                    // but on the CURRENTLY GROWING reply it animates the bubble's own
                    // resize — its bounds lag the real size every tick, so the
                    // overflow-based auto-scroll below (which reads those bounds)
                    // keeps chasing a moving target and never truly pins to the
                    // bottom while text is streaming in. Skip it on that one bubble.
                    val isStreamingTail = revealing && i == messages.lastIndex && msg.role == "assistant"
                    val itemModifier = if (isStreamingTail) Modifier else Modifier.animateItem()
                    Box(itemModifier) { Bubble(msg, showAvatar = msg.role != prevRole) }
                }
                // A failed turn gets a one-tap retry directly under it.
                val last = messages.lastOrNull()
                if (!sending && last?.role == "assistant" && last.content.startsWith("⚠️")) {
                    item {
                        TextButton(onClick = { vm.retryLast() }) { Text("Try again") }
                    }
                }
                if (!revealing) actionWeek?.let { week ->
                    item {
                        CalendarResultCard(
                            week,
                            changed = lastAction,
                            showReplan = showReplan,
                            onOpen = onOpenCalendar,
                            onOpenDay = { date -> vm.focusCalendar(date); onOpenCalendar() },
                            onReplan = { vm.rePlanWeek() },
                            onDismiss = { vm.dismissActionCard() },
                        )
                    }
                }
                if (sending) {
                    item {
                        // The agentic turn made visible: each tool the coach uses
                        // becomes a step that checks off, so a 30s plan feels like
                        // work happening rather than a stuck spinner. Before the
                        // first tool event, the plain typing indicator.
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (toolSteps.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TypingDots()
                                    Text(
                                        liveStatus ?: "Coach is thinking…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 10.dp),
                                    )
                                }
                            } else {
                                toolSteps.forEach { step -> ToolStepRow(step) }
                            }
                        }
                    }
                }
            }

            // Jump back down when the reader has scrolled up into history.
            val scope = rememberCoroutineScope()
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val count = listState.layoutInfo.totalItemsCount
                            if (count > 0) listState.animateScrollToItem(count - 1)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to latest")
                }
            }
        }

        // Quick-reply chips: starters on a fresh thread, contextual follow-ups
        // after a coach action ("Explain the week", "Make it easier", ...). One
        // row, one component; follow-ups clear on tap or when a new turn starts.
        val chips = if (messages.size <= 1) suggestions else followUps
        if (chips.isNotEmpty() && !sending && !revealing) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips) { sgn ->
                    androidx.compose.material3.AssistChip(
                        onClick = { vm.dismissFollowUps(); vm.send(sgn.prompt) },
                        label = { Text(sgn.label) },
                    )
                }
            }
        }

        if (!revealing) banner?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Fallback only: the coach normally applies changes itself (its plan_week /
        // generate_workout tools land on the real calendar and the result card
        // shows above). This appears just for the rare turn where the coach
        // proposed sessions in prose without applying them — a manual escape hatch.
        // Saving a reusable template is tucked into the small "Save" menu.
        val lastAssistant = messages.lastOrNull { it.role == "assistant" }?.content ?: ""
        if (!revealing && messages.size > 2 && actionWeek == null && looksLikeWorkoutProposal(lastAssistant)) {
            Text(
                "Coach proposed this but didn't apply it:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton(
                    onClick = {
                        vm.send("Yes, apply that to my real calendar now and push it to my watch, then confirm exactly what you scheduled.")
                    },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                ) { Text("📅 Put it on my calendar") }
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

        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; if (it.isNotEmpty()) vm.dismissFollowUps() },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message your coach…") },
                shape = RoundedCornerShape(24.dp),
                // Long questions happen; grow with the text, cap before it eats
                // the thread. The send button anchors to the bottom edge.
                maxLines = 4,
            )
            val canSend = !sending && input.isNotBlank()
            val sendBg by androidx.compose.animation.animateColorAsState(
                if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                label = "sendBg",
            )
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(sendBg),
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
    changed: String?,
    showReplan: Boolean,
    onOpen: () -> Unit,
    onOpenDay: (String) -> Unit,
    onReplan: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.workoutmaker.app.ui.components.SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.workoutmaker.app.ui.components.SectionLabel(
                if (changed != null) "✓ Updated your calendar" else "Now on your calendar",
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Hide", style = MaterialTheme.typography.labelMedium) }
        }
        changed?.let {
            Text(
                "The coach $it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
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
            // Each day is a jump straight to that date on the Calendar tab.
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpenDay(w.date) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("View in calendar") }
            if (showReplan) {
                TextButton(onClick = onReplan) {
                    Text("Re-plan", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// One row of the live tool timeline: a pulsing dot while the tool runs, a ✓
// once the next event arrives. Write tools (they change the calendar) carry the
// amber accent so "about to modify your plan" is visually distinct from reads.
@Composable
private fun ToolStepRow(step: CoachViewModel.ToolStep) {
    val accent = if (step.write) com.workoutmaker.app.ui.theme.amberAccent()
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (step.done) {
            Text("✓", style = MaterialTheme.typography.labelMedium, color = accent)
        } else {
            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "step")
            val alpha by pulse.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(600),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "stepAlpha",
            )
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(accent.copy(alpha = alpha)),
            )
        }
        Text(
            step.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// Soft three-dot "coach is typing" indicator.
@Composable
internal fun TypingDots() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(500),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 160),
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun Bubble(msg: ChatMessage, showAvatar: Boolean = true) {
    val isUser = msg.role == "user"

    // Some agentic replies leak raw JSON — either the protocol envelope
    // ({action, message}) or a data blob. Unwrap the prose; render data as a card.
    if (!isUser && looksLikeJson(msg.content)) {
        val obj = runCatching { coachJson.parseToJsonElement(msg.content) }.getOrNull() as? JsonObject
        val unwrapped = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        when {
            unwrapped != null -> AssistantProse(unwrapped, showAvatar)
            obj != null && isWorkoutShape(obj) -> WorkoutCard(obj)
            else -> DataCard(msg.content)
        }
        return
    }

    if (!isUser && msg.content.startsWith("⚠️")) {
        ErrorTurn(msg.content.removePrefix("⚠️").trim())
        return
    }

    if (!isUser) {
        AssistantProse(msg.content, showAvatar)
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                msg.content,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// The coach speaks as the app, not from a box: avatar + flat prose on the
// background, full reading width. Consecutive assistant messages group — the
// avatar appears only on the first, follow-ons indent to the same text column.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AssistantProse(text: String, showAvatar: Boolean = true) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val snackbar = com.workoutmaker.app.ui.components.LocalAppSnackbar.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (showAvatar) {
            com.workoutmaker.app.ui.components.LogoMark(
                modifier = Modifier.padding(end = 8.dp, top = 3.dp),
                size = 20.dp,
                animate = false,
            )
        } else {
            Spacer(Modifier.width(28.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        snackbar?.show("Copied")
                    },
                )
                .padding(vertical = 2.dp),
        ) {
            com.workoutmaker.app.ui.components.MarkdownText(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            )
        }
    }
}

// A failed turn: compact, clearly an error, never styled like coach advice.
@Composable
private fun ErrorTurn(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
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
