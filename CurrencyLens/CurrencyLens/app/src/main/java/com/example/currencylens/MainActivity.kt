package com.example.currencylens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ratesRepo by lazy { RatesRepository(applicationContext) }

    /** null = auto-detect from the symbol on the menu */
    @Volatile private var sourceCurrency: String? = null
    @Volatile private var targetCurrency: String = "IDR"

    private val currencies = listOf(
        "USD", "EUR", "GBP", "JPY", "IDR", "SGD", "MYR", "THB", "VND",
        "PHP", "CNY", "KRW", "INR", "AUD", "AED", "CHF", "HKD", "TWD", "SAR"
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupSpinners()
        fetchRates()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------------------------------------------------------------- UI

    private fun setupSpinners() {
        val sourceSpinner = findViewById<Spinner>(R.id.sourceSpinner)
        val targetSpinner = findViewById<Spinner>(R.id.targetSpinner)

        val sourceItems = listOf("Auto") + currencies
        sourceSpinner.adapter = spinnerAdapter(sourceItems)
        targetSpinner.adapter = spinnerAdapter(currencies)

        targetSpinner.setSelection(currencies.indexOf(targetCurrency))

        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                sourceCurrency = if (pos == 0) null else sourceItems[pos]
                updateStatus()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        targetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                targetCurrency = currencies[pos]
                updateStatus()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun fetchRates() {
        lifecycleScope.launch {
            val ok = ratesRepo.refreshIfStale()
            updateStatus(liveFetchFailed = !ok && !ratesRepo.isLive)
        }
    }

    private fun updateStatus(liveFetchFailed: Boolean = false) {
        val src = sourceCurrency
        val sample = if (src != null) {
            val converted = ratesRepo.convert(1.0, src, targetCurrency)
            if (converted != null) "1 $src = ${formatMoney(converted, targetCurrency)}" else ""
        } else {
            "Auto-detect ➜ $targetCurrency"
        }
        val suffix = when {
            liveFetchFailed -> " · offline rates (approx.)"
            ratesRepo.isLive -> ""
            else -> " · updating rates…"
        }
        runOnUiThread { statusText.text = sample + suffix }
    }

    // ---------------------------------------------------------------- Camera

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) } }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        // Dimensions of the upright (rotated) image — ML Kit boxes are in this frame
        val imgW = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        val imgH = if (rotation == 90 || rotation == 270) proxy.width else proxy.height

        recognizer.process(input)
            .addOnSuccessListener { text -> handleText(text, imgW, imgH) }
            .addOnCompleteListener { proxy.close() }
    }

    // ---------------------------------------------------------------- Detection → overlay

    private fun handleText(text: Text, imgW: Int, imgH: Int) {
        val src = sourceCurrency
        val target = targetCurrency
        val chips = mutableListOf<OverlayView.Chip>()

        // Map from upright-image coords to PreviewView coords (FILL_CENTER)
        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val scale = max(viewW / imgW, viewH / imgH)
        val dx = (viewW - imgW * scale) / 2f
        val dy = (viewH - imgH * scale) / 2f

        fun mapRect(r: Rect) = RectF(
            r.left * scale + dx, r.top * scale + dy,
            r.right * scale + dx, r.bottom * scale + dy
        )

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val matches = PriceDetector.findPrices(line.text, src)
                for (m in matches) {
                    if (m.currency == target) continue // nothing to convert
                    val converted = ratesRepo.convert(m.amount, m.currency, target) ?: continue
                    val box = boxForMatch(line, m) ?: continue
                    chips += OverlayView.Chip(mapRect(box), formatMoney(converted, target))
                }
            }
        }
        overlay.submit(chips)
    }

    /** Union of bounding boxes of the OCR elements that overlap the matched char range. */
    private fun boxForMatch(line: Text.Line, m: PriceDetector.PriceMatch): Rect? {
        val lineText = line.text
        var cursor = 0
        var union: Rect? = null
        for (el in line.elements) {
            val idx = lineText.indexOf(el.text, cursor)
            if (idx < 0) continue
            val start = idx
            val end = idx + el.text.length
            cursor = end
            if (start < m.end && end > m.start) {
                val b = el.boundingBox ?: continue
                union = union?.apply { union(b) } ?: Rect(b)
            }
        }
        return union ?: line.boundingBox
    }

    // ---------------------------------------------------------------- Formatting

    private fun formatMoney(amount: Double, code: String): String {
        val zeroDecimal = setOf("IDR", "JPY", "KRW", "VND")
        val nf = NumberFormat.getNumberInstance(Locale.US).apply {
            if (code in zeroDecimal) {
                maximumFractionDigits = 0
            } else {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
        }
        return "${displaySymbol(code)}${nf.format(amount)}"
    }

    private fun displaySymbol(code: String): String = when (code) {
        "IDR" -> "Rp"
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "JPY" -> "¥"
        "SGD" -> "S$"
        "MYR" -> "RM"
        "THB" -> "฿"
        "VND" -> "₫"
        "INR" -> "₹"
        "KRW" -> "₩"
        "PHP" -> "₱"
        "AUD" -> "A$"
        "HKD" -> "HK$"
        "TWD" -> "NT$"
        else -> "$code "
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
