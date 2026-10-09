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

@Composable
fun ScannerDialog(onCode: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Сканирование кода") }, text = {
        if (granted) BarcodeCamera(Modifier.fillMaxWidth().height(330.dp), onCode) else Text("Для сканирования нужен доступ к камере. Разрешите его в настройках телефона.")
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

@SuppressLint("UnsafeOptInUsageError")
@Composable private fun BarcodeCamera(modifier: Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    AndroidView(factory = { ctx ->
        PreviewView(ctx).also { previewView ->
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val scanner = BarcodeScanning.getClient()
                // Не накапливаем старые кадры: для кассы важнее быстро прочитать актуальный QR-код.
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { analyzer ->
                    analyzer.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        val media = imageProxy.image
                        if (media == null) imageProxy.close() else scanner.process(InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)).addOnSuccessListener { codes -> codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue?.let(onCode) }.addOnCompleteListener { imageProxy.close() }
                    }
                }
                provider.unbindAll(); provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
        }
    }, modifier = modifier)
}
