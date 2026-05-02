package com.example.wifi_destroyer

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var etDuration: EditText
    private lateinit var etUrl: EditText
    private lateinit var switchMode: SwitchCompat
    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView

    // OkHttp Client configured for speed (short timeouts for failing fast)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Control flag
    private var isTesting = false
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views
        etDuration = findViewById(R.id.etDuration)
        etUrl = findViewById(R.id.etUrl)
        switchMode = findViewById(R.id.switchMode)
        btnStart = findViewById(R.id.btnStart)
        tvStatus = findViewById(R.id.svTerminal)

        btnStart.setOnClickListener {
            if (isTesting) {
                stopTest()
            } else {
                startTest()
                btnStart.text = getString(R.string.btn_stop)
            }
        }
    }

    private fun startTest() {
        val durationStr = etDuration.text.toString()
        val targetUrl = etUrl.text.toString()

        if (durationStr.isEmpty() || targetUrl.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        val durationSeconds = durationStr.toLongOrNull() ?: 10
        val isUpload = switchMode.isChecked

        isTesting = true
        btnStart.text = getString(R.string.stop_test)
        val modeString = if (isUpload) getString(R.string.type_upload) else getString(R.string.type_download)
        tvStatus.text = getString(R.string.status_starting_test, modeString)

        // Launch Coroutine on IO thread
        job = CoroutineScope(Dispatchers.IO).launch {
            val endTime = System.currentTimeMillis() + (durationSeconds * 1000)
            var totalBytes = 0L
            var errors = 0

            // Loop until time runs out or test is cancelled
            while (isActive && System.currentTimeMillis() < endTime) {
                try {
                    val bytesProcessed = if (isUpload) {
                        performUpload(targetUrl)
                    } else {
                        performDownload(targetUrl)
                    }

                    totalBytes += bytesProcessed

                    // Update UI periodically (every request)
                    withContext(Dispatchers.Main) {
                        val mb = totalBytes / (1024 * 1024)
                        tvStatus.append("Request OK. Total: $mb MB\n")
                    }
                } catch (e: Exception) {
                    errors++
                    withContext(Dispatchers.Main) {
                        tvStatus.append("Error: ${e.message}\n")
                    }
                    Log.e("WifiDestroyer", "Error", e)
                }
            }

            // Test Finished
            withContext(Dispatchers.Main) {
                stopTest()
                tvStatus.append("\n--- DONE ---\n")
                tvStatus.append("Total Transferred: ${totalBytes / (1024 * 1024)} MB\n")
                tvStatus.append("Errors: $errors")
            }
        }
    }

    private fun stopTest() {
        isTesting = false
        job?.cancel() // Cancel the background coroutine
        btnStart.text = getString(R.string.start_stress_test)
    }

    // DOWNLOAD LOGIC
    private fun performDownload(url: String): Long {
        val request = Request.Builder().url(url).build()

        // We use .execute() for synchronous calls inside the background thread loop
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            // Read the stream to consume bandwidth, but don't save it
            val source = response.body?.source() ?: return 0
            var bytesRead: Long = 0
            val buffer = okio.Buffer()

            // Read 8KB chunks until exhausted
            while (source.read(buffer, 8192) != -1L) {
                bytesRead += buffer.size
                buffer.clear() // Discard data
            }
            return bytesRead
        }
    }

    // UPLOAD LOGIC
    private fun performUpload(url: String): Long {
        // Create a 1MB dummy payload
        val dummyData = ByteArray(1024 * 1024) { 1 }
        val requestBody = dummyData.toRequestBody("application/octet-stream".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            // Even if server returns 404 or 500, we successfully sent the bits
            if (!response.isSuccessful && response.code != 200) {
            return 0
            }
            return requestBody.contentLength()
        }
    }
}
