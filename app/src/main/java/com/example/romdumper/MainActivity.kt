package com.example.romdumper

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var llPartitions: LinearLayout
    private lateinit var btnExtract: Button

    private var payloadFile: File? = null
    private var manifest: PayloadManifest? = null

    private val checkBoxes = mutableMapOf<String, CheckBox>()
    private val flashButtons = mutableMapOf<String, Button>()
    private val extractedFiles = mutableMapOf<String, File>()

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) handlePickedFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        llPartitions = findViewById(R.id.llPartitions)
        btnExtract = findViewById(R.id.btnExtract)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }
        btnExtract.setOnClickListener { extractSelected() }
    }

    // ---- File selection -------------------------------------------------

    private fun queryDisplayName(uri: Uri): String {
        var name = "fayl"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx) ?: name
        }
        return name
    }

    private fun handlePickedFile(uri: Uri) {
        val displayName = queryDisplayName(uri)

        llPartitions.removeAllViews()
        checkBoxes.clear()
        flashButtons.clear()
        extractedFiles.clear()
        btnExtract.isEnabled = false

        tvStatus.text = "Hazırlanır: $displayName"
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pFile = preparePayloadFile(uri, displayName)
                val m = PayloadParser.parseHeader(pFile)

                withContext(Dispatchers.Main) {
                    payloadFile = pFile
                    manifest = m
                    progressBar.isIndeterminate = false
                    progressBar.visibility = View.GONE
                    tvStatus.text = "${m.partitions.size} bölmə tapıldı (minor_version=${m.minorVersion})"
                    buildPartitionRows(m)
                    btnExtract.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvStatus.text = "Xəta: ${e.message}"
                }
            }
        }
    }

    /** Copies either a bare payload.bin, or the payload.bin entry of an OTA zip, into cacheDir. */
    private fun preparePayloadFile(uri: Uri, displayName: String): File {
        val outFile = File(cacheDir, "payload.bin")
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Fayl açıla bilmədi")

        input.use { stream ->
            if (displayName.endsWith(".zip", ignoreCase = true)) {
                ZipInputStream(BufferedInputStream(stream)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val current = entry
                        if (current.name == "payload.bin") {
                            FileOutputStream(outFile).use { fos -> zis.copyTo(fos, 1 shl 20) }
                            return outFile
                        }
                        entry = zis.nextEntry
                    }
                }
                throw IOException("Zip içində payload.bin tapılmadı")
            } else {
                FileOutputStream(outFile).use { fos -> stream.copyTo(fos, 1 shl 20) }
                return outFile
            }
        }
    }

    // ---- Partition list UI ----------------------------------------------

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return "%.1f %s".format(v, units[i])
    }

    private fun buildPartitionRows(m: PayloadManifest) {
        for (part in m.partitions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cb = CheckBox(this).apply {
                text = "${part.name}  (${humanSize(part.newSize)})"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val flashBtn = Button(this).apply {
                text = "Flaş"
                isEnabled = false
                setOnClickListener { confirmAndFlash(part.name) }
            }
            row.addView(cb)
            row.addView(flashBtn)
            llPartitions.addView(row)

            checkBoxes[part.name] = cb
            flashButtons[part.name] = flashBtn
        }
    }

    // ---- Extraction -------------------------------------------------------

    private fun extractSelected() {
        val m = manifest ?: return
        val pFile = payloadFile ?: return
        val selected = m.partitions.filter { checkBoxes[it.name]?.isChecked == true }

        if (selected.isEmpty()) {
            tvStatus.text = "Heç bir bölmə seçilməyib"
            return
        }

        btnExtract.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {
            val outDir = File(getExternalFilesDir(null), "images").apply { mkdirs() }

            for ((index, part) in selected.withIndex()) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Çıxarılır: ${part.name} (${index + 1}/${selected.size})"
                }
                val outFile = File(outDir, "${part.name}.img")
                try {
                    PayloadParser.extractPartition(pFile, m, part, outFile) { frac ->
                        // overall per-op progress is fine-grained; the status text above is enough for a simple UI
                    }
                    withContext(Dispatchers.Main) {
                        extractedFiles[part.name] = outFile
                        flashButtons[part.name]?.isEnabled = true
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "${part.name}: xəta — ${e.message}"
                    }
                }
                withContext(Dispatchers.Main) {
                    progressBar.progress = ((index + 1) * 100) / selected.size
                }
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                btnExtract.isEnabled = true
                tvStatus.text = "Bitdi. Fayllar bu qovluqdadır:\n${outDir.absolutePath}"
            }
        }
    }

    // ---- Optional root flashing -------------------------------------------

    private fun confirmAndFlash(partitionName: String) {
        val img = extractedFiles[partitionName] ?: return
        AlertDialog.Builder(this)
            .setTitle("Flaş et: $partitionName")
            .setMessage(
                "Bu, cihazınızın \"$partitionName\" bölməsini DAİMİ olaraq yenisi ilə " +
                    "əvəz edəcək və root tələb edir. Səhv bölməni flaş etmək cihazı " +
                    "işləməz vəziyyətə sala bilər. Davam edilsin?"
            )
            .setPositiveButton("Bəli, flaş et") { _, _ -> doFlash(partitionName, img) }
            .setNegativeButton("İmtina", null)
            .show()
    }

    private fun doFlash(partitionName: String, img: File) {
        tvStatus.text = "Flaş edilir: $partitionName ..."
        lifecycleScope.launch(Dispatchers.IO) {
            val (ok, msg) = FlashHelper.flashPartition(img.absolutePath, partitionName)
            withContext(Dispatchers.Main) { tvStatus.text = msg }
        }
    }
}
