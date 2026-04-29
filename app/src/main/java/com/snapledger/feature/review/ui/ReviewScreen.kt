package com.snapledger.feature.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snapledger.feature.review.domain.ReviewEditableFieldState
import com.snapledger.feature.review.domain.ReviewItemFieldState
import com.snapledger.feature.review.domain.ReviewUiState
import com.snapledger.feature.review.vm.ReviewViewModel

@Composable
fun ReviewRoute(
    viewModel: ReviewViewModel,
    onBack: () -> Unit,
) {
    ReviewScreen(
        uiState = viewModel.uiState,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = uiState.title) },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = uiState.subtitle,
                style = MaterialTheme.typography.headlineSmall,
            )

            EditableFieldCard(
                field = uiState.merchant,
                supportingText = "Required",
                onValueChange = onMerchantChanged,
            )
            EditableFieldCard(
                field = uiState.expenseDate,
                supportingText = "Required, YYYY-MM-DD",
                onValueChange = onExpenseDateChanged,
            )
            EditableFieldCard(
                field = uiState.totalAmount,
                supportingText = "Required, positive amount",
                onValueChange = onTotalAmountChanged,
            )

            ReviewWarningsCard(warnings = uiState.warnings)
            ReviewItemsCard(
                items = uiState.items,
                onAddItemRequested = onAddItemRequested,
                onDescriptionChanged = onItemDescriptionChanged,
                onAmountChanged = onItemAmountChanged,
                onRemoveItemRequested = onRemoveItemRequested,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Validation",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (uiState.saveEnabled) {
                            "Required fields are valid. Save is enabled."
                        } else {
                            "Merchant, expense date, and total amount must all be valid before save is enabled."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = uiState.saveStatusMessage,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = onSaveRequested,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.saveEnabled,
            ) {
                Text(text = "Save Placeholder")
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Back")
            }
        }
    }
}

@Composable
private fun EditableFieldCard(
    field: ReviewEditableFieldState,
    supportingText: String,
    onValueChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = field.value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = field.label) },
                isError = field.errorMessage != null,
                singleLine = true,
                supportingText = {
                    Text(text = field.errorMessage ?: supportingText)
                },
            )
        }
    }
}

@Composable
private fun ReviewWarningsCard(warnings: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Parser warnings",
                style = MaterialTheme.typography.titleMedium,
            )
            if (warnings.isEmpty()) {
                Text(
                    text = "No parser warnings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                warnings.forEach { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewItemsCard(
    items: List<ReviewItemFieldState>,
    onAddItemRequested: () -> Unit,
    onDescriptionChanged: (Int, String) -> Unit,
    onAmountChanged: (Int, String) -> Unit,
    onRemoveItemRequested: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Items",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onAddItemRequested) {
                    Text(text = "Add Item")
                }
            }

            if (items.isEmpty()) {
                Text(
                    text = "Items can be partial or empty in Phase 2.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                items.forEach { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = item.description,
                                onValueChange = { onDescriptionChanged(item.id, it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(text = "Item description") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = item.amount,
                                onValueChange = { onAmountChanged(item.id, it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(text = "Item amount") },
                                singleLine = true,
                                supportingText = {
                                    Text(text = "Optional during Phase 2 review")
                                },
                            )
                            TextButton(
                                onClick = { onRemoveItemRequested(item.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = "Remove Item")
                            }
                        }
                    }
                }
            }
        }
    }
}
