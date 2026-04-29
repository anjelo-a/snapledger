package com.snapledger.feature.scan.ui

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
import com.snapledger.feature.scan.domain.ScanUiState
import com.snapledger.feature.scan.vm.ScanViewModel

@Composable
fun ScanRoute(
    viewModel: ScanViewModel,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
) {
    ScanScreen(
        uiState = viewModel.uiState,
        onBack = onBack,
        onCaptureRequested = viewModel::onCaptureRequested,
        onOcrRequested = viewModel::onOcrRequested,
        onParseRequested = viewModel::onParseRequested,
        onOpenReview = onOpenReview,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    uiState: ScanUiState,
    onBack: () -> Unit,
    onCaptureRequested: () -> Unit,
    onOcrRequested: () -> Unit,
    onParseRequested: () -> Unit,
    onOpenReview: () -> Unit,
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
                text = uiState.status,
                style = MaterialTheme.typography.headlineSmall,
            )

            PlaceholderStatusCard(
                title = "CameraX capture",
                body = uiState.captureStatus,
                todo = "TODO: wire real preview/capture flow in feature/scan when CameraX implementation starts.",
            )
            PlaceholderStatusCard(
                title = "OCR extraction",
                body = uiState.ocrStatus,
                todo = "TODO: hand captured image/text blocks to ML Kit without embedding OCR logic in Compose.",
            )
            PlaceholderStatusCard(
                title = "Deterministic parser",
                body = uiState.parserStatus,
                todo = "TODO: call only deterministic parser logic after OCR normalization. No LLM parsing.",
            )

            Button(
                onClick = onCaptureRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Mark Camera TODO")
            }
            Button(
                onClick = onOcrRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Mark OCR TODO")
            }
            Button(
                onClick = onParseRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Mark Parser TODO")
            }
            Button(
                onClick = onOpenReview,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.canContinueToReview,
            ) {
                Text(text = "Open Review Placeholder")
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
private fun PlaceholderStatusCard(
    title: String,
    body: String,
    todo: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = todo,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
