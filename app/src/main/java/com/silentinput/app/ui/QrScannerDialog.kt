package com.silentinput.app.ui

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.silentinput.app.model.PairingQrPayload
import com.silentinput.app.model.PairingQrParser
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun QrScannerDialog(
    onPayloadScanned: (PairingQrPayload) -> Unit,
    onInvalidQr: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val handled = remember { AtomicBoolean(false) }
    var invalidHint by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.lanQrScanTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.lanQrScanHint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    val previewView = remember {
                        PreviewView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    }

                    DisposableEffect(lifecycleOwner) {
                        val analyzerExecutor = Executors.newSingleThreadExecutor()
                        val mainExecutor = ContextCompat.getMainExecutor(context)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        var boundProvider: ProcessCameraProvider? = null

                        val reader = MultiFormatReader().apply {
                            setHints(
                                mapOf(
                                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                    DecodeHintType.TRY_HARDER to true
                                )
                            )
                        }

                        cameraProviderFuture.addListener({
                            runCatching {
                                val provider = cameraProviderFuture.get()
                                boundProvider = provider
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { imageAnalysis ->
                                        imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                                            val rawText = decodeQrFromImageProxy(imageProxy, reader)
                                            if (rawText != null && !handled.get()) {
                                                val parsed = PairingQrParser.parse(rawText)
                                                if (parsed != null) {
                                                    if (handled.compareAndSet(false, true)) {
                                                        mainExecutor.execute {
                                                            provider.unbindAll()
                                                            onPayloadScanned(parsed)
                                                        }
                                                    }
                                                } else {
                                                    mainExecutor.execute {
                                                        invalidHint = true
                                                        onInvalidQr()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            }.onFailure { err ->
                                cameraError = err.localizedMessage ?: strings.lanQrCameraError
                            }
                        }, mainExecutor)

                        onDispose {
                            runCatching { boundProvider?.unbindAll() }
                            analyzerExecutor.shutdown()
                        }
                    }

                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (cameraError != null) {
                    Text(
                        text = cameraError ?: strings.lanQrCameraError,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (invalidHint) {
                    Text(
                        text = strings.lanQrInvalidErr,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.btnCancel)
            }
        }
    )
}

@Composable
internal fun QrIpPickerDialog(
    payload: PairingQrPayload,
    reachableIps: List<String>,
    onSelectIp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val reachableSet = remember(reachableIps) { reachableIps.toSet() }
    val orderedIps = remember(payload.ips, reachableIps) {
        (reachableIps + payload.ips.filterNot { it in reachableSet }).distinct()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.lanQrPickIpTitle(payload.name), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.lanQrPickIpHint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                orderedIps.forEach { ip ->
                    val reachable = ip in reachableSet
                    OutlinedButton(
                        onClick = { onSelectIp(ip) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = PairingQrParser.formatEndpoint(ip, payload.port),
                                fontWeight = if (reachable) FontWeight.SemiBold else FontWeight.Normal
                            )
                            if (reachable) {
                                Text(
                                    text = strings.lanQrReachableTag,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.btnCancel)
            }
        }
    )
}

private fun decodeQrFromImageProxy(imageProxy: ImageProxy, reader: MultiFormatReader): String? {
    return try {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val yBytes = ByteArray(width * height)
        if (rowStride == width) {
            buffer.get(yBytes, 0, minOf(buffer.remaining(), yBytes.size))
        } else {
            val rowBuf = ByteArray(rowStride)
            for (y in 0 until height) {
                val toRead = minOf(rowStride, buffer.remaining())
                if (toRead < width) break
                buffer.get(rowBuf, 0, toRead)
                System.arraycopy(rowBuf, 0, yBytes, y * width, width)
            }
        }
        val source = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)
        try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            reader.reset()
            runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            }.getOrNull()
        }
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
        imageProxy.close()
    }
}
