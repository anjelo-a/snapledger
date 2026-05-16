package com.snapledger.feature.review.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.feature.review.domain.ReviewItemFieldState
import com.snapledger.feature.review.domain.ReviewUiState
import com.snapledger.feature.review.vm.ReviewViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val phCurrencyFormatter = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)

@Composable
fun ReviewRoute(
    viewModel: ReviewViewModel,
    onBack: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf("Food") }

    ReviewScreen(
        uiState = viewModel.uiState,
        selectedCategory = selectedCategory,
        onCategoryChanged = { selectedCategory = it },
        onBack = onBack,
        onMerchantChanged = viewModel::onMerchantChanged,
        onExpenseDateChanged = viewModel::onExpenseDateChanged,
        onTotalAmountChanged = viewModel::onTotalAmountChanged,
        onItemDescriptionChanged = viewModel::onItemDescriptionChanged,
        onItemAmountChanged = viewModel::onItemAmountChanged,
        onAddItemRequested = viewModel::onAddItemRequested,
        onRemoveItemRequested = viewModel::onRemoveItemRequested,
        onSaveRequested = viewModel::onSaveRequested,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
    selectedCategory: String,
    onCategoryChanged: (String) -> Unit,
    onBack: () -> Unit,
    onMerchantChanged: (String) -> Unit,
    onExpenseDateChanged: (String) -> Unit,
    onTotalAmountChanged: (String) -> Unit,
    onItemDescriptionChanged: (Int, String) -> Unit,
    onItemAmountChanged: (Int, String) -> Unit,
    onAddItemRequested: () -> Unit,
    onRemoveItemRequested: (Int) -> Unit,
    onSaveRequested: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .padding(top = 24.dp)
    ) {
        // TOP BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE0E0E0), CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = Color(0xFF424242),
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = "Review Receipt",
                color = Color(0xFF1F1F1F),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )

            val checkBgColor = if (uiState.saveEnabled) Color(0xFF00C875) else Color(0xFFBDBDBD)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(checkBgColor)
                    .clickable(enabled = uiState.saveEnabled && !uiState.isSaving) { onSaveRequested() },
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Save",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = 8.dp,
                bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            item {
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = "AI Extracted",
                            tint = Color(0xFF00C875),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Extracted with 94% confidence. Tap any field to edit.",
                            color = Color(0xFF2E7D32),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(text = "Merchant", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
                        MinimalTextField(
                            value = uiState.merchant.value,
                            onValueChange = onMerchantChanged,
                            hint = "Enter merchant name",
                            isError = uiState.merchant.errorMessage != null
                        )
                        if (uiState.merchant.errorMessage != null) {
                            Text(text = uiState.merchant.errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(text = "Date", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
                        ReviewDatePickerField(
                            value = uiState.expenseDate.value,
                            onValueChange = onExpenseDateChanged,
                            isError = uiState.expenseDate.errorMessage != null
                        )
                        if (uiState.expenseDate.errorMessage != null) {
                            Text(text = uiState.expenseDate.errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(text = "Category", fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 8.dp))
                        val categories = listOf("Food", "Transport", "Shopping", "Bills", "Entertainment")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categories.forEach { category ->
                                val isSelected = category == selectedCategory
                                Surface(
                                    color = if (isSelected) Color(0xFF00C875) else Color.White,
                                    shape = RoundedCornerShape(20.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) Color.Transparent else Color(0xFFE0E0E0)),
                                    modifier = Modifier.clickable { onCategoryChanged(category) }
                                ) {
                                    Text(
                                        text = category,
                                        color = if (isSelected) Color.White else Color(0xFF757575),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Line Items", fontSize = 16.sp, color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium)
                            Text(text = "${uiState.items.size} items", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        uiState.items.forEach { item ->
                            ReviewItemRow(
                                item = item,
                                onDescriptionChanged = { onItemDescriptionChanged(item.id, it) },
                                onAmountChanged = { onItemAmountChanged(item.id, it) },
                                onRemoveRequested = { onRemoveItemRequested(item.id) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        TextButton(
                            onClick = onAddItemRequested,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        ) {
                            Text(text = "+ Add missing item", color = Color(0xFF00C875), fontSize = 14.sp)
                        }

                        HorizontalDivider(color = Color(0xFFF5F5F5), thickness = 1.dp)

                        Spacer(modifier = Modifier.height(16.dp))

                        val subtotal = uiState.items.mapNotNull { it.amount.toDoubleOrNull() }.sum()
                        val formattedSubtotal = phCurrencyFormatter.format(subtotal)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Subtotal", fontSize = 14.sp, color = Color(0xFF9E9E9E))
                            Text(text = formattedSubtotal, fontSize = 14.sp, color = Color(0xFF757575))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Total", fontSize = 16.sp, color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium)

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "₱", fontSize = 16.sp, color = Color(0xFF757575), modifier = Modifier.padding(end = 8.dp))
                                Box(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(40.dp)
                                        .border(1.dp, if (uiState.totalAmount.errorMessage != null) MaterialTheme.colorScheme.error else Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    BasicTextField(
                                        value = uiState.totalAmount.value,
                                        onValueChange = onTotalAmountChanged,
                                        textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF1F1F1F), textAlign = TextAlign.End),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        cursorBrush = SolidColor(Color(0xFF00A86B)),
                                        modifier = Modifier.fillMaxWidth(),
                                        decorationBox = { innerTextField ->
                                            Box(contentAlignment = Alignment.CenterEnd) {
                                                if (uiState.totalAmount.value.isEmpty()) {
                                                    Text(text = "0.00", color = Color(0xFFBDBDBD), fontSize = 16.sp)
                                                }
                                                innerTextField()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        if (uiState.totalAmount.errorMessage != null) {
                            Text(
                                text = uiState.totalAmount.errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 10.sp,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                textAlign = TextAlign.End
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Merchant, date and total are required. Items are optional.",
                            color = Color(0xFFBDBDBD),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isError: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(if (isError) Color(0xFFFFEBEE) else Color(0xFFF8F9FA), RoundedCornerShape(12.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
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
private fun ReviewDatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean
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
            .background(if (isError) Color(0xFFFFEBEE) else Color(0xFFF8F9FA), RoundedCornerShape(12.dp))
            .clickable { showDialog = true }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(text = "YYYY-MM-DD", color = Color(0xFFBDBDBD), fontSize = 16.sp)
        } else {
            Text(text = value, color = Color(0xFF1F1F1F), fontSize = 16.sp)
        }
    }
}

@Composable
private fun ReviewItemRow(
    item: ReviewItemFieldState,
    onDescriptionChanged: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onRemoveRequested: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = "Edit",
            tint = Color(0xFFBDBDBD),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))

        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = item.description,
                onValueChange = onDescriptionChanged,
                textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF1F1F1F)),
                singleLine = true,
                cursorBrush = SolidColor(Color(0xFF00A86B)),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.description.isEmpty()) {
                            Text(text = "Item name", color = Color(0xFFBDBDBD), fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .width(80.dp)
                .height(36.dp)
                .background(Color(0xFFF8F9FA), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = item.amount,
                onValueChange = onAmountChanged,
                textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF1F1F1F)),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = SolidColor(Color(0xFF00A86B)),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.amount.isEmpty()) {
                            Text(text = "0.00", color = Color(0xFFBDBDBD), fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Icon(
            imageVector = Icons.Rounded.Delete,
            contentDescription = "Delete",
            tint = Color(0xFFBDBDBD),
            modifier = Modifier
                .size(20.dp)
                .clickable { onRemoveRequested() }
        )
    }
}