package com.merchseller

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.merchseller.ui.QuietAction
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScannerDialog(onCode: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Сканирование кода") }, text = {
        if (granted) BarcodeCamera(Modifier.fillMaxWidth().height(330.dp), onCode) else Text("Для сканирования нужен доступ к камере. Разрешите его в настройках телефона.")
    }, confirmButton = { QuietAction(onClick = onDismiss) { Text("Закрыть") } })
}

@SuppressLint("UnsafeOptInUsageError")
@Composable private fun BarcodeCamera(modifier: Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    val active = remember { AtomicBoolean(true) }
    val codeReported = remember { AtomicBoolean(false) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var barcodeScanner by remember { mutableStateOf<com.google.mlkit.vision.barcode.BarcodeScanner?>(null) }
    AndroidView(factory = { ctx ->
        PreviewView(ctx).also { previewView ->
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val scanner = BarcodeScanning.getClient()
                if (!active.get()) {
                    scanner.close()
                    return@addListener
                }
                cameraProvider = provider
                barcodeScanner = scanner
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                // Не накапливаем старые кадры: для кассы важнее быстро прочитать актуальный QR-код.
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { useCase ->
                    useCase.setAnalyzer(analyzerExecutor) { imageProxy ->
                        val media = imageProxy.image
                        if (media == null) {
                            imageProxy.close()
                        } else {
                            runCatching { scanner.process(InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)) }
                                .onSuccess { task ->
                                    task.addOnSuccessListener { codes ->
                                        val value = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                                        // Анализатор мог увидеть один код в нескольких соседних кадрах.
                                        if (value != null && active.get() && codeReported.compareAndSet(false, true)) {
                                            useCase.clearAnalyzer()
                                            ContextCompat.getMainExecutor(ctx).execute { if (active.get()) onCode(value) }
                                        }
                                    }.addOnCompleteListener { imageProxy.close() }
                                }
                                .onFailure { imageProxy.close() }
                        }
                    }
                }
                provider.unbindAll(); provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
        }
    }, modifier = modifier)
    DisposableEffect(lifecycle) {
        onDispose {
            active.set(false)
            cameraProvider?.unbindAll()
            barcodeScanner?.close()
            analyzerExecutor.shutdownNow()
        }
    }
}
