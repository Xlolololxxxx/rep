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
import java.net.URL
import java.net.HttpURLConnection

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
            val requestData = intent.getStringExtra(CaptureService.EXTRA_REQUEST_DATA) ?: return
            Log.d(TAG, "Received captured request broadcast")

            val parsedRequest = com.rep.plus.network.PcapParser.parse(requestData)
            if (parsedRequest != null) {
                val requestJson = JSONObject().apply {
                    put("method", parsedRequest.method)
                    put("url", parsedRequest.url)
                    put("headers", parsedRequest.headers)
                    put("body", parsedRequest.body)
                }.toString()

                // Send to WebView
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onAndroidRequestCaptured && window.onAndroidRequestCaptured($requestJson);",
                        null
                    )
                    Log.d(TAG, "Sent request to WebView: ${requestJson.take(100)}...")
                }
            } else {
                Log.w(TAG, "Failed to parse captured request.")
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
            IntentFilter(CaptureService.ACTION_REQUEST_CAPTURED),
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
        fun importRequests(jsonData: String): Boolean {
            return try {
                val data = JSONObject(jsonData)
                val requests = data.getJSONArray("requests")
                for (i in 0 until requests.length()) {
                    activity.requestDb.insertRequest(requests.getJSONObject(i).toString())
                }
                activity.runOnUiThread {
                    activity.webView.evaluateJavascript("window.refreshRequestList && window.refreshRequestList();", null)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                false
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun executeBulkReplay(requestsJson: String, callback: String) {
            activity.shouldStopBulk = false
            val requests = JSONArray(requestsJson)
            val totalRequests = requests.length()

            Thread {
                for (i in 0 until totalRequests) {
                    if (activity.shouldStopBulk) {
                        break
                    }
                    try {
                        val request = requests.getJSONObject(i)
                        val result = com.rep.plus.network.HttpClient.send(request)
                        result.put("index", i)
                        result.put("total", totalRequests)
                        activity.runOnUiThread {
                            activity.webView.evaluateJavascript("$callback(${result});", null)
                        }
                    } catch (e: Exception) {
                        val errorResult = JSONObject().apply {
                            put("error", e.message ?: "Unknown error")
                            put("index", i)
                            put("total", totalRequests)
                        }
                        activity.runOnUiThread {
                            activity.webView.evaluateJavascript("$callback(${errorResult});", null)
                        }
                    }
                }
                if (!activity.shouldStopBulk) {
                    activity.runOnUiThread {
                        activity.webView.evaluateJavascript("$callback({\"complete\": true, \"total\": $totalRequests});", null)
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun stopBulkReplay() {
            // Set flag to stop execution
            activity.shouldStopBulk = true
        }

        @JavascriptInterface
        fun callAnthropicAPI(apiKey: String, model: String, systemPrompt: String, userMessage: String, callback: String) {
            Thread {
                try {
                    val url = URL("https://api.anthropic.com/v1/messages")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("x-api-key", apiKey)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                    connection.doOutput = true
                    connection.connectTimeout = 60000
                    connection.readTimeout = 60000

                    val requestBody = JSONObject().apply {
                        put("model", model)
                        put("max_tokens", 4096)
                        put("system", systemPrompt)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", userMessage)
                            })
                        })
                    }

                    connection.outputStream.write(requestBody.toString().toByteArray())

                    val responseCode = connection.responseCode
                    val response = if (responseCode == 200) {
                        connection.inputStream.bufferedReader().readText()
                    } else {
                        connection.errorStream?.bufferedReader()?.readText() ?: "Error: $responseCode"
                    }

                    activity.runOnUiThread {
                        val escapedResponse = response.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                        activity.webView.evaluateJavascript("$callback(\"$escapedResponse\", $responseCode);", null)
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        val error = e.message?.replace("\"", "\\\"") ?: "Unknown error"
                        activity.webView.evaluateJavascript("$callback(\"Error: $error\", 0);", null)
                    }
                }
            }.start()
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
