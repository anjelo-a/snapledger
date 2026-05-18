package com.snapledger.feature.insights.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.feature.dashboard.network.DashboardInsightChatResult
import com.snapledger.feature.dashboard.network.DashboardInsightClient
import com.snapledger.feature.dashboard.network.DashboardInsightMetrics
import kotlinx.coroutines.launch

private data class QuickPrompt(
    val templateKey: String,
    val label: String,
)

private data class InsightMessage(
    val id: String,
    val sender: InsightSender,
    val text: String,
    val supportingText: String? = null,
    val isGuardrail: Boolean = false,
)

private enum class InsightSender {
    USER,
    ASSISTANT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiInsightsRoute(
    currentInsight: String?,
    currentActionTip: String?,
    isInsightLoading: Boolean,
    insightClient: DashboardInsightClient,
    insightMetrics: DashboardInsightMetrics,
    onBack: () -> Unit,
) {
    val messages = remember { mutableStateListOf<InsightMessage>() }
    val quickPrompts = remember {
        listOf(
            QuickPrompt("top_category", "Top spending category"),
            QuickPrompt("spending_trend", "Spending trend"),
            QuickPrompt("budget_status", "Budget status"),
            QuickPrompt("saving_opportunity", "Saving opportunity"),
        )
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var isSending by rememberSaveable { mutableStateOf(false) }
    var hasSeededInitialMessage by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(currentInsight, currentActionTip, isInsightLoading) {
        if (hasSeededInitialMessage) {
            return@LaunchedEffect
        }
        if (isInsightLoading && currentInsight.isNullOrBlank()) {
            return@LaunchedEffect
        }

        val introText = currentInsight
            ?: "Your latest AI insight will appear here once the dashboard has enough trusted data."
        messages += InsightMessage(
            id = "initial-insight",
            sender = InsightSender.ASSISTANT,
            text = introText,
            supportingText = currentActionTip ?: "Ask a follow-up or tap one of the guided prompts below.",
        )
        hasSeededInitialMessage = true
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun submitPrompt(templateKey: String? = null, question: String? = null, label: String) {
        if (isSending) return
        val trimmedQuestion = question?.trim().orEmpty()
        if (templateKey == null && trimmedQuestion.isBlank()) return

        messages += InsightMessage(
            id = "user-${messages.size}",
            sender = InsightSender.USER,
            text = if (templateKey != null) label else trimmedQuestion,
        )
        input = ""
        isSending = true

        scope.launch {
            when (
                val result = insightClient.chat(
                    period = "monthly",
                    templateKey = templateKey,
                    question = question?.trim(),
                    metrics = insightMetrics,
                )
            ) {
                is DashboardInsightChatResult.Success -> {
                    val warningText = result.warnings.firstOrNull()?.replace('_', ' ')
                    val supporting = listOfNotNull(result.actionTip, warningText).joinToString("  ")
                        .ifBlank { null }
                    messages += InsightMessage(
                        id = "assistant-${messages.size}",
                        sender = InsightSender.ASSISTANT,
                        text = result.answer,
                        supportingText = supporting,
                        isGuardrail = result.status == "blocked" || result.promptSource == "guardrail",
                    )
                }

                is DashboardInsightChatResult.Failure -> {
                    messages += InsightMessage(
                        id = "assistant-${messages.size}",
                        sender = InsightSender.ASSISTANT,
                        text = "AI insights are unavailable right now.",
                        supportingText = result.message,
                        isGuardrail = true,
                    )
                }
            }
            isSending = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "AI Insights") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFFF8F9FA),
                ),
            )
        },
        containerColor = Color(0xFFF8F9FA),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            GuardrailCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    InsightBubble(message = message)
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Quick prompts",
                            color = Color(0xFF5F6368),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            quickPrompts.take(2).forEach { prompt ->
                                Box(modifier = Modifier.weight(1f)) {
                                    QuickPromptChip(
                                        prompt = prompt,
                                        enabled = !isSending,
                                        onClick = {
                                            submitPrompt(
                                                templateKey = prompt.templateKey,
                                                label = prompt.label,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            quickPrompts.drop(2).forEach { prompt ->
                                Box(modifier = Modifier.weight(1f)) {
                                    QuickPromptChip(
                                        prompt = prompt,
                                        enabled = !isSending,
                                        onClick = {
                                            submitPrompt(
                                                templateKey = prompt.templateKey,
                                                label = prompt.label,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 112.dp),
                        enabled = !isSending,
                        placeholder = {
                            Text("Ask about spending patterns or current budget status")
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Read-only AI guidance from trusted dashboard metrics",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF6B7280),
                            fontSize = 12.sp,
                        )
                        Button(
                            onClick = {
                                submitPrompt(
                                    question = input,
                                    label = input.trim(),
                                )
                            },
                            enabled = !isSending && input.isNotBlank(),
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                            } else {
                                Text("Ask")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuardrailCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Budget guardrails",
                color = Color(0xFF9A3412),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "You can ask for read-only coaching about spending and current budget pressure. AI will not set, edit, or approve budgets, and it only answers from trusted dashboard metrics.",
                color = Color(0xFF7C2D12),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun QuickPromptChip(
    prompt: QuickPrompt,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                text = prompt.label,
                maxLines = 2,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.White,
            labelColor = Color(0xFF374151),
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = enabled,
            borderColor = Color(0xFFE5E7EB),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InsightBubble(message: InsightMessage) {
    val isAssistant = message.sender == InsightSender.ASSISTANT
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Top,
    ) {
        if (isAssistant) {
            BubbleAvatar(
                background = if (message.isGuardrail) Color(0xFFFED7AA) else Color(0xFFEDE9FE),
                text = if (message.isGuardrail) "G" else "AI",
                textColor = if (message.isGuardrail) Color(0xFF9A3412) else Color(0xFF6D28D9),
            )
        }

        Card(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .fillMaxWidth(0.82f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    !isAssistant -> Color(0xFF00A86B)
                    message.isGuardrail -> Color(0xFFFFFBEB)
                    else -> Color.White
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message.text,
                    color = if (isAssistant) Color(0xFF111827) else Color.White,
                    fontSize = 14.sp,
                )
                if (!message.supportingText.isNullOrBlank()) {
                    Text(
                        text = message.supportingText,
                        color = if (isAssistant) Color(0xFF6B7280) else Color(0xFFD1FAE5),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        if (!isAssistant) {
            BubbleAvatar(
                background = Color(0xFFD1FAE5),
                text = "You",
                textColor = Color(0xFF047857),
            )
        }
    }
}

@Composable
private fun BubbleAvatar(
    background: Color,
    text: String,
    textColor: Color,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
