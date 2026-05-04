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
import com.snapledger.R
import com.snapledger.feature.scan.domain.CameraPermissionState
import com.snapledger.feature.scan.domain.OcrExtractionPhase
import com.snapledger.feature.scan.domain.ParserPhase
import com.snapledger.feature.scan.domain.PendingCapture
import com.snapledger.feature.scan.domain.ScanCapturePhase
import com.snapledger.feature.scan.domain.ScanUiState
import com.snapledger.feature.scan.vm.ScanViewModel
import java.io.File

// --- ScanRoute remains unchanged as it handles the ViewModel connections ---
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
        onToggleCamera = viewModel::toggleCameraActiveState,
        onBack = onBack,
        onRequestPermission = {
            hasRequestedPermission = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onOpenAppSettings = { context.openAppSettings() },
        onCaptureRequested = { viewModel.prepareCapture(context.cacheDir) },
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

// --- The Redesigned ScanScreen ---
@Composable
fun ScanScreen(
    uiState: ScanUiState,
    onToggleCamera: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .padding(top = 24.dp) // Status bar padding
    ) {
        // 1. Top Bar (Close button & Title)
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

            // Spacer to balance the layout so title stays perfectly centered
            Spacer(modifier = Modifier.width(40.dp))
        }

        // 2. Camera Preview Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp)
        ) {
            CameraCardDesign(
                uiState = uiState,
                onToggleCamera = onToggleCamera,
                onCameraPreviewReady = onCameraPreviewReady,
                onCameraFailure = onCameraFailure,
                onImageCaptureReady = { imageCapture = it },
                onRequestPermission = onRequestPermission,
                onOpenAppSettings = onOpenAppSettings
            )
        }

        // 3. Dynamic Footer Button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Determine button state based on backend flow
            val buttonText = when {
                uiState.canContinueToReview -> "Review Receipt"
                uiState.parser.phase == ParserPhase.Running -> "Parsing Data..."
                uiState.canRunParser -> "Analyze Receipt"
                uiState.ocr.phase == OcrExtractionPhase.Running -> "Extracting Text..."
                uiState.canRunOcr -> "Extract Text"
                uiState.capturePhase == ScanCapturePhase.Capturing -> "Capturing..."
                else -> "Capture Receipt"
            }

            val isButtonEnabled = uiState.canCapture || uiState.canRunOcr || uiState.canRunParser || uiState.canContinueToReview

            androidx.compose.material3.Button(
                onClick = {
                    when {
                        uiState.canContinueToReview -> onOpenReview()
                        uiState.canRunParser -> onParseRequested()
                        uiState.canRunOcr -> onOcrRequested()
                        uiState.canCapture -> {
                            val pendingCapture = onCaptureRequested() ?: return@Button
                            if (imageCapture != null) {
                                capturePhoto(context, imageCapture!!, pendingCapture, onCaptureSucceeded, onCaptureFailed)
                            }
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

            // Optional: Error display if something fails
            val errorText = uiState.cameraErrorMessage ?: uiState.ocr.errorMessage ?: uiState.parser.errorMessage
            if (errorText != null) {
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun CameraCardDesign(
    uiState: ScanUiState,
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
            .clickable { onToggleCamera() }, // This makes the whole card a toggle switch!
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // 1. Camera Stream OR Inactive Placeholder Layer
            if (uiState.isCameraActive) {
                when (uiState.permissionState) {
                    CameraPermissionState.Granted -> {
                        CameraPreviewSurface(
                            sessionId = uiState.cameraSessionId,
                            showLoading = uiState.capturePhase == ScanCapturePhase.PreviewLoading,
                            onCameraPreviewReady = onCameraPreviewReady,
                            onCameraFailure = onCameraFailure,
                            onImageCaptureReady = onImageCaptureReady,
                        )
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
                // Inactive State Placeholder
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.camera), // Your custom camera drawable
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

            // 2. Green Reticle Overlay (Frame)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .border(2.dp, Color(0xFF00E676), RoundedCornerShape(16.dp))
            )

            // 3. AI Badge Overlay
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
                        painter = painterResource(id = R.drawable.astroid), // Your custom star drawable
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

// --- Retained Backend Logic Functions ---

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
            .clip(RoundedCornerShape(24.dp)) // Ensures the camera doesn't overflow the rounded card
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