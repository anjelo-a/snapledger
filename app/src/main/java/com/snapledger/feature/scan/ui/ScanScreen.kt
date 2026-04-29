package com.snapledger.feature.scan.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.snapledger.feature.scan.domain.CameraPermissionState
import com.snapledger.feature.scan.domain.PendingCapture
import com.snapledger.feature.scan.domain.ScanCapturePhase
import com.snapledger.feature.scan.domain.ScanUiState
import com.snapledger.feature.scan.vm.ScanViewModel
import java.io.File

@Composable
fun ScanRoute(
    viewModel: ScanViewModel,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val canRequestAgain = activity?.shouldShowCameraPermissionRationale() == true
        viewModel.onPermissionUpdated(
            granted = granted,
            canRequestPermissionAgain = canRequestAgain,
        )
    }
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel.uiState.permissionState) {
        if (viewModel.uiState.permissionState == CameraPermissionState.Unknown && !hasRequestedPermission) {
            if (context.hasCameraPermission()) {
                viewModel.onPermissionUpdated(
                    granted = true,
                    canRequestPermissionAgain = true,
                )
            } else {
                hasRequestedPermission = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    ScanScreen(
        uiState = viewModel.uiState,
        onBack = onBack,
        onRequestPermission = {
            hasRequestedPermission = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onOpenAppSettings = { context.openAppSettings() },
        onCaptureRequested = {
            viewModel.prepareCapture(context.cacheDir)
        },
        onCaptureSucceeded = viewModel::onCaptureSucceeded,
        onCaptureFailed = viewModel::onCaptureFailed,
        onCameraPreviewReady = viewModel::onCameraPreviewReady,
        onCameraFailure = viewModel::onCameraFailure,
        onRetryCapture = viewModel::onRetryCapture,
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
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onCaptureRequested: () -> PendingCapture?,
    onCaptureSucceeded: (String, String?) -> Unit,
    onCaptureFailed: (String) -> Unit,
    onCameraPreviewReady: () -> Unit,
    onCameraFailure: (String) -> Unit,
    onRetryCapture: () -> Unit,
    onOcrRequested: () -> Unit,
    onParseRequested: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val context = LocalContext.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

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
                text = uiState.status,
                style = MaterialTheme.typography.headlineSmall,
            )

            CameraPreviewCard(
                uiState = uiState,
                onCameraPreviewReady = onCameraPreviewReady,
                onCameraFailure = onCameraFailure,
                onImageCaptureReady = { imageCapture = it },
                onRequestPermission = onRequestPermission,
                onOpenAppSettings = onOpenAppSettings,
            )

            CaptureStatusCard(uiState = uiState)
            PlaceholderStatusCard(
                title = "OCR extraction",
                body = uiState.ocrStatus,
                todo = "TODO: add ML Kit OCR after the capture flow; do not run OCR from Compose.",
            )
            PlaceholderStatusCard(
                title = "Deterministic parser",
                body = uiState.parserStatus,
                todo = "TODO: call deterministic parser only after OCR normalization is added.",
            )

            Button(
                onClick = {
                    val pendingCapture = onCaptureRequested() ?: return@Button
                    val currentImageCapture = imageCapture
                    if (currentImageCapture == null) {
                        onCaptureFailed("ImageCapture is not ready yet.")
                        return@Button
                    }
                    capturePhoto(
                        context = context,
                        imageCapture = currentImageCapture,
                        pendingCapture = pendingCapture,
                        onCaptureSucceeded = onCaptureSucceeded,
                        onCaptureFailed = onCaptureFailed,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.canCapture,
            ) {
                Text(text = if (uiState.capturePhase == ScanCapturePhase.Capturing) "Capturing..." else "Capture Receipt")
            }
            OutlinedButton(
                onClick = onRetryCapture,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.canRetry,
            ) {
                Text(text = "Retry Capture")
            }
            Button(
                onClick = onOcrRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Keep OCR Deferred")
            }
            Button(
                onClick = onParseRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Keep Parser Deferred")
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
private fun CameraPreviewCard(
    uiState: ScanUiState,
    onCameraPreviewReady: () -> Unit,
    onCameraFailure: (String) -> Unit,
    onImageCaptureReady: (ImageCapture?) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "CameraX capture",
                style = MaterialTheme.typography.titleMedium,
            )

            when (uiState.permissionState) {
                CameraPermissionState.Unknown,
                CameraPermissionState.Denied -> {
                    Text(
                        text = uiState.captureStatus,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Grant Camera Permission")
                    }
                }

                CameraPermissionState.PermanentlyDenied -> {
                    Text(
                        text = uiState.captureStatus,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Open App Settings")
                    }
                }

                CameraPermissionState.Granted -> {
                    CameraPreviewSurface(
                        sessionId = uiState.cameraSessionId,
                        showLoading = uiState.capturePhase == ScanCapturePhase.PreviewLoading,
                        onCameraPreviewReady = onCameraPreviewReady,
                        onCameraFailure = onCameraFailure,
                        onImageCaptureReady = onImageCaptureReady,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewSurface(
    sessionId: Int,
    showLoading: Boolean,
    onCameraPreviewReady: () -> Unit,
    onCameraFailure: (String) -> Unit,
    onImageCaptureReady: (ImageCapture?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner, sessionId) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null

        val listener = Runnable {
            try {
                cameraProvider = cameraProviderFuture.get()
                val previewUseCase = Preview.Builder().build().also { useCase ->
                    useCase.surfaceProvider = previewView.surfaceProvider
                }
                val imageCaptureUseCase = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    imageCaptureUseCase,
                )
                onImageCaptureReady(imageCaptureUseCase)
                onCameraPreviewReady()
            } catch (error: Exception) {
                onImageCaptureReady(null)
                onCameraFailure(error.message ?: "Unable to open the camera.")
            }
        }

        cameraProviderFuture.addListener(listener, executor)

        onDispose {
            onImageCaptureReady(null)
            cameraProvider?.unbindAll()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (showLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun CaptureStatusCard(uiState: ScanUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Capture status",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = uiState.captureStatus,
                style = MaterialTheme.typography.bodyLarge,
            )
            uiState.cameraErrorMessage?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            uiState.capturedImage?.let { metadata ->
                Text(
                    text = "File: ${metadata.fileName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Path: ${metadata.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "URI: ${metadata.contentUri}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Size: ${metadata.fileSizeBytes} bytes",
                    style = MaterialTheme.typography.bodySmall,
                )
                val dimensions = if (metadata.widthPx != null && metadata.heightPx != null) {
                    "${metadata.widthPx} x ${metadata.heightPx}"
                } else {
                    "Unknown"
                }
                Text(
                    text = "Dimensions: $dimensions",
                    style = MaterialTheme.typography.bodySmall,
                )
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

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    pendingCapture: PendingCapture,
    onCaptureSucceeded: (String, String?) -> Unit,
    onCaptureFailed: (String) -> Unit,
) {
    val outputFile = File(pendingCapture.outputPath)
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onCaptureSucceeded(
                    outputPath = outputFile.absolutePath,
                    savedUri = outputFileResults.savedUri?.toString(),
                )
            }

            override fun onError(exception: ImageCaptureException) {
                onCaptureFailed(exception.message ?: "CameraX capture failed.")
            }
        },
    )
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Activity.shouldShowCameraPermissionRationale(): Boolean {
    return ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
}

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
}

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}
