package com.rep.plus.network

import org.json.JSONObject

data class ParsedRequest(
    val method: String,
    val url: String,
    val headers: JSONObject,
    val body: String
)

object PcapParser {
    fun parse(data: String): ParsedRequest? {
        try {
            val lines = data.lines()
            if (lines.isEmpty()) return null

            val requestLine = lines[0].split(" ")
            if (requestLine.size < 2) return null

            val method = requestLine[0]
            val path = requestLine[1]

            val headers = JSONObject()
            var bodyStartIndex = -1
            var host = ""

            for (i in 1 until lines.size) {
                if (lines[i].isBlank()) {
                    bodyStartIndex = i + 1
                    break
                }
                val header = lines[i].split(":", limit = 2)
                if (header.size == 2) {
                    val key = header[0].trim()
                    val value = header[1].trim()
                    headers.put(key, value)
                    if (key.equals("Host", ignoreCase = true)) {
                        host = value
                    }
                }
            }

            val body = if (bodyStartIndex != -1 && bodyStartIndex < lines.size) {
                lines.subList(bodyStartIndex, lines.size).joinToString("\n")
            } else {
                ""
            }

            val url = "http://$host$path" // Assuming http for now

            return ParsedRequest(method, url, headers, body)
        } catch (e: Exception) {
            return null
        }
    }
}
