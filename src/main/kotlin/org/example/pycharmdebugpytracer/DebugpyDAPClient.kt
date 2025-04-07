package com.example.debugpyplugin

import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class DebugpyDAPClient(
    private val host: String = "localhost",
    private val port: Int = 5678
) {
    companion object {
        var globalInstance: DebugpyDAPClient? = null
    }

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var dapJob: Job? = null

    private val outputListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var sequence = 1

    fun connect(programPath: String? = null) {
        if (dapJob != null) return // already connected

        dapJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = Socket(host, port)
                writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), StandardCharsets.UTF_8))
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), StandardCharsets.UTF_8))

                sendInitialize()
                delay(200)
                programPath?.let {
                    sendLaunch(it)
                    delay(200)
                    sendConfigurationDone()
                }

                while (isActive) {
                    val message = readMessage()
                    message?.let {
                        handleMessage(it)
                    }
                }

            } catch (e: Exception) {
                notifyOutput("❌ DAP connection failed: ${e.message}")
            }
        }

        globalInstance = this
    }

    fun disconnect() {
        dapJob?.cancel()
        reader?.close()
        writer?.close()
        socket?.close()
        dapJob = null
        notifyOutput("🔌 DAP disconnected.\n")
    }

    private fun readMessage(): String? {
        val contentLengthPrefix = "Content-Length: "
        var contentLength = 0

        var line: String?
        while (true) {
            line = reader?.readLine() ?: return null
            if (line.startsWith(contentLengthPrefix)) {
                contentLength = line.removePrefix(contentLengthPrefix).toInt()
            }
            if (line.isEmpty()) break
        }

        val buffer = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val r = reader?.read(buffer, read, contentLength - read) ?: break
            if (r == -1) break
            read += r
        }

        return String(buffer, 0, read)
    }

    private fun sendDAPMessage(command: String, arguments: Map<String, Any?> = emptyMap()) {
        val jsonBody = buildJsonRequest(command, arguments)
        val content = jsonBody.trim()
        val header = "Content-Length: ${content.toByteArray(StandardCharsets.UTF_8).size}\r\n\r\n"
        writer?.apply {
            write(header)
            write(content)
            flush()
        }
    }

    private fun buildJsonRequest(command: String, arguments: Map<String, Any?>): String {
        val args = arguments.entries.joinToString(",") {
            val value = when (val v = it.value) {
                is String -> "\"${v.replace("\"", "\\\"")}\""
                is Map<*, *> -> v.toString().replace("=", ":")
                null -> "null"
                else -> v.toString()
            }
            "\"${it.key}\": $value"
        }
        return """
            {
                "seq": ${sequence++},
                "type": "request",
                "command": "$command",
                "arguments": { $args }
            }
        """.trimIndent()
    }

    private fun handleMessage(json: String) {
        when {
            json.contains("\"event\":\"output\"") -> {
                val content = Regex("\"output\"\\s*:\\s*\"(.*?)\"").find(json)?.groupValues?.get(1)
                content?.let { notifyOutput(it) }
            }
            json.contains("\"event\":\"initialized\"") -> {
                notifyOutput("✅ DAP Initialized\n")
            }
            json.contains("\"event\":\"terminated\"") -> {
                notifyOutput("🛑 Target terminated\n")
            }
            json.contains("\"event\":\"stopped\"") -> {
                notifyOutput("⏸️ Target stopped\n")
            }
        }
    }

    private fun notifyOutput(text: String) {
        outputListeners.forEach { it(text) }
    }

    fun onOutput(listener: (String) -> Unit) {
        outputListeners.add(listener)
    }

    fun sendInitialize() {
        sendDAPMessage("initialize", mapOf(
            "adapterID" to "debugpy",
            "pathFormat" to "path",
            "linesStartAt1" to true,
            "columnsStartAt1" to true,
            "supportsVariableType" to true,
            "supportsVariablePaging" to false,
            "supportsRunInTerminalRequest" to false
        ))
    }

    fun sendLaunch(programPath: String) {
        sendDAPMessage("launch", mapOf(
            "name" to "Launch $programPath",
            "type" to "python",
            "request" to "launch",
            "program" to programPath,
            "console" to "integratedTerminal",
            "justMyCode" to false
        ))
    }

    fun sendConfigurationDone() {
        sendDAPMessage("configurationDone")
    }

    fun sendSetBreakpoints(filePath: String, lines: List<Int>) {
        sendDAPMessage("setBreakpoints", mapOf(
            "source" to mapOf("path" to filePath),
            "breakpoints" to lines.map { mapOf("line" to it) }
        ))
    }

    fun sendContinue(threadId: Int) {
        sendDAPMessage("continue", mapOf("threadId" to threadId))
    }

    fun sendStackTrace(threadId: Int) {
        sendDAPMessage("stackTrace", mapOf("threadId" to threadId))
    }
}