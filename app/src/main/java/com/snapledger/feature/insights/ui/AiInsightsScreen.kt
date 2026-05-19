package com.snapledger.feature.insights.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.R
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

fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick
    )
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
                title = {
                    Text(
                        text = "AI Insights",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F1F1F)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF1F1F1F)
                        )
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    GuardrailCard()
                }

                items(messages, key = { it.id }) { message ->
                    InsightBubble(message = message)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .shadow(elevation = 16.dp, spotColor = Color(0x1A000000), ambientColor = Color.Transparent)
            ) {
                AnimatedVisibility(
                    visible = !isSending,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(quickPrompts) { prompt ->
                            QuickPromptPill(
                                label = prompt.label,
                                onClick = {
                                    submitPrompt(
                                        templateKey = prompt.templateKey,
                                        label = prompt.label,
                                    )
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(Color(0xFFF5F5F5), RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            enabled = !isSending,
                            textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF1F1F1F)),
                            singleLine = true,
                            cursorBrush = SolidColor(Color(0xFF00A86B)),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (input.isEmpty()) {
                                        Text(
                                            text = "Ask a question...",
                                            color = Color(0xFF9E9E9E),
                                            fontSize = 15.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (input.isNotBlank() && !isSending) Color(0xFF00A86B) else Color(0xFFE0E0E0))
                            .noRippleClickable(enabled = input.isNotBlank() && !isSending) {
                                submitPrompt(question = input, label = input.trim())
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF757575)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Send,
                                contentDescription = "Send",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp).padding(start = 4.dp)
                            )
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = "Info",
                tint = Color(0xFF7B1FA2),
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = "AI Coaching Mode",
                    color = Color(0xFF4A148C),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Responses are generated securely based on your local dashboard metrics. The AI cannot edit or alter your budgets.",
                    color = Color(0xFF6A1B9A),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun QuickPromptPill(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = label,
            color = Color(0xFF424242),
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun InsightBubble(message: InsightMessage) {
    val isAssistant = message.sender == InsightSender.ASSISTANT

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isAssistant) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFFF5F3FF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.astroid_single),
                    contentDescription = "AI",
                    tint = Color(0xFF7F22FE),
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        val bubbleShape = if (isAssistant) {
            RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
        } else {
            RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
        }

        val bgColor = when {
            !isAssistant -> Color(0xFF00A86B)
            message.isGuardrail -> Color(0xFFFFF9C4)
            else -> Color.White
        }

        val textColor = if (isAssistant) Color(0xFF1F1F1F) else Color.White

        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = bgColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message.text,
                    color = textColor,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
                if (!message.supportingText.isNullOrBlank()) {
                    Text(
                        text = message.supportingText,
                        color = if (isAssistant) Color(0xFF757575) else Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}