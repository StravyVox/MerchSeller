package com.merchseller

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.merchseller.ui.QuietAction
import com.merchseller.ui.SecondaryAction
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Composable
fun ScannerDialog(onCode: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (granted) denied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Сканирование кода") }, text = {
        if (granted) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Наведите камеру на код. После каждого товара можно сразу сканировать следующий.")
                BarcodeCamera(Modifier.fillMaxWidth().height(300.dp), onCode)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Для сканирования нужен доступ к камере.")
                SecondaryAction(onClick = { request.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Разрешить камеру")
                }
                if (denied && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    QuietAction(onClick = {
                        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки") }
                }
            }
        }
    }, confirmButton = { QuietAction(onClick = onDismiss) { Text("Закрыть") } })
}

@SuppressLint("UnsafeOptInUsageError")
@Composable private fun BarcodeCamera(modifier: Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    val active = remember { AtomicBoolean(true) }
    val lastReported = remember { AtomicReference<Pair<String, Long>?>(null) }
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
                                        // Подавляем соседние кадры с тем же кодом, но оставляем камеру открытой.
                                        val shouldReport = if (value == null || !active.get()) false else synchronized(lastReported) {
                                            val now = SystemClock.elapsedRealtime()
                                            val previous = lastReported.get()
                                            if (previous?.first == value && now - previous.second < 1_800L) false
                                            else { lastReported.set(value to now); true }
                                        }
                                        if (shouldReport && value != null) {
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
