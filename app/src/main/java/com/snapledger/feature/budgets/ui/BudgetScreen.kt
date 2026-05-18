package com.snapledger.feature.budget.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.R
import com.snapledger.core.categories.expenseTransactionCategories
import com.snapledger.core.ledger.LedgerBudgetPeriod
import com.snapledger.core.ledger.LedgerRepository
import com.snapledger.core.ledger.LedgerSnapshot
import com.snapledger.core.ledger.LedgerTransactionType
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
private val amountInputRegex = Regex("^\\d{0,3}(,\\d{3})*(\\.\\d*)?$|^\\d*(\\.\\d*)?$")
private fun formatPhp(amount: Double): String = currencyFormatter.format(amount).replace("₱", "PHP ")

private data class BudgetCategoryOption(
    val name: String,
    val iconResId: Int,
    val tintColor: Color,
)

private val budgetCategoryOptions = expenseTransactionCategories().map { category ->
    BudgetCategoryOption(
        name = category.name,
        iconResId = category.iconResId,
        tintColor = category.tintColor,
    )
}

enum class BudgetPeriod { WEEKLY, MONTHLY }

data class BudgetCategoryUiModel(
    val id: String,
    val name: String,
    val iconResId: Int,
    val tintColor: Color,
    val spent: Double,
    val allocated: Double
) {
    val percentage: Float get() = if (allocated > 0) (spent / allocated).toFloat() else 0f
    val remaining: Double get() = allocated - spent
}

data class BudgetUiState(
    val currentMonth: String = SimpleDateFormat("MMMM yyyy", Locale.US).format(Date()),
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val categories: List<BudgetCategoryUiModel> = emptyList()
) {
    val totalSpent: Double get() = categories.sumOf { it.spent }
    val totalAllocated: Double get() = categories.sumOf { it.allocated }
    val totalPercentage: Float get() = if (totalAllocated > 0) (totalSpent / totalAllocated).toFloat() else 0f

    val totalRemaining: Double get() = totalAllocated - totalSpent
}

@Composable
fun BudgetRoute(ledgerRepository: LedgerRepository) {
    val snapshot by ledgerRepository.snapshotFlow.collectAsState(initial = LedgerSnapshot())
    val coroutineScope = rememberCoroutineScope()
    var period by remember { mutableStateOf(BudgetPeriod.MONTHLY) }
    val ledgerPeriod = period.toLedgerPeriod()
    val categories = remember(snapshot, ledgerPeriod) {
        snapshot.budgetCategories
            .filter { it.period == ledgerPeriod }
            .map { category ->
                val option = category.toBudgetCategoryOption()
                val spent = snapshot.transactions
                    .filter {
                        it.type == LedgerTransactionType.EXPENSE &&
                                it.category.equals(category.name, ignoreCase = true)
                    }
                    .sumOf { it.amount }

                BudgetCategoryUiModel(
                    id = category.id,
                    name = category.name,
                    iconResId = option.iconResId,
                    tintColor = option.tintColor,
                    spent = spent,
                    allocated = category.allocated
                )
            }
    }

    val state = BudgetUiState(
        period = period,
        categories = categories
    )

    BudgetScreen(
        uiState = state,
        onPeriodChanged = { period = it },
        onSaveNewCategory = { name, allocated ->
            coroutineScope.launch {
                ledgerRepository.saveBudgetCategory(
                    name = name,
                    period = period.toLedgerPeriod(),
                    allocated = allocated,
                )
            }
        },
        onSaveEditedCategory = { id, name, allocated ->
            coroutineScope.launch {
                ledgerRepository.updateBudgetCategory(id = id, name = name, allocated = allocated)
            }
        },
        onDeleteCategory = { id ->
            coroutineScope.launch {
                ledgerRepository.deleteBudgetCategory(id)
            }
        }
    )
}

@Composable
fun BudgetScreen(
    uiState: BudgetUiState,
    onPeriodChanged: (BudgetPeriod) -> Unit,
    onSaveNewCategory: (String, Double) -> Unit,
    onSaveEditedCategory: (String, String, Double) -> Unit,
    onDeleteCategory: (String) -> Unit
) {
    var isAddingCategory by remember { mutableStateOf(false) }
    var categoryToEdit by remember { mutableStateOf<BudgetCategoryUiModel?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .padding(top = 24.dp)
    ) {
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
            Text(
                text = "Budget",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Text(
                text = uiState.currentMonth,
                fontSize = 12.sp,
                color = Color(0xFF757575),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = 8.dp,
                    bottom = 40.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(contentType = "period_switcher") {
                    BudgetPeriodSwitcher(
                        currentPeriod = uiState.period,
                        onPeriodChanged = onPeriodChanged
                    )
                }

                if (uiState.categories.isNotEmpty()) {
                    item(contentType = "main_budget_card") {
                        MainBudgetCard(uiState)
                    }
                }

                item(contentType = "categories_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Categories",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F1F1F)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .noRippleClickable { isAddingCategory = !isAddingCategory }
                                .padding(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isAddingCategory) Icons.Rounded.Close else Icons.Rounded.Add,
                                contentDescription = "Toggle category",
                                tint = Color(0xFF00C875),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isAddingCategory) "Cancel" else "Add category",
                                color = Color(0xFF00C875),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }

                item(contentType = "add_category_inline") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AnimatedVisibility(
                            visible = isAddingCategory,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            AddCategoryInlineCard(
                                onSave = { name, amount ->
                                    onSaveNewCategory(name, amount)
                                    isAddingCategory = false
                                }
                            )
                        }
                    }
                }

                if (uiState.categories.isEmpty() && !isAddingCategory) {
                    item(contentType = "empty_state") {
                        BudgetEmptyState()
                    }
                } else {
                    items(uiState.categories, key = { it.id }, contentType = { "category_card" }) { category ->
                        SwipeRevealCategoryCard(
                            category = category,
                            onEditClicked = { categoryToEdit = category },
                            onDeleteClicked = { onDeleteCategory(category.id) }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFF8F9FA), Color(0x00F8F9FA))
                        )
                    )
            )
        }
    }

    if (categoryToEdit != null) {
        EditCategoryDialog(
            category = categoryToEdit!!,
            onDismiss = { categoryToEdit = null },
            onSave = { name, allocated ->
                onSaveEditedCategory(categoryToEdit!!.id, name, allocated)
                categoryToEdit = null
            }
        )
    }
}

@Composable
private fun SwipeRevealCategoryCard(
    category: BudgetCategoryUiModel,
    onEditClicked: () -> Unit,
    onDeleteClicked: () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val revealWidth = 144.dp
    val density = LocalDensity.current
    val revealWidthPx = remember(density) { with(density) { revealWidth.toPx() } }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .graphicsLayer {
                    val progress = (revealWidthPx + offsetX.value) / revealWidthPx
                    translationX = -progress * (revealWidthPx * 0.25f)
                    this.alpha = (1f - progress).coerceIn(0f, 1f)
                },
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(60.dp)
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF00A86B))
                    .clickable {
                        coroutineScope.launch { offsetX.animateTo(0f) }
                        onEditClicked()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Edit, tint = Color(0xFFFFFFFF), contentDescription = "Edit")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(60.dp)
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFF5252))
                    .clickable {
                        coroutineScope.launch { offsetX.animateTo(0f) }
                        onDeleteClicked()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Delete, tint = Color(0xFFFFFFFF), contentDescription = "Delete")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            coroutineScope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(-revealWidthPx, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (offsetX.value < -revealWidthPx / 2) {
                                    offsetX.animateTo(
                                        targetValue = -revealWidthPx,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                } else {
                                    offsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        }
                    )
                }
        ) {
            BudgetCategoryCard(category = category)
        }
    }
}

@Composable
private fun BudgetEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFFEEEEEE), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.box),
                contentDescription = "Empty",
                tint = Color(0xFFBDBDBD),
                modifier = Modifier.size(32.dp)
            )
        }
        Text(
            text = "No budgets set yet.",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF757575),
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = "Tap '+ Add category' to start managing your expenses.",
            fontSize = 14.sp,
            color = Color(0xFF9E9E9E),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 8.dp, start = 32.dp, end = 32.dp)
        )
    }
}

@Composable
private fun AddCategoryInlineCard(onSave: (String, Double) -> Unit) {
    var selectedCategory by remember { mutableStateOf(budgetCategoryOptions.first()) }
    var amount by remember { mutableStateOf("") }
    val isFormValid = (amount.toAmountOrNull() ?: 0.0) > 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Category", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            BudgetCategoryPicker(
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Monthly Limit (PHP)", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            BudgetInputField(
                value = amount,
                onValueChange = {
                    val formatted = it.toFormattedAmountInput()
                    if (formatted.isEmpty() || formatted.matches(amountInputRegex)) amount = formatted
                },
                hint = "0.00",
                keyboardType = KeyboardType.Decimal
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onSave(selectedCategory.name, amount.toAmountOrNull() ?: 0.0) },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C875)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Save Category", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun EditCategoryDialog(
    category: BudgetCategoryUiModel,
    onDismiss: () -> Unit,
    onSave: (String, Double) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(category.toBudgetCategoryOption()) }
    var amount by remember { mutableStateOf(category.allocated.toFormattedAmountValue()) }
    val isFormValid = (amount.toAmountOrNull() ?: 0.0) > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Text("Edit Category", fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
        },
        text = {
            Column {
                Text("Category", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
                BudgetCategoryPicker(
                    selectedCategory = selectedCategory,
                    onCategorySelected = { selectedCategory = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Budget Limit", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 4.dp))
                BudgetInputField(
                    value = amount,
                    onValueChange = {
                        val formatted = it.toFormattedAmountInput()
                        if (formatted.isEmpty() || formatted.matches(amountInputRegex)) amount = formatted
                    },
                    hint = "0.00",
                    keyboardType = KeyboardType.Decimal
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selectedCategory.name, amount.toAmountOrNull() ?: 0.0) },
                enabled = isFormValid
            ) {
                Text("Save", color = if (isFormValid) Color(0xFF00C875) else Color(0xFFBDBDBD))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF757575))
            }
        }
    )
}

@Composable
private fun BudgetInputField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFFF8F9FA), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF1F1F1F)),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            cursorBrush = SolidColor(Color(0xFF00A86B)),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(text = hint, color = Color(0xFFBDBDBD), fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                }
            }
        )
    }
}

@Composable
private fun BudgetCategoryPicker(
    selectedCategory: BudgetCategoryOption,
    onCategorySelected: (BudgetCategoryOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        budgetCategoryOptions.chunked(3).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEach { option ->
                    val isSelected = option.name == selectedCategory.name
                    Surface(
                        color = if (isSelected) option.tintColor.copy(alpha = 0.16f) else Color(0xFFF8F9FA),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) option.tintColor else Color(0xFFE0E0E0),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onCategorySelected(option) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(id = option.iconResId),
                                contentDescription = option.name,
                                tint = if (isSelected) option.tintColor else Color(0xFF757575),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = option.name,
                                color = if (isSelected) Color(0xFF1F1F1F) else Color(0xFF757575),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
                repeat(3 - rowOptions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BudgetPeriodSwitcher(
    currentPeriod: BudgetPeriod,
    onPeriodChanged: (BudgetPeriod) -> Unit
) {
    val isWeekly = currentPeriod == BudgetPeriod.WEEKLY

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color.White, RoundedCornerShape(24.dp))
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(24.dp))
            .padding(4.dp)
    ) {
        val pillWidth = this.maxWidth / 2

        val offsetX by animateDpAsState(
            targetValue = if (isWeekly) 0.dp else pillWidth,
            animationSpec = tween(300),
            label = "SlideAnimation"
        )
        val bgColor by animateColorAsState(
            targetValue = Color(0xFF00C875),
            animationSpec = tween(300),
            label = "ColorAnimation"
        )

        Box(
            modifier = Modifier
                .width(pillWidth)
                .fillMaxHeight()
                .offset(x = offsetX)
                .background(bgColor, RoundedCornerShape(20.dp))
        )

        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .noRippleClickable { onPeriodChanged(BudgetPeriod.WEEKLY) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Weekly",
                    color = if (isWeekly) Color.White else Color(0xFF757575),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .noRippleClickable { onPeriodChanged(BudgetPeriod.MONTHLY) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Monthly",
                    color = if (!isWeekly) Color.White else Color(0xFF757575),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun MainBudgetCard(uiState: BudgetUiState) {
    val animatedProgress by animateFloatAsState(
        targetValue = uiState.totalPercentage,
        animationSpec = tween(1000),
        label = "MainProgress"
    )

    val progressColor = remember(uiState.totalPercentage) {
        when {
            uiState.totalPercentage >= 0.9f -> Color(0xFFFF5252)
            uiState.totalPercentage >= 0.7f -> Color(0xFFFFB300)
            else -> Color(0xFF00C875)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Remaining this month",
                fontSize = 14.sp,
                color = Color(0xFF757575),
                fontWeight = FontWeight.Medium
            )

            Text(
                text = formatPhp(uiState.totalRemaining).replace(".00", ""),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )

            Text(
                text = "of ${formatPhp(uiState.totalAllocated).replace(".00", "")}",
                fontSize = 14.sp,
                color = Color(0xFF757575),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            LinearProgressIndicator(
                progress = { animatedProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color = progressColor,
                trackColor = Color(0xFFF5F5F5),
                strokeCap = StrokeCap.Round
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${(uiState.totalPercentage * 100).toInt()}% used",
                    fontSize = 14.sp,
                    color = progressColor,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${(100 - (uiState.totalPercentage * 100)).coerceAtLeast(0f).toInt()}% left",
                    fontSize = 14.sp,
                    color = progressColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun BudgetCategoryCard(
    category: BudgetCategoryUiModel
) {
    val progressColor = remember(category.percentage) {
        when {
            category.percentage >= 0.9f -> Color(0xFFFF5252)
            category.percentage >= 0.7f -> Color(0xFFFFB300)
            else -> Color(0xFF00C875)
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = category.percentage,
        animationSpec = tween(1000),
        label = "CategoryProgress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(category.tintColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = category.iconResId),
                        contentDescription = category.name,
                        tint = category.tintColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            Text(
                                text = category.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1F1F1F),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            if (category.percentage >= 0.7f) {
                                Surface(
                                    color = progressColor.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = "${(category.percentage * 100).toInt()}%",
                                        color = progressColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        val spentFormatted = formatPhp(category.spent).replace(".00", "")
                        val allocFormatted = formatPhp(category.allocated).replace(".00", "")
                        Text(
                            text = "$spentFormatted / $allocFormatted",
                            fontSize = 14.sp,
                            color = Color(0xFF757575),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { animatedProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = progressColor,
                        trackColor = Color(0xFFF5F5F5),
                        strokeCap = StrokeCap.Round
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val remainingText = if (category.remaining < 0) {
                        "-${formatPhp(category.remaining * -1)} over"
                    } else {
                        "-${formatPhp(category.remaining)} left"
                    }

                    Text(
                        text = remainingText,
                        fontSize = 12.sp,
                        color = Color(0xFF9E9E9E)
                    )
                }
            }
        }
    }
}

private fun BudgetPeriod.toLedgerPeriod(): LedgerBudgetPeriod {
    return when (this) {
        BudgetPeriod.WEEKLY -> LedgerBudgetPeriod.WEEKLY
        BudgetPeriod.MONTHLY -> LedgerBudgetPeriod.MONTHLY
    }
}

private fun BudgetCategoryUiModel.toBudgetCategoryOption(): BudgetCategoryOption {
    return budgetCategoryOptions.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: budgetCategoryOptions.last()
}

private fun com.snapledger.core.ledger.LedgerBudgetCategory.toBudgetCategoryOption(): BudgetCategoryOption {
    return budgetCategoryOptions.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: budgetCategoryOptions.last()
}

private fun String.toAmountOrNull(): Double? {
    return replace(",", "").toDoubleOrNull()
}

private fun String.toFormattedAmountInput(): String {
    val raw = replace(",", "")
        .filterIndexed { index, char -> char.isDigit() || (char == '.' && substring(0, index).none { it == '.' }) }
    if (raw.isBlank()) return ""

    val whole = raw.substringBefore(".").trimStart('0').ifBlank { "0" }
    val decimal = raw.substringAfter(".", missingDelimiterValue = "")
    val groupedWhole = whole.reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

    return if (raw.contains(".")) {
        "$groupedWhole.$decimal"
    } else {
        groupedWhole
    }
}

private fun Double.toFormattedAmountValue(): String {
    val text = if (this % 1.0 == 0.0) {
        toLong().toString()
    } else {
        toString()
    }
    return text.toFormattedAmountInput()
}

@Composable
fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    return this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick
    )
}