package com.snapledger.feature.entry.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val amountRegex = Regex("^\\d*\\.?\\d*\$")
private val dateFormatter = SimpleDateFormat("MM/dd/yyyy", Locale.US)

enum class TransactionType { EXPENSE, INCOME }

data class CategoryUiModel(
    val name: String,
    val iconResId: Int,
    val tintColor: Color
)

data class AddTransactionUiState(
    val type: TransactionType = TransactionType.EXPENSE,
    val amount: String = "",
    val merchant: String = "",
    val date: String = "",
    val note: String = "",
    val selectedCategory: String = "",
    val isCustomCategoryInputVisible: Boolean = false,
    val customCategoryName: String = "",
    val categories: List<CategoryUiModel> = emptyList(),
    val isSaving: Boolean = false
) {
    val isSaveEnabled: Boolean
        get() = amount.isNotBlank() &&
                amount.toDoubleOrNull() != null &&
                amount.toDouble() > 0 &&
                merchant.isNotBlank() &&
                date.isNotBlank() &&
                selectedCategory.isNotBlank()
}

sealed class AddTransactionEvent {
    data class OnTypeChanged(val type: TransactionType) : AddTransactionEvent()
    data class OnAmountChanged(val amount: String) : AddTransactionEvent()
    data class OnMerchantChanged(val merchant: String) : AddTransactionEvent()
    data class OnDateChanged(val date: String) : AddTransactionEvent()
    data class OnNoteChanged(val note: String) : AddTransactionEvent()
    data class OnCategorySelected(val categoryName: String) : AddTransactionEvent()
    object OnToggleCustomCategory : AddTransactionEvent()
    data class OnCustomCategoryNameChanged(val name: String) : AddTransactionEvent()
    object OnAddCustomCategory : AddTransactionEvent()
    object OnSaveClicked : AddTransactionEvent()
}

@Composable
fun AddTransactionRoute(
    onBack: () -> Unit
) {
    val defaultCategories = remember {
        listOf(
            CategoryUiModel("Food", R.drawable.utensils, Color(0xFF4CAF50)),
            CategoryUiModel("Transport", R.drawable.car, Color(0xFF009688)),
            CategoryUiModel("Shopping", R.drawable.shopping_basket, Color(0xFFE91E63)),
            CategoryUiModel("Bills", R.drawable.receipt, Color(0xFFFFC107)),
            CategoryUiModel("Entertainment", R.drawable.film, Color(0xFF9C27B0)),
            CategoryUiModel("Health", R.drawable.heart_pulse, Color(0xFFFF9800)),
            CategoryUiModel("Other", R.drawable.box, Color(0xFF9E9E9E))
        )
    }

    var state by remember { mutableStateOf(AddTransactionUiState(categories = defaultCategories)) }

    AddTransactionScreen(
        uiState = state,
        onEvent = { event ->
            when (event) {
                is AddTransactionEvent.OnTypeChanged -> state = state.copy(type = event.type, selectedCategory = "")
                is AddTransactionEvent.OnAmountChanged -> state = state.copy(amount = event.amount)
                is AddTransactionEvent.OnMerchantChanged -> state = state.copy(merchant = event.merchant)
                is AddTransactionEvent.OnDateChanged -> state = state.copy(date = event.date)
                is AddTransactionEvent.OnNoteChanged -> state = state.copy(note = event.note)
                is AddTransactionEvent.OnCategorySelected -> {
                    if (event.categoryName == "New") {
                        state = state.copy(isCustomCategoryInputVisible = !state.isCustomCategoryInputVisible)
                    } else {
                        state = state.copy(selectedCategory = event.categoryName, isCustomCategoryInputVisible = false)
                    }
                }
                is AddTransactionEvent.OnToggleCustomCategory -> state = state.copy(isCustomCategoryInputVisible = !state.isCustomCategoryInputVisible)
                is AddTransactionEvent.OnCustomCategoryNameChanged -> state = state.copy(customCategoryName = event.name)
                is AddTransactionEvent.OnAddCustomCategory -> {
                    if (state.customCategoryName.isNotBlank()) {
                        val newCategory = CategoryUiModel(state.customCategoryName, R.drawable.astroid, Color(0xFF00A86B))
                        state = state.copy(
                            categories = state.categories + newCategory,
                            selectedCategory = state.customCategoryName,
                            customCategoryName = "",
                            isCustomCategoryInputVisible = false
                        )
                    }
                }
                is AddTransactionEvent.OnSaveClicked -> {
                    if (state.isSaveEnabled) {
                        onBack()
                    }
                }
            }
        },
        onBack = onBack
    )
}

@Composable
fun AddTransactionScreen(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .padding(top = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE0E0E0), CircleShape)
                    .noRippleClickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color(0xFF424242))
            }

            AnimatedContent(
                targetState = uiState.type,
                transitionSpec = { fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300)) },
                label = "TitleAnimation"
            ) { type ->
                Text(
                    text = if (type == TransactionType.EXPENSE) "Add Expense" else "Add Income",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F1F1F)
                )
            }

            val checkBgColor by animateColorAsState(if (uiState.isSaveEnabled) Color(0xFF00C875) else Color(0xFFBDBDBD), label = "SaveBtnColor")
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(checkBgColor)
                    .noRippleClickable(enabled = uiState.isSaveEnabled) { onEvent(AddTransactionEvent.OnSaveClicked) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "Save", tint = Color.White)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { TransactionTypeSwitcher(uiState.type, onEvent) }
            item { AmountCard(uiState.amount, onEvent) }
            item { DetailsCard(uiState, onEvent) }
            item { CategoryCard(uiState, onEvent) }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun TransactionTypeSwitcher(
    currentType: TransactionType,
    onEvent: (AddTransactionEvent) -> Unit
) {
    val isExpense = currentType == TransactionType.EXPENSE

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color.White, RoundedCornerShape(28.dp))
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(28.dp))
            .padding(4.dp)
    ) {
        val pillWidth = this.maxWidth / 2

        val offsetX by animateDpAsState(
            targetValue = if (isExpense) 0.dp else pillWidth,
            animationSpec = tween(300),
            label = "SlideAnimation"
        )
        val bgColor by animateColorAsState(
            targetValue = if (isExpense) Color(0xFFFF5252) else Color(0xFF00C875),
            animationSpec = tween(300),
            label = "ColorAnimation"
        )

        Box(
            modifier = Modifier
                .width(pillWidth)
                .fillMaxHeight()
                .offset(x = offsetX)
                .background(bgColor, RoundedCornerShape(24.dp))
        )

        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .noRippleClickable { onEvent(AddTransactionEvent.OnTypeChanged(TransactionType.EXPENSE)) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Expense",
                    color = if (isExpense) Color.White else Color(0xFF757575),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .noRippleClickable { onEvent(AddTransactionEvent.OnTypeChanged(TransactionType.INCOME)) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Income",
                    color = if (isExpense) Color(0xFF757575) else Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun AmountCard(
    amount: String,
    onEvent: (AddTransactionEvent) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 32.dp, horizontal = 24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Amount", fontSize = 14.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("$", fontSize = 36.sp, color = if (amount.isEmpty()) Color(0xFFBDBDBD) else Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.width(4.dp))
                BasicTextField(
                    value = amount,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || newValue.matches(amountRegex)) {
                            onEvent(AddTransactionEvent.OnAmountChanged(newValue))
                        }
                    },
                    textStyle = TextStyle(fontSize = 36.sp, color = Color(0xFF1F1F1F), textAlign = TextAlign.Start),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = SolidColor(Color(0xFF00A86B)),
                    modifier = Modifier.width(IntrinsicSize.Min).widthIn(min = 40.dp),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (amount.isEmpty()) {
                                Text("0.00", color = Color(0xFFBDBDBD), fontSize = 36.sp)
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DetailsCard(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Merchant", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            AddTransactionInputField(
                value = uiState.merchant,
                onValueChange = { onEvent(AddTransactionEvent.OnMerchantChanged(it)) },
                hint = "e.g. Whole Foods"
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Date", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            AddTransactionDateField(
                value = uiState.date,
                onValueChange = { onEvent(AddTransactionEvent.OnDateChanged(it)) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Note", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            AddTransactionInputField(
                value = uiState.note,
                onValueChange = { onEvent(AddTransactionEvent.OnNoteChanged(it)) },
                hint = "Optional"
            )
        }
    }
}

@Composable
private fun CategoryCard(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Category", fontSize = 14.sp, color = Color(0xFF757575), modifier = Modifier.padding(bottom = 16.dp))

            val totalItems = uiState.categories.size + 1
            val rows = remember(uiState.categories) { (0 until totalItems).chunked(4) }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                rows.forEach { rowIndices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        rowIndices.forEach { index ->
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                                if (index < uiState.categories.size) {
                                    val category = uiState.categories[index]
                                    CategoryItem(
                                        category = category,
                                        isSelected = uiState.selectedCategory == category.name,
                                        onClick = { onEvent(AddTransactionEvent.OnCategorySelected(category.name)) }
                                    )
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .noRippleClickable { onEvent(AddTransactionEvent.OnCategorySelected("New")) }
                                            .padding(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(16.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Rounded.Add, contentDescription = "New", tint = Color(0xFF757575))
                                        }
                                        Text("New", fontSize = 12.sp, color = Color(0xFF757575), modifier = Modifier.padding(top = 8.dp))
                                    }
                                }
                            }
                        }
                        repeat(4 - rowIndices.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = uiState.isCustomCategoryInputVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AddTransactionInputField(
                        value = uiState.customCategoryName,
                        onValueChange = { onEvent(AddTransactionEvent.OnCustomCategoryNameChanged(it)) },
                        hint = "Custom category name",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.Button(
                        onClick = { onEvent(AddTransactionEvent.OnAddCustomCategory) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C875)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Add")
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(
    category: CategoryUiModel,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(if (isSelected) category.tintColor.copy(alpha = 0.15f) else Color.Transparent, label = "catBg")
    val iconTint = if (isSelected) category.tintColor else Color(0xFFBDBDBD)
    val textWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
    val textColor = if (isSelected) Color(0xFF1F1F1F) else Color(0xFF757575)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .noRippleClickable { onClick() }
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(bgColor, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = category.iconResId),
                contentDescription = category.name,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = category.name,
            fontSize = 12.sp,
            color = textColor,
            fontWeight = textWeight,
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun AddTransactionInputField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFFF8F9FA), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF1F1F1F)),
            singleLine = true,
            cursorBrush = SolidColor(Color(0xFF00A86B)),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(text = hint, color = Color(0xFFBDBDBD), fontSize = 16.sp)
                        }
                        innerTextField()
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTransactionDateField(
    value: String,
    onValueChange: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    if (showDialog) {
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        onValueChange(dateFormatter.format(Date(millis)))
                    }
                    showDialog = false
                }) {
                    Text("OK", color = Color(0xFF00C875))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel", color = Color(0xFF757575))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFFF8F9FA), RoundedCornerShape(12.dp))
            .noRippleClickable { showDialog = true }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            if (value.isEmpty()) {
                Text(text = "MM/DD/YYYY", color = Color(0xFFBDBDBD), fontSize = 16.sp)
            } else {
                Text(text = value, color = Color(0xFF1F1F1F), fontSize = 16.sp)
            }
        }
    }
}

//custom modifier to remove ripples

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