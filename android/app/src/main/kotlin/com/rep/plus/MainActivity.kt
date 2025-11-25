package com.rep.plus

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.rep.plus.network.CaptureService
import com.rep.plus.storage.RequestDatabase
import com.rep.plus.utils.PCAPdroidHelper
import org.json.JSONObject
import org.json.JSONArray

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var requestDb: RequestDatabase
    private var captureServiceIntent: Intent? = null
    @Volatile
    private var shouldStopBulk = false

    /**
     * BroadcastReceiver to listen for captured HTTP requests from CaptureService
     */
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val requestJson = intent.getStringExtra("request") ?: return
            Log.d(TAG, "Received captured request broadcast")

            // Send to WebView
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onAndroidRequestCaptured && window.onAndroidRequestCaptured($requestJson);",
                    null
                )
                Log.d(TAG, "Sent request to WebView: ${requestJson.take(100)}...")
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PCAPDROID = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize database
        requestDb = RequestDatabase(this)

        // Setup WebView
        webView = WebView(this)
        setupWebView()
        setContentView(webView)

        // Register broadcast receiver for captured HTTP requests
        registerReceiver(
            captureReceiver,
            IntentFilter(CaptureService.ACTION_HTTP_CAPTURED),
            RECEIVER_NOT_EXPORTED
        )

        // Load the UI
        webView.loadUrl("file:///android_asset/web/panel.html")

        // Check if PCAPdroid is installed
        checkPCAPdroidInstalled()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.setWebChromeClient(WebChromeClient())
        webView.setWebViewClient(WebViewClient())

        // Add JavaScript interface
        webView.addJavascriptInterface(RepJSBridge(this), "Android")
    }

    private fun checkPCAPdroidInstalled() {
        if (!PCAPdroidHelper.isInstalled(this)) {
            Toast.makeText(
                this,
                "PCAPdroid not installed. Please install PCAPdroid to capture traffic.",
                Toast.LENGTH_LONG
            ).show()
            // Optionally open Play Store
            // PCAPdroidHelper.openPlayStore(this)
        }
    }

    private fun startCapture() {
        val intent = PCAPdroidHelper.createStartIntent(
            pcapMode = "udp_exporter",
            collectorIp = "127.0.0.1",
            collectorPort = 5123,
            appFilter = null, // Capture all apps
            broadcastReceiver = "com.rep.plus.network.PCAPdroidReceiver"
        )
        startActivityForResult(intent, REQUEST_CODE_PCAPDROID)
    }

    private fun stopCapture() {
        val intent = PCAPdroidHelper.createStopIntent()
        startActivityForResult(intent, REQUEST_CODE_PCAPDROID)

        // Stop UDP service
        captureServiceIntent?.let {
            stopService(it)
            captureServiceIntent = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PCAPDROID) {
            handlePCAPdroidResult(resultCode, data)
        }
    }

    private fun handlePCAPdroidResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            // PCAPdroid started successfully
            // Start our UDP receiver service
            captureServiceIntent = Intent(this, CaptureService::class.java).apply {
                putExtra("port", 5123)
            }
            startService(captureServiceIntent)

            // Notify WebView
            webView.evaluateJavascript("window.onCaptureStarted?.()", null)
        } else {
            Toast.makeText(this, "Failed to start capture", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * JavaScript Bridge - Called from WebView
     */
    inner class RepJSBridge(private val activity: MainActivity) {

        @JavascriptInterface
        fun startCapture() {
            activity.runOnUiThread {
                activity.startCapture()
            }
        }

        @JavascriptInterface
        fun stopCapture() {
            activity.runOnUiThread {
                activity.stopCapture()
            }
        }

        @JavascriptInterface
        fun sendRequest(requestJson: String): String {
            // Parse request and send it
            return try {
                val request = JSONObject(requestJson)
                val response = activity.sendHttpRequest(request)
                response.toString()
            } catch (e: Exception) {
                JSONObject().apply {
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun saveRequest(requestJson: String) {
            activity.requestDb.insertRequest(requestJson)
        }

        @JavascriptInterface
        fun getRequests(): String {
            return activity.requestDb.getAllRequests()
        }

        @JavascriptInterface
        fun clearRequests() {
            activity.requestDb.clearAll()
        }

        @JavascriptInterface
        fun deleteRequest(id: Long): Boolean {
            return activity.requestDb.deleteRequest(id) > 0
        }

        @JavascriptInterface
        fun updateRequest(id: Long, requestJson: String): Boolean {
            return try {
                activity.requestDb.updateRequest(id, requestJson) > 0
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun searchRequests(query: String): String {
            return activity.requestDb.searchRequests(query)
        }

        @JavascriptInterface
        fun getRequestById(id: Long): String {
            return activity.requestDb.getRequestById(id) ?: "{}"
        }

        @JavascriptInterface
        fun toggleStar(id: Long): Boolean {
            return try {
                // Get current starred state, toggle it
                val request = activity.requestDb.getRequestById(id)
                if (request != null) {
                    val json = JSONObject(request)
                    val currentStarred = json.optInt("starred", 0)
                    val newStarred = if (currentStarred == 1) 0 else 1
                    activity.requestDb.updateStarred(id, newStarred) > 0
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun exportAllRequests(): String {
            return activity.requestDb.getAllRequests()
        }

        @JavascriptInterface
        fun showToast(message: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun executeBulkReplay(requestsJson: String, callback: String) {
            // Reset stop flag
            activity.shouldStopBulk = false

            // Parse array of requests
            val requests = JSONArray(requestsJson)
            val totalRequests = requests.length()

            // Execute in background thread
            Thread {
                for (i in 0 until totalRequests) {
                    // Check if we should stop
                    if (activity.shouldStopBulk) {
                        activity.runOnUiThread {
                            activity.webView.evaluateJavascript(
                                "$callback({\"stopped\": true, \"index\": $i, \"total\": $totalRequests});",
                                null
                            )
                        }
                        break
                    }

                    try {
                        val request = requests.getJSONObject(i)

                        // Execute request
                        val result = com.rep.plus.network.HttpClient.send(request)

                        // Add request index to result
                        result.put("index", i)
                        result.put("total", totalRequests)

                        // Report back to WebView
                        activity.runOnUiThread {
                            // Escape the JSON result properly
                            val resultJson = result.toString()
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            activity.webView.evaluateJavascript(
                                "$callback($resultJson);",
                                null
                            )
                        }

                        // Small delay between requests to avoid overwhelming
                        Thread.sleep(50)
                    } catch (e: Exception) {
                        // Report error for this request
                        activity.runOnUiThread {
                            val errorResult = JSONObject().apply {
                                put("error", e.message ?: "Unknown error")
                                put("index", i)
                                put("total", totalRequests)
                            }
                            activity.webView.evaluateJavascript(
                                "$callback($errorResult);",
                                null
                            )
                        }
                    }
                }

                // Signal completion if not stopped
                if (!activity.shouldStopBulk) {
                    activity.runOnUiThread {
                        activity.webView.evaluateJavascript(
                            "$callback({\"complete\": true, \"total\": $totalRequests});",
                            null
                        )
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun stopBulkReplay() {
            // Set flag to stop execution
            activity.shouldStopBulk = true
        }
    }

    private fun sendHttpRequest(request: JSONObject): JSONObject {
        // Implemented in network module
        return com.rep.plus.network.HttpClient.send(request)
    }

    override fun onDestroy() {
        super.onDestroy()
        captureServiceIntent?.let { stopService(it) }
        // Unregister broadcast receiver
        try {
            unregisterReceiver(captureReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered, ignore
        }
    }
}
