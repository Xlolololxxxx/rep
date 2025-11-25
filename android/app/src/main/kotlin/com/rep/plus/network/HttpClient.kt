package com.rep.plus.network

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object HttpClient {

    fun send(request: JSONObject): JSONObject {
        val urlString = request.getString("url")
        val method = request.getString("method")
        val headers = request.getJSONObject("headers")
        val body = request.optString("body", null)

        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.doInput = true

        // Set headers
        headers.keys().forEach { key ->
            connection.setRequestProperty(key, headers.getString(key))
        }

        // Set body for POST, PUT, PATCH
        if (body != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
            connection.doOutput = true
            connection.outputStream.write(body.toByteArray())
        }

        val startTime = System.currentTimeMillis()
        connection.connect()
        val duration = System.currentTimeMillis() - startTime

        val status = connection.responseCode
        val statusText = connection.responseMessage
        val responseBody = try {
            connection.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            connection.errorStream?.bufferedReader()?.readText() ?: ""
        }
        val responseSize = responseBody.toByteArray().size

        val responseHeaders = JSONObject()
        connection.headerFields.forEach { (key, value) ->
            if (key != null) {
                responseHeaders.put(key, value.joinToString(", "))
            }
        }

        return JSONObject().apply {
            put("status", status)
            put("statusText", statusText)
            put("headers", responseHeaders)
            put("body", responseBody)
            put("size", responseSize)
            put("duration", duration)
        }
    }
}
