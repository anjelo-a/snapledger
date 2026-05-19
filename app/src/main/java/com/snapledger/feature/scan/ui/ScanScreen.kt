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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.snapledger.R
import com.snapledger.feature.scan.domain.CameraPermissionState
import com.snapledger.feature.scan.domain.OcrExtractionPhase
import com.snapledger.feature.scan.domain.PendingCapture
import com.snapledger.feature.scan.domain.ParserPhase
import com.snapledger.feature.scan.domain.ScanCapturePhase
import com.snapledger.feature.scan.domain.ScanUiState
import com.snapledger.feature.scan.vm.ScanViewModel
import java.io.FileOutputStream
import java.io.InputStream
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun ScanRoute(
    viewModel: ScanViewModel,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    var pendingCapture by remember { mutableStateOf<PendingCapture?>(null) }

    var isScreenReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(350)
        isScreenReady = true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val canRequestAgain = activity?.shouldShowCameraPermissionRationale() == true
        viewModel.onPermissionUpdated(
            granted = granted,
            canRequestPermissionAgain = canRequestAgain,
        )
    }

    val documentScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { activityResult ->
        val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
        val pageUri = result?.pages?.firstOrNull()?.imageUri
        val pending = pendingCapture
        if (pageUri != null && pending != null) {
            val copied = copyUriToPath(
                context = context,
                sourceUri = pageUri,
                outputPath = pending.outputPath,
            )
            if (copied) {
                viewModel.onCaptureSucceeded(pending.outputPath, pageUri.toString())
            } else {
                viewModel.onCaptureFailed("Document scan output could not be copied for OCR.")
            }
        } else if (pending != null) {
            viewModel.onCaptureFailed("Document scan was cancelled or returned no page.")
        }
        pendingCapture = null
    }

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setPageLimit(1)
            .setGalleryImportAllowed(false)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val documentScanner = remember { GmsDocumentScanning.getClient(scannerOptions) }
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel.uiState.permissionState) {
        delay(300)

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
        isScreenReady = isScreenReady,
        onToggleCamera = viewModel::toggleCameraActiveState,
        onBack = onBack,
        onRequestPermission = {
            hasRequestedPermission = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onOpenAppSettings = { context.openAppSettings() },
        onCaptureRequested = { viewModel.prepareCapture(context.cacheDir) },
        onDocumentScanRequested = { pending ->
            val host = activity
            if (host == null) {
                viewModel.onCaptureFailed("Unable to launch scanner without an activity context.")
            } else {
                pendingCapture = pending
                documentScanner.getStartScanIntent(host)
                    .addOnSuccessListener { intentSender ->
                        documentScannerLauncher.launch(
                            IntentSenderRequest.Builder(intentSender).build(),
                        )
                    }
                    .addOnFailureListener { error ->
                        pendingCapture = null
                        viewModel.onCaptureFailed(error.message ?: "Document scanner launch failed.")
                    }
            }
        },
        onCaptureSucceeded = viewModel::onCaptureSucceeded,
        onCaptureFailed = viewModel::onCaptureFailed,
        onCameraPreviewReady = viewModel::onCameraPreviewReady,
        onCameraFailure = viewModel::onCameraFailure,
        onRetryCapture = viewModel::onRetryCapture,
        onOcrRequested = viewModel::onOcrRequested,
        onOpenReview = onOpenReview,
    )
}

@Composable
fun ScanScreen(
    uiState: ScanUiState,
    isScreenReady: Boolean,
    onToggleCamera: () -> Unit,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onCaptureRequested: () -> PendingCapture?,
    onDocumentScanRequested: (PendingCapture) -> Unit,
    onCaptureSucceeded: (String, String?) -> Unit,
    onCaptureFailed: (String) -> Unit,
    onCameraPreviewReady: () -> Unit,
    onCameraFailure: (String) -> Unit,
    onRetryCapture: () -> Unit,
    onOcrRequested: () -> Unit,
    onOpenReview: () -> Unit,
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
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
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = "Scan Receipt",
                color = Color(0xFF1F1F1F),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.width(40.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp)
        ) {
            if (uiState.capturedImage == null) {
                CameraCardDesign(
                    uiState = uiState,
                    isScreenReady = isScreenReady,
                    onToggleCamera = onToggleCamera,
                    onCameraPreviewReady = onCameraPreviewReady,
                    onCameraFailure = onCameraFailure,
                    onImageCaptureReady = { _ -> },
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings
                )
            } else {
                CapturedReceiptCard(uiState = uiState)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val buttonText = when {
                uiState.canContinueToReview -> "Review Receipt"
                uiState.parser.phase.name == "Running" || uiState.ocr.phase == OcrExtractionPhase.Running -> "Processing..."
                uiState.canRunOcr -> "Process Receipt"
                uiState.capturePhase == ScanCapturePhase.Capturing -> "Capturing..."
                else -> "Capture Receipt"
            }

            val isButtonEnabled = uiState.canCapture || uiState.canRunOcr || uiState.canContinueToReview

            androidx.compose.material3.Button(
                onClick = {
                    when {
                        uiState.canContinueToReview -> onOpenReview()
                        uiState.canRunOcr -> onOcrRequested()
                        uiState.canCapture -> {
                            val pendingCapture = onCaptureRequested() ?: return@Button
                            onDocumentScanRequested(pendingCapture)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp), spotColor = Color(0xFF00C875)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C875),
                    disabledContainerColor = Color(0xFF81C784)
                ),
                shape = RoundedCornerShape(16.dp),
                enabled = isButtonEnabled
            ) {
                Text(
                    text = buttonText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "Tap to extract merchant, items and total automatically",
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp)
            )

            val errorText = uiState.cameraErrorMessage ?: uiState.ocr.errorMessage ?: uiState.parser.errorMessage
            if (errorText != null) {
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            val warningLines = buildList {
                addAll(uiState.ocr.warningMessages)
                uiState.parser.candidate?.warnings?.let { addAll(it) }
            }.distinct()
            if (warningLines.isNotEmpty()) {
                Text(
                    text = warningLines.joinToString(separator = "\n") { "Warning: $it" },
                    color = Color(0xFF8A6D3B),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun CapturedReceiptCard(uiState: ScanUiState) {
    val statusText = when {
        uiState.canContinueToReview -> "Receipt processed. Ready for review."
        uiState.parser.phase == ParserPhase.Running || uiState.ocr.phase == OcrExtractionPhase.Running -> "Processing receipt..."
        else -> "Receipt captured. Tap Process Receipt."
    }
    Card(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(id = R.drawable.receipt),
                    contentDescription = "Captured receipt",
                    tint = Color(0xFF00E676),
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CameraCardDesign(
    uiState: ScanUiState,
    isScreenReady: Boolean,
    onToggleCamera: () -> Unit,
    onCameraPreviewReady: () -> Unit,
    onCameraFailure: (String) -> Unit,
    onImageCaptureReady: (ImageCapture?) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .clickable { onToggleCamera() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            if (uiState.isCameraActive) {
                when (uiState.permissionState) {
                    CameraPermissionState.Granted -> {
                        if (isScreenReady) {
                            CameraPreviewSurface(
                                sessionId = uiState.cameraSessionId,
                                showLoading = uiState.capturePhase == ScanCapturePhase.PreviewLoading,
                                onCameraPreviewReady = onCameraPreviewReady,
                                onCameraFailure = onCameraFailure,
                                onImageCaptureReady = onImageCaptureReady,
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1C1A))) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center),
                                    color = Color(0xFF00E676)
                                )
                            }
                        }
                    }
                    CameraPermissionState.Denied -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Camera access required", color = Color.White)
                            androidx.compose.material3.Button(
                                onClick = onRequestPermission,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("Grant Permission")
                            }
                        }
                    }
                    CameraPermissionState.PermanentlyDenied -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Camera disabled in settings", color = Color.White)
                            androidx.compose.material3.Button(
                                onClick = onOpenAppSettings,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("Open Settings")
                            }
                        }
                    }
                    else -> {}
                }
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.camera),
                        contentDescription = "Open Camera",
                        tint = Color(0xFF9E9E9E),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Tap to open camera",
                        color = Color(0xFF9E9E9E),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .border(2.dp, Color(0xFF00E676), RoundedCornerShape(16.dp))
            )

            Surface(
                color = Color(0xFF262626),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(start = 24.dp, top = 24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.astroid),
                        contentDescription = "AI Ready",
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "AI Ready",
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 6.dp)
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
            .fillMaxSize()
            .background(Color.Transparent),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (showLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF00E676)
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
                    outputFile.absolutePath,
                    outputFileResults.savedUri?.toString(),
                )
            }

            override fun onError(exception: ImageCaptureException) {
                onCaptureFailed(exception.message ?: "CameraX capture failed.")
            }
        },
    )
}

private fun copyUriToPath(
    context: Context,
    sourceUri: Uri,
    outputPath: String,
): Boolean {
    return runCatching {
        context.contentResolver.openInputStream(sourceUri).use { input: InputStream? ->
            if (input == null) return@runCatching false
            File(outputPath).parentFile?.mkdirs()
            FileOutputStream(outputPath).use { output ->
                input.copyTo(output)
            }
            true
        } ?: false
    }.getOrDefault(false)
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