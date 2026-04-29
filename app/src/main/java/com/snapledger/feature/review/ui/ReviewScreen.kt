package com.snapledger.feature.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snapledger.feature.review.domain.ReviewFieldState
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
        onSaveRequested = viewModel::onSaveRequested,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
    onBack: () -> Unit,
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = uiState.subtitle,
                style = MaterialTheme.typography.headlineSmall,
            )

            ReviewValueCard(field = uiState.merchant)
            ReviewValueCard(field = uiState.expenseDate)
            ReviewValueCard(field = uiState.totalAmount)
            uiState.items.forEach { field ->
                ReviewValueCard(field = field)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Warnings and TODO boundaries",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    uiState.warnings.forEach { warning ->
                        Text(
                            text = "- $warning",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = "TODO: add editing state and validation here after deterministic parser output is available.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = onSaveRequested,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.saveEnabled,
            ) {
                Text(text = "Local Save Placeholder")
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
private fun ReviewValueCard(field: ReviewFieldState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = field.value,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
