package com.snapledger.feature.budget.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.R
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// OPTIMIZATION: Hoisted formatters and Regex
private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("en", "US"))
private val amountRegex = Regex("^\\d*\\.?\\d*\$")

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
fun BudgetRoute() {
    val categories = remember { mutableStateListOf<BudgetCategoryUiModel>() }
    var period by remember { mutableStateOf(BudgetPeriod.MONTHLY) }

    val state = BudgetUiState(
        period = period,
        categories = categories
    )

    BudgetScreen(
        uiState = state,
        onPeriodChanged = { period = it },
        onSaveNewCategory = { name, allocated ->
            categories.add(
                BudgetCategoryUiModel(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    iconResId = R.drawable.box, // Default icon requested
                    tintColor = Color(0xFF00C875),
                    spent = 0.0,
                    allocated = allocated
                )
            )
        },
        onSaveEditedCategory = { id, name, allocated ->
            val index = categories.indexOfFirst { it.id == id }
            if (index != -1) {
                categories[index] = categories[index].copy(name = name, allocated = allocated)
            }
        },
        onDeleteCategory = { id ->
            categories.removeAll { it.id == id }
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
        // --- 1. STICKY HEADER ---
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

        // --- 2. SCROLLABLE AREA WITH FADE ---
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Period Switcher
                item {
                    BudgetPeriodSwitcher(
                        currentPeriod = uiState.period,
                        onPeriodChanged = onPeriodChanged
                    )
                }

                // Main Budget Summary Card (Only show if there are categories)
                if (uiState.categories.isNotEmpty()) {
                    item {
                        MainBudgetCard(uiState)
                    }
                }

                // Categories Header
                item {
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

                // Animated Add Category Card
                item {
                    // FIX: Wrapped in Column to provide the required ColumnScope
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

                // Category List or Empty State
                if (uiState.categories.isEmpty() && !isAddingCategory) {
                    item {
                        BudgetEmptyState()
                    }
                } else {
                    items(uiState.categories, key = { it.id }) { category ->
                        BudgetCategoryCard(
                            category = category,
                            onEditClicked = { categoryToEdit = category }
                        )
                    }
                }
            }

            // The 12.dp Fading Gradient Overlay
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

    // Edit Category Dialog
    if (categoryToEdit != null) {
        EditCategoryDialog(
            category = categoryToEdit!!,
            onDismiss = { categoryToEdit = null },
            onSave = { name, allocated ->
                onSaveEditedCategory(categoryToEdit!!.id, name, allocated)
                categoryToEdit = null
            },
            onDelete = {
                onDeleteCategory(categoryToEdit!!.id)
                categoryToEdit = null
            }
        )
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
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val isFormValid = name.isNotBlank() && amount.toDoubleOrNull() ?: 0.0 > 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("New Category Name", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            BudgetInputField(
                value = name,
                onValueChange = { name = it },
                hint = "e.g. Groceries"
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Monthly Limit ($)", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            BudgetInputField(
                value = amount,
                onValueChange = { if (it.isEmpty() || it.matches(amountRegex)) amount = it },
                hint = "0.00",
                keyboardType = KeyboardType.Decimal
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onSave(name, amount.toDouble()) },
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
    onSave: (String, Double) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(category.name) }
    var amount by remember { mutableStateOf(category.allocated.toString()) }
    val isFormValid = name.isNotBlank() && amount.toDoubleOrNull() ?: 0.0 > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Text("Edit Category", fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
        },
        text = {
            Column {
                Text("Name", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 4.dp))
                BudgetInputField(
                    value = name,
                    onValueChange = { name = it },
                    hint = "Category name"
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Budget Limit", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 4.dp))
                BudgetInputField(
                    value = amount,
                    onValueChange = { if (it.isEmpty() || it.matches(amountRegex)) amount = it },
                    hint = "0.00",
                    keyboardType = KeyboardType.Decimal
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, amount.toDouble()) },
                enabled = isFormValid
            ) {
                Text("Save", color = if (isFormValid) Color(0xFF00C875) else Color(0xFFBDBDBD))
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = Color(0xFFFF5252))
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(text = "Remaining this month", fontSize = 14.sp, color = Color(0xFF9E9E9E))
                    Text(
                        text = currencyFormatter.format(uiState.totalRemaining).replace(".00", ""),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F1F1F),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "of ${currencyFormatter.format(uiState.totalAllocated).replace(".00", "")}", fontSize = 14.sp, color = Color(0xFF757575))
                    Text(
                        text = "${(100 - (uiState.totalPercentage * 100)).coerceAtLeast(0f).toInt()}% left",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF00C875),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { animatedProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = Color(0xFF00C875),
                trackColor = Color(0xFFE8F5E9),
                strokeCap = StrokeCap.Round
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0%", fontSize = 10.sp, color = Color(0xFFBDBDBD))
                Text("100%", fontSize = 10.sp, color = Color(0xFFBDBDBD))
            }
        }
    }
}

@Composable
private fun BudgetCategoryCard(
    category: BudgetCategoryUiModel,
    onEditClicked: () -> Unit
) {
    val progressColor = remember(category.percentage) {
        when {
            category.percentage >= 0.9f -> Color(0xFFFF5252) // Red
            category.percentage >= 0.7f -> Color(0xFFFFB300) // Yellow
            else -> Color(0xFF00C875) // Green
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = category.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1F1F1F)
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

                        val spentFormatted = currencyFormatter.format(category.spent).replace(".00", "")
                        val allocFormatted = currencyFormatter.format(category.allocated).replace(".00", "")
                        Text(
                            text = "$spentFormatted / $allocFormatted",
                            fontSize = 14.sp,
                            color = Color(0xFF757575)
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val remainingText = if (category.remaining < 0) {
                            "-${currencyFormatter.format(category.remaining * -1)} over"
                        } else {
                            "-${currencyFormatter.format(category.remaining)} left"
                        }

                        Text(
                            text = remainingText,
                            fontSize = 12.sp,
                            color = Color(0xFF9E9E9E)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .noRippleClickable { onEditClicked() }
                                .padding(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Edit",
                                tint = Color(0xFF00C875),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Edit",
                                color = Color(0xFF00C875),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
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