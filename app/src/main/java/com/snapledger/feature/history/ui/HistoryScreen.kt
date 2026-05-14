package com.snapledger.feature.history.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class QuickFilter { ALL, EXPENSE, INCOME }

data class HistoryTransactionUiModel(
    val id: String,
    val title: String,
    val category: String,
    val note: String?,
    val amount: Double,
    val isIncome: Boolean,
    val dateString: String
)

data class HistoryUiState(
    val searchQuery: String = "",
    val quickFilter: QuickFilter = QuickFilter.ALL,
    val isAdvancedFilterVisible: Boolean = false,
    val startDate: String = "",
    val endDate: String = "",
    val minAmount: String = "",
    val maxAmount: String = "",
    val selectedCategory: String = "All",
    val totalResults: Int = 0,
    val groupedTransactions: Map<String, List<HistoryTransactionUiModel>> = emptyMap()
)

sealed class HistoryEvent {
    data class OnSearchQueryChanged(val query: String) : HistoryEvent()
    data class OnQuickFilterSelected(val filter: QuickFilter) : HistoryEvent()
    object OnToggleAdvancedFilters : HistoryEvent()
    data class OnCategorySelected(val category: String) : HistoryEvent()
    data class OnTransactionClicked(val id: String) : HistoryEvent()

    data class OnStartDateChanged(val date: String) : HistoryEvent()
    data class OnEndDateChanged(val date: String) : HistoryEvent()
    data class OnMinAmountChanged(val amount: String) : HistoryEvent()
    data class OnMaxAmountChanged(val amount: String) : HistoryEvent()

    // NEW EVENT: Added clear functionality for Advanced Search
    object OnClearAdvancedFilters : HistoryEvent()
}

@Composable
fun HistoryRoute(
    onNavigateToDetail: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var quickFilter by remember { mutableStateOf(QuickFilter.ALL) }
    var isAdvancedFilterVisible by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("All") }

    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var minAmount by remember { mutableStateOf("") }
    var maxAmount by remember { mutableStateOf("") }

    val allTransactions = remember {
        listOf<HistoryTransactionUiModel>()
    }

    val filteredTransactions = allTransactions.filter { transaction ->
        val matchesSearch = transaction.title.contains(searchQuery, ignoreCase = true) ||
                transaction.category.contains(searchQuery, ignoreCase = true)

        val matchesQuickFilter = when (quickFilter) {
            QuickFilter.ALL -> true
            QuickFilter.EXPENSE -> !transaction.isIncome
            QuickFilter.INCOME -> transaction.isIncome
        }

        val matchesCategory = if (selectedCategory == "All") true else transaction.category.equals(selectedCategory, ignoreCase = true)

        matchesSearch && matchesQuickFilter && matchesCategory
    }

    val grouped = filteredTransactions.groupBy { it.dateString }

    val currentState = HistoryUiState(
        searchQuery = searchQuery,
        quickFilter = quickFilter,
        isAdvancedFilterVisible = isAdvancedFilterVisible,
        selectedCategory = selectedCategory,
        startDate = startDate,
        endDate = endDate,
        minAmount = minAmount,
        maxAmount = maxAmount,
        totalResults = filteredTransactions.size,
        groupedTransactions = grouped
    )

    HistoryScreen(
        uiState = currentState,
        onEvent = { event ->
            when (event) {
                is HistoryEvent.OnSearchQueryChanged -> searchQuery = event.query
                is HistoryEvent.OnQuickFilterSelected -> quickFilter = event.filter
                is HistoryEvent.OnToggleAdvancedFilters -> isAdvancedFilterVisible = !isAdvancedFilterVisible
                is HistoryEvent.OnCategorySelected -> selectedCategory = event.category
                is HistoryEvent.OnStartDateChanged -> startDate = event.date
                is HistoryEvent.OnEndDateChanged -> endDate = event.date
                is HistoryEvent.OnMinAmountChanged -> minAmount = event.amount
                is HistoryEvent.OnMaxAmountChanged -> maxAmount = event.amount
                is HistoryEvent.OnTransactionClicked -> onNavigateToDetail(event.id)
                // clear advanced search shits
                is HistoryEvent.OnClearAdvancedFilters -> {
                    startDate = ""
                    endDate = ""
                    minAmount = ""
                    maxAmount = ""
                    selectedCategory = "All"
                }
            }
        }
    )
}

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onEvent: (HistoryEvent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .padding(top = 24.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = "Transactions",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )

            Text(
                text = "${uiState.totalResults} results",
                fontSize = 14.sp,
                color = Color(0xFF757575),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            SearchAndFilterRow(uiState, onEvent)
            Spacer(modifier = Modifier.height(16.dp))
            QuickFiltersRow(uiState.quickFilter, onEvent)

            AnimatedVisibility(
                visible = uiState.isAdvancedFilterVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                AdvancedFilterCard(uiState, onEvent)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (uiState.groupedTransactions.isEmpty()) {
            EmptyStateView()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 24.dp, end = 24.dp, bottom = 100.dp
                )
            ) {
                uiState.groupedTransactions.forEach { (dateString, transactions) ->
                    item {
                        Text(
                            text = dateString,
                            fontSize = 12.sp,
                            color = Color(0xFF9E9E9E),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 8.dp, top = if (dateString == uiState.groupedTransactions.keys.first()) 0.dp else 16.dp)
                        )
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column {
                                transactions.forEachIndexed { index, transaction ->
                                    TransactionItemRow(transaction) {
                                        onEvent(HistoryEvent.OnTransactionClicked(transaction.id))
                                    }
                                    if (index < transactions.lastIndex) {
                                        HorizontalDivider(
                                            color = Color(0xFFF5F5F5),
                                            thickness = 1.dp,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndFilterRow(
    uiState: HistoryUiState,
    onEvent: (HistoryEvent) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_search),
                    contentDescription = "Search",
                    tint = Color(0xFF9E9E9E),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))

                BasicTextField(
                    value = uiState.searchQuery,
                    onValueChange = { onEvent(HistoryEvent.OnSearchQueryChanged(it)) },
                    textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF1F1F1F)),
                    singleLine = true,
                    cursorBrush = SolidColor(Color(0xFF00A86B)),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (uiState.searchQuery.isEmpty()) {
                                Text(
                                    text = "Search merchant",
                                    color = Color(0xFF9E9E9E),
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (uiState.isAdvancedFilterVisible) Color(0xFF00C875) else Color.White,
                    RoundedCornerShape(12.dp)
                )
                .border(
                    1.dp,
                    if (uiState.isAdvancedFilterVisible) Color.Transparent else Color(0xFFE0E0E0),
                    RoundedCornerShape(12.dp)
                )
                .clickable { onEvent(HistoryEvent.OnToggleAdvancedFilters) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.settings_sliders),
                contentDescription = "Advanced Filters",
                tint = if (uiState.isAdvancedFilterVisible) Color.White else Color(0xFF757575),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun QuickFiltersRow(
    currentFilter: QuickFilter,
    onEvent: (HistoryEvent) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterPill(
            text = "All",
            isSelected = currentFilter == QuickFilter.ALL,
            onClick = { onEvent(HistoryEvent.OnQuickFilterSelected(QuickFilter.ALL)) }
        )
        FilterPill(
            text = "Expense",
            isSelected = currentFilter == QuickFilter.EXPENSE,
            onClick = { onEvent(HistoryEvent.OnQuickFilterSelected(QuickFilter.EXPENSE)) }
        )
        FilterPill(
            text = "Income",
            isSelected = currentFilter == QuickFilter.INCOME,
            onClick = { onEvent(HistoryEvent.OnQuickFilterSelected(QuickFilter.INCOME)) }
        )
    }
}

@Composable
private fun FilterPill(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFF1F1F1F) else Color.White
    val textColor = if (isSelected) Color.White else Color(0xFF757575)
    val borderColor = if (isSelected) Color.Transparent else Color(0xFFE0E0E0)

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedFilterCard(
    uiState: HistoryUiState,
    onEvent: (HistoryEvent) -> Unit
) {
    val categories = listOf("All", "Food", "Transport", "Shopping", "Bills", "Entertainment", "Health", "Income", "Other")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Text("Date range", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AdvancedFilterDateInput(
                    modifier = Modifier.weight(1f),
                    value = uiState.startDate,
                    onValueChange = { onEvent(HistoryEvent.OnStartDateChanged(it)) },
                    hint = "Start Date"
                )
                AdvancedFilterDateInput(
                    modifier = Modifier.weight(1f),
                    value = uiState.endDate,
                    onValueChange = { onEvent(HistoryEvent.OnEndDateChanged(it)) },
                    hint = "End Date"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Amount range", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AdvancedFilterNumberInput(
                    modifier = Modifier.weight(1f),
                    value = uiState.minAmount,
                    onValueChange = { onEvent(HistoryEvent.OnMinAmountChanged(it)) },
                    hint = "Min ₱"
                )
                AdvancedFilterNumberInput(
                    modifier = Modifier.weight(1f),
                    value = uiState.maxAmount,
                    onValueChange = { onEvent(HistoryEvent.OnMaxAmountChanged(it)) },
                    hint = "Max ₱"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Category", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { category ->
                    val isSelected = category == uiState.selectedCategory
                    Surface(
                        color = if (isSelected) Color(0xFF00C875) else Color.White,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) Color.Transparent else Color(0xFFE0E0E0)),
                        modifier = Modifier.clickable { onEvent(HistoryEvent.OnCategorySelected(category)) }
                    ) {
                        Text(
                            text = category,
                            color = if (isSelected) Color.White else Color(0xFF757575),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // NEW: Clear Filters Button
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onEvent(HistoryEvent.OnClearAdvancedFilters) }) {
                    Text("Clear Filters", color = Color(0xFF00C875), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedFilterDateInput(
    modifier: Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String
) {
    var showDialog by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    if (showDialog) {
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val formatter = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                        onValueChange(formatter.format(Date(millis)))
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
        modifier = modifier
            .height(40.dp)
            .background(Color(0xFFF8F9FA), RoundedCornerShape(10.dp))
            .clickable { showDialog = true }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (value.isEmpty()) "MM/DD/YYYY" else value,
                color = if (value.isEmpty()) Color(0xFFBDBDBD) else Color(0xFF1F1F1F),
                fontSize = 14.sp
            )
            Icon(
                imageVector = Icons.Rounded.DateRange,
                contentDescription = "Select Date",
                tint = Color(0xFFBDBDBD),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// NEW: Updated to restrict input to numbers and decimals only
@Composable
private fun AdvancedFilterNumberInput(
    modifier: Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .background(Color(0xFFF8F9FA), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                // Regex ensures only numbers and up to one decimal point can be typed
                if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*\$"))) {
                    onValueChange(newValue)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF1F1F1F)),
            singleLine = true,
            cursorBrush = SolidColor(Color(0xFF00A86B)),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(text = hint, color = Color(0xFFBDBDBD), fontSize = 14.sp)
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun TransactionItemRow(
    transaction: HistoryTransactionUiModel,
    onClick: () -> Unit
) {
    val iconData = when (transaction.category.lowercase()) {
        "food" -> Pair(R.drawable.utensils, Color(0xFF4CAF50))
        "transport" -> Pair(R.drawable.car, Color(0xFF009688))
        "income", "salary" -> Pair(R.drawable.hand_coins, Color(0xFF00A86B))
        "entertainment" -> Pair(R.drawable.film, Color(0xFF673AB7))
        "health" -> Pair(R.drawable.heart_pulse, Color(0xFFFF9800))
        else -> Pair(R.drawable.receipt, Color(0xFF757575))
    }

    val iconResId = iconData.first
    val iconTint = iconData.second
    val iconBgColor = iconTint.copy(alpha = 0.15f)

    val amountPrefix = if (transaction.isIncome) "+" else "-"
    val amountColor = if (transaction.isIncome) Color(0xFF00C875) else Color(0xFF1F1F1F)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = transaction.category,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(text = transaction.title, color = Color(0xFF1F1F1F), fontSize = 16.sp, fontWeight = FontWeight.Medium)

            val subText = if (transaction.note != null) "${transaction.category} · ${transaction.note}" else transaction.category
            Text(text = subText, color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }

        Text(
            text = "$amountPrefix${formatMoney(transaction.amount)}",
            color = amountColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyStateView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = R.drawable.receipt),
            contentDescription = "No transactions",
            tint = Color(0xFFE0E0E0),
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = "No transaction made yet.",
            color = Color(0xFF757575),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = "Scan a receipt or add manually to see them here.",
            color = Color(0xFF9E9E9E),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp).padding(top = 4.dp)
        )
    }
}

private fun formatMoney(amount: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
    return format.format(amount)
}
