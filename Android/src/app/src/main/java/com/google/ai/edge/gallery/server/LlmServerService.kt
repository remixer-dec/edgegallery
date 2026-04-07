package com.google.ai.edge.gallery.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.litertlm.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

class LlmServerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var engine: Engine? = null

    private var activeModelName = "gemma-local"
    private var useTools = false
    private var useVision = false
    private var topK = 40
    private var topP = 0.95
    private var temperature = 1.0

    private class BadRequestException(message: String) : Exception(message)
    private class ServiceUnavailableException(message: String) : Exception(message)

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_IP = "EXTRA_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_MODEL_NAME = "EXTRA_MODEL_NAME"
        const val EXTRA_MODEL_PATH = "EXTRA_MODEL_PATH"
        const val EXTRA_MAX_TOKENS = "EXTRA_MAX_TOKENS"
        const val EXTRA_ACCELERATOR = "EXTRA_ACCELERATOR"
        const val EXTRA_ENABLE_TOOLS = "EXTRA_ENABLE_TOOLS"
        const val EXTRA_TOP_K = "EXTRA_TOP_K"
        const val EXTRA_TOP_P = "EXTRA_TOP_P"
        const val EXTRA_TEMPERATURE = "EXTRA_TEMPERATURE"
        const val EXTRA_ENABLE_VISION = "EXTRA_ENABLE_VISION"

        private const val CHANNEL_ID = "LlmServerChannel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "LlmServerService"

        // Gemma tool-call DSL: <|tool_call>call:NAME{args}<tool_call|>
        // The native parser is strict; this regex is lenient and matches the wrapper shape.
        private val TOOL_CALL_REGEX = Regex("""<\|tool_call>call:([A-Za-z_][A-Za-z0-9_]*)\{([^}]*)\}<tool_call\|>""")
        private const val DSL_STR_DELIM = "<|\"|>"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_IP) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return START_NOT_STICKY
                activeModelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "gemma-local"
                useTools = intent.getBooleanExtra(EXTRA_ENABLE_TOOLS, false)
                useVision = intent.getBooleanExtra(EXTRA_ENABLE_VISION, false)
                val maxTokens = intent.getIntExtra(EXTRA_MAX_TOKENS, 1024)
                val accelerator = intent.getStringExtra(EXTRA_ACCELERATOR) ?: "GPU"
                topK = intent.getIntExtra(EXTRA_TOP_K, 40)
                topP = intent.getFloatExtra(EXTRA_TOP_P, 0.95f).toDouble()
                temperature = intent.getFloatExtra(EXTRA_TEMPERATURE, 1.0f).toDouble()

                startForegroundService(ip, port)
                startServer(ip, port, modelPath, maxTokens, accelerator)
                isRunning = true
            }

            ACTION_STOP -> stopServerAndService()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService(ip: String, port: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "LLM Server", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        val stopIntent = Intent(this, LlmServerService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("open_destination", "server")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("API Server: $activeModelName")
            .setContentText("Listening on $ip:$port")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun startServer(ip: String, port: Int, modelPath: String, maxTokens: Int, accelerator: String) {
        if (isRunning) return
        isRunning = true

        serviceScope.launch {
            try {
                Log.d(TAG, "Initializing Engine with backend: $accelerator")
                val backend = when (accelerator.uppercase()) {
                    "CPU" -> Backend.CPU()
                    "NPU" -> Backend.NPU(applicationInfo.nativeLibraryDir)
                    else -> Backend.GPU()
                }

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = maxTokens,
                    audioBackend = Backend.CPU(),
                    visionBackend = if (useVision) Backend.GPU() else null
                )
                engine = Engine(engineConfig).apply { initialize() }

                serverSocket = ServerSocket(port, 50, InetAddress.getByName(ip))
                Log.d(TAG, "Server started on $ip:$port")

                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
                stopServerAndService()
                isRunning = false
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.inputStream)
            val output = BufferedOutputStream(client.outputStream)

            val requestLine = readHttpLine(input)
            if (requestLine.isNullOrEmpty()) return

            val isPost = requestLine.startsWith("POST", ignoreCase = true)

            var contentLength = 0
            var expectContinue = false
            var transferEncoding: String? = null

            while (true) {
                val header = readHttpLine(input) ?: break
                if (header.isEmpty()) break

                val colon = header.indexOf(':')
                if (colon <= 0) continue
                val name = header.substring(0, colon).trim().lowercase()
                val value = header.substring(colon + 1).trim()

                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "expect" -> if (value.equals("100-continue", ignoreCase = true)) expectContinue = true
                    "transfer-encoding" -> transferEncoding = value.lowercase()
                }
            }

            if (!isPost) {
                writeError(output, 405, "Method Not Allowed", "Only POST is supported")
                return
            }

            if (transferEncoding != null && transferEncoding.contains("chunked")) {
                writeError(output, 411, "Length Required", "Chunked transfer encoding is not supported")
                return
            }

            if (contentLength <= 0) {
                writeError(output, 411, "Length Required", "Missing or invalid Content-Length")
                return
            }

            val maxBodyBytes = 50 * 1024 * 1024
            if (contentLength > maxBodyBytes) {
                writeError(output, 413, "Payload Too Large", "Body exceeds $maxBodyBytes bytes")
                return
            }

            if (expectContinue) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            }

            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) {
                    Log.e(TAG, "Client disconnected after $read / $contentLength bytes")
                    writeError(output, 400, "Bad Request", "Truncated request body")
                    return
                }
                read += n
            }

            val bodyString = String(body, Charsets.UTF_8)

            val jsonResponse: String = try {
                processOpenAIRequest(bodyString)
            } catch (e: BadRequestException) {
                Log.w(TAG, "Bad request: ${e.message}")
                writeError(output, 400, "Bad Request", e.message ?: "Bad request")
                return
            } catch (e: ServiceUnavailableException) {
                Log.w(TAG, "Service unavailable: ${e.message}")
                writeError(output, 503, "Service Unavailable", e.message ?: "Service unavailable")
                return
            } catch (e: Exception) {
                Log.e(TAG, "processOpenAIRequest threw", e)
                writeError(output, 500, "Internal Server Error", e.message ?: "Internal error")
                return
            }

            val jsonBytes = jsonResponse.toByteArray(Charsets.UTF_8)
            output.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Length: ${jsonBytes.size}\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(jsonBytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Client handling error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buf.size() == 0) null else buf.toString("US-ASCII")
            }
            if (prev == '\r'.code && b == '\n'.code) {
                val bytes = buf.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.US_ASCII)
            }
            buf.write(b)
            prev = b
            if (buf.size() > 8192) {
                throw IOException("HTTP header line too long")
            }
        }
    }

    private fun writeError(output: OutputStream, code: Int, status: String, message: String) {
        try {
            val bodyJson = """{"error":{"message":${Gson().toJson(message)},"code":$code}}"""
            val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
            output.write("HTTP/1.1 $code $status\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bodyBytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write error response", e)
        }
    }

    private fun extractText(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString
        if (element.isJsonArray) {
            val sb = StringBuilder()
            var hasAudio = false
            for (item in element.asJsonArray) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val type = obj.get("type")?.asString ?: continue
                when (type) {
                    "text" -> if (obj.has("text")) sb.append(obj.get("text").asString)
                    "input_audio" -> hasAudio = true
                }
            }
            if (sb.isEmpty() && hasAudio) return "[audio message]"
            return sb.toString()
        }
        return ""
    }

    /** Tool result content can be a string or a structured object — serialize uniformly. */
    private fun extractToolResultText(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString
        return element.toString()
    }

    @OptIn(ExperimentalApi::class)
    private fun processOpenAIRequest(jsonBody: String): String {
        var conversation: Conversation? = null
        try {
            val currentEngine = engine
                ?: throw ServiceUnavailableException("Engine not ready")

            val jsonObject = try {
                JsonParser.parseString(jsonBody).asJsonObject
            } catch (e: Exception) {
                throw BadRequestException("Invalid JSON: ${e.message}")
            }

            val messages = jsonObject.getAsJsonArray("messages")
                ?: throw BadRequestException("Missing 'messages' array")
            if (messages.size() == 0) {
                throw BadRequestException("'messages' array is empty")
            }

            // External tools mode is auto-enabled when the request declares any tools.
            // It auto-disables the native AndroidServerTools.
            val externalToolsArray = jsonObject.getAsJsonArray("tools")
            val hasExternalTools = externalToolsArray != null && externalToolsArray.size() > 0

            // The "trigger" is the last user OR tool message — in tool mode the client
            // may send a tool result as the most recent message instead of a new user turn.
            var triggerIndex = -1
            for (i in messages.size() - 1 downTo 0) {
                val role = messages[i].asJsonObject.get("role")?.asString ?: continue
                if (role == "user" || role == "tool") {
                    triggerIndex = i
                    break
                }
            }
            if (triggerIndex < 0) {
                throw BadRequestException("No user or tool message found in conversation")
            }

            var systemPrompt: String? = null
            val formattedHistory = StringBuilder()
            val finalContents = mutableListOf<Content>()

            for (i in 0 until messages.size()) {
                val msg = messages[i].asJsonObject
                val role = msg.get("role")?.asString ?: continue
                val contentElement = msg.get("content")

                when {
                    role == "system" -> {
                        val text = extractText(contentElement)
                        if (text.isNotEmpty()) {
                            systemPrompt = if (systemPrompt == null) text else "$systemPrompt\n$text"
                        }
                    }
                    i < triggerIndex -> {
                        appendHistoryEntry(formattedHistory, role, msg, contentElement)
                    }
                    i == triggerIndex -> {
                        if (role == "tool") {
                            val toolCallId = msg.get("tool_call_id")?.asString
                            val resultText = extractToolResultText(contentElement)
                            val prefix = if (toolCallId != null) "Tool result for $toolCallId" else "Tool result"
                            finalContents.add(Content.Text("$prefix: $resultText"))
                        } else {
                            parseFinalUserContent(contentElement, finalContents)
                        }
                    }
                }
            }

            if (finalContents.isEmpty()) {
                throw BadRequestException("Trigger message has no usable content")
            }
            
            // Tiny nudge — Gemma's FC parser bails on bare argument names like {location}
            // instead of {location:"Paris"}. Reminding the model to fill all args fixes most cases.
            if (useTools || hasExternalTools) {
                val nudge = "When calling a tool, fill values for every argument."
                systemPrompt = if (systemPrompt.isNullOrEmpty()) nudge else "$systemPrompt\n$nudge"
            }

            
            val hasAudioInFinalMessage = finalContents.any { it is Content.AudioBytes }
            if (hasAudioInFinalMessage && systemPrompt != null) {
                systemPrompt += "\n\nAs a powerful multimodal model, " +
                    "you MUST listen the audio, analyze it and respond accordingly in the same language."
                if (useTools || hasExternalTools) {
                    systemPrompt += "\nUse tools only when it makes sense. Tools available:"
                }
            }


            val toolList: List<ToolProvider> = when {
                hasExternalTools -> externalToolsArray!!.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val fn = el.asJsonObject.getAsJsonObject("function") ?: return@mapNotNull null
                    tool(DynamicOpenApiTool(fn.toString()))
                }
                useTools -> listOf(tool(AndroidServerTools(this)))
                else -> emptyList()
            }

            conversation = currentEngine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature),
                    systemInstruction = systemPrompt?.let { Contents.of(Content.Text(it)) },
                    tools = toolList
                )
            )

            if (hasExternalTools) {
                forceDisableAutomaticToolCalling(conversation)
            }

            if (formattedHistory.isNotEmpty()) {
                finalContents.add(
                    0,
                    Content.Text("Previous conversation context:\n$formattedHistory\nNow respond to the following:")
                )
            }

            // litertlm's native FC parser sometimes fails on Gemma's tool-call DSL when args
            // are missing values. We catch the parse error, extract the raw DSL from the
            // exception message, and parse it ourselves. Successful (parser-friendly) calls
            // still come back through the structured Message path below.
            val response = try {
                conversation.sendMessage(Contents.of(finalContents))
            } catch (e: Exception) {
                if (hasExternalTools) {
                    val recovered = recoverGemmaDslToolCalls(e.message ?: "")
                    if (recovered != null) {
                        Log.w(TAG, "Recovered ${recovered.size()} tool call(s) from native parser failure")
                        return buildOpenAIResponse(text = null, toolCalls = recovered)
                    }
                }
                throw e
            }

            val toolCallsJson = if (hasExternalTools) toolCallsToOpenAi(response) else null
            return if (toolCallsJson != null && toolCallsJson.size() > 0) {
                buildOpenAIResponse(text = null, toolCalls = toolCallsJson)
            } else {
                buildOpenAIResponse(text = extractTextFromResponse(response), toolCalls = null)
            }
        } finally {
            try { conversation?.close() } catch (_: Exception) {}
        }
    }

    private fun appendHistoryEntry(
        history: StringBuilder,
        role: String,
        msg: JsonObject,
        contentElement: JsonElement?
    ) {
        when (role) {
            "tool" -> {
                val toolCallId = msg.get("tool_call_id")?.asString
                val resultText = extractToolResultText(contentElement)
                if (resultText.isNotEmpty()) {
                    val tag = if (toolCallId != null) "TOOL_RESULT[$toolCallId]" else "TOOL_RESULT"
                    history.append("$tag: $resultText\n")
                }
            }
            "assistant" -> {
                val toolCalls = msg.getAsJsonArray("tool_calls")
                if (toolCalls != null && toolCalls.size() > 0) {
                    history.append("ASSISTANT_TOOL_CALL: $toolCalls\n")
                } else {
                    val text = extractText(contentElement)
                    if (text.isNotEmpty()) history.append("ASSISTANT: $text\n")
                }
            }
            else -> {
                val text = extractText(contentElement)
                if (text.isNotEmpty()) history.append("${role.uppercase()}: $text\n")
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun parseFinalUserContent(contentElement: JsonElement?, finalContents: MutableList<Content>) {
        if (contentElement != null && contentElement.isJsonArray) {
            for (item in contentElement.asJsonArray) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val type = obj.get("type")?.asString ?: continue
                when (type) {
                    "text" -> {
                        val t = obj.get("text")?.asString ?: ""
                        if (t.isNotEmpty()) finalContents.add(Content.Text(t))
                    }
                    "input_audio" -> {
                        val audioObj = obj.getAsJsonObject("input_audio")
                            ?: throw BadRequestException("input_audio missing object body")
                        val audioData = audioObj.get("data")?.asString
                            ?: throw BadRequestException("input_audio.data is missing")
                        val decodedAudio = try {
                            Base64.decode(audioData, Base64.DEFAULT)
                        } catch (e: IllegalArgumentException) {
                            throw BadRequestException("input_audio.data is not valid base64: ${e.message}")
                        }
                        finalContents.add(Content.AudioBytes(decodedAudio))
                    }
                    "image_url" -> {
                        val url = obj.getAsJsonObject("image_url").get("url").asString
                        val rawBytes = if (url.startsWith("data:")) {
                            Base64.decode(url.substringAfter(","), Base64.DEFAULT)
                        } else {
                            URL(url).openStream().use { it.readBytes() }
                        }
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                            ?: throw IllegalArgumentException("Could not decode image")
                        val pngBytes = ByteArrayOutputStream().use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                            out.toByteArray()
                        }
                    }
                }
            }
        } else {
            val t = extractText(contentElement)
            if (t.isEmpty()) throw BadRequestException("Final user message is empty")
            finalContents.add(Content.Text(t))
        }
    }

    /**
     * Force-disable automaticToolCalling on a Conversation. The flag is a `val` constructor
     * parameter on Conversation with no setter and no Engine.createConversation overload that
     * takes it, so reflection is the only way to flip it. The backing field is a regular final
     * boolean on the JVM; setAccessible(true) lets us assign it.
     */
    private fun forceDisableAutomaticToolCalling(conversation: Any) {
        try {
            val field = conversation.javaClass.getDeclaredField("automaticToolCalling")
            field.isAccessible = true
            field.setBoolean(conversation, false)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to disable automaticToolCalling via reflection. " +
                "External tools mode will fall back to native execution and likely fail.", e)
        }
    }

    /**
     * Last-resort parser for Gemma's tool-call DSL when litertlm's native parser fails.
     *
     * Gemma emits tool calls as:
     *     <|tool_call>call:FUNCTION_NAME{arg1:value1,arg2:value2}<tool_call|>
     *
     * String values are wrapped as <|"|>value<|"|>. The native parser is strict and rejects
     * the whole call if any argument has a bare key with no value (e.g. {location} instead
     * of {location:"Paris"}). We're lenient: missing values become empty strings so the
     * conversation can continue and the OpenAI client can decide what to do.
     *
     * The raw DSL is extracted from the exception message litertlm produces, which has the
     * shape: "...Failed to parse tool calls from response: <RAW_DSL> with error: ..."
     */
    private fun recoverGemmaDslToolCalls(errorMessage: String): JsonArray? {
        val rawDsl = extractRawDslFromError(errorMessage) ?: return null
        val matches = TOOL_CALL_REGEX.findAll(rawDsl).toList()
        if (matches.isEmpty()) return null

        val out = JsonArray()
        matches.forEachIndexed { idx, match ->
            val name = match.groupValues[1]
            val argsBody = match.groupValues[2]
            val args = parseDslArgs(argsBody)
            out.add(JsonObject().apply {
                addProperty("id", "call_${System.currentTimeMillis()}_$idx")
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("arguments", args.toString())
                })
            })
        }
        return if (out.size() > 0) out else null
    }

    private fun extractRawDslFromError(errorMessage: String): String? {
        val marker = "Failed to parse tool calls from response:"
        val start = errorMessage.indexOf(marker)
        if (start < 0) return null
        val afterMarker = errorMessage.substring(start + marker.length)
        // The DSL is followed by " with error:" or "code block:" — take everything before whichever comes first.
        val cutPoints = listOf(
            afterMarker.indexOf(" with error:"),
            afterMarker.indexOf("code block:")
        ).filter { it >= 0 }
        val end = cutPoints.minOrNull() ?: afterMarker.length
        return afterMarker.substring(0, end).trim()
    }

    private fun parseDslArgs(body: String): JsonObject {
        val result = JsonObject()
        if (body.isBlank()) return result

        for (token in splitDslArgsTopLevel(body)) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            val colon = trimmed.indexOf(':')
            if (colon < 0) {
                // Bare key without value — the exact case the native parser chokes on.
                result.addProperty(trimmed, "")
            } else {
                val key = trimmed.substring(0, colon).trim()
                val rawVal = trimmed.substring(colon + 1).trim()
                result.addProperty(key, stripDslStringDelimiters(rawVal))
            }
        }
        return result
    }

    /** Splits "a:1,b:<|"|>x,y<|"|>,c" into ["a:1", "b:<|\"|>x,y<|\"|>", "c"], honoring string delimiters. */
    private fun splitDslArgsTopLevel(body: String): List<String> {
        val result = mutableListOf<String>()
        var inString = false
        var start = 0
        var i = 0
        while (i < body.length) {
            if (i + DSL_STR_DELIM.length <= body.length &&
                body.regionMatches(i, DSL_STR_DELIM, 0, DSL_STR_DELIM.length)) {
                inString = !inString
                i += DSL_STR_DELIM.length
                continue
            }
            if (!inString && body[i] == ',') {
                result.add(body.substring(start, i))
                start = i + 1
            }
            i++
        }
        if (start <= body.length) result.add(body.substring(start))
        return result
    }

    private fun stripDslStringDelimiters(s: String): String {
        if (s.startsWith(DSL_STR_DELIM) && s.endsWith(DSL_STR_DELIM) && s.length >= DSL_STR_DELIM.length * 2) {
            return s.substring(DSL_STR_DELIM.length, s.length - DSL_STR_DELIM.length)
        }
        return s
    }

    /**
     * Convert a Message's structured tool calls into OpenAI's wire format.
     * Returns null if there are no tool calls. We use reflection on the Message API
     * to stay tolerant of small field-name differences across litertlm versions —
     * the upstream Conversation builds Message.model(contents, toolCalls, channels)
     * with toolCalls: List<ToolCall> where ToolCall has a name and arguments map.
     */
    private fun toolCallsToOpenAi(response: Any?): JsonArray? {
        if (response == null) return null
        val toolCalls: List<Any> = try {
            val field = response.javaClass.methods.firstOrNull {
                it.name == "getToolCalls" && it.parameterCount == 0
            } ?: return null
            @Suppress("UNCHECKED_CAST")
            (field.invoke(response) as? List<Any>) ?: return null
        } catch (_: Throwable) {
            return null
        }
        if (toolCalls.isEmpty()) return null

        val out = JsonArray()
        val gson = Gson()
        toolCalls.forEachIndexed { idx, call ->
            val name = readStringProp(call, "getName", "getFunctionName") ?: return@forEachIndexed
            val argsAny = readAnyProp(call, "getArguments", "getArgs")
            val argsStr = when (argsAny) {
                null -> "{}"
                is String -> argsAny
                else -> gson.toJson(argsAny)
            }
            out.add(JsonObject().apply {
                addProperty("id", "call_${System.currentTimeMillis()}_$idx")
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("arguments", argsStr)
                })
            })
        }
        return if (out.size() > 0) out else null
    }

    /** Extract plain text from a Message. Falls back to toString() if the structured path fails. */
    private fun extractTextFromResponse(response: Any?): String {
        if (response == null) return ""
        // Try response.contents → List<Content>, looking for Content.Text entries.
        try {
            val getter = response.javaClass.methods.firstOrNull {
                it.name == "getContents" && it.parameterCount == 0
            } ?: return response.toString()
            val contents = getter.invoke(response) ?: return response.toString()
            // Contents might be iterable directly, or expose a list via .getContents() / .toList()
            val iter: Iterable<*> = when (contents) {
                is Iterable<*> -> contents
                else -> {
                    val asList = contents.javaClass.methods.firstOrNull {
                        it.name == "getContents" && it.parameterCount == 0
                    }?.invoke(contents) as? Iterable<*>
                    asList ?: return response.toString()
                }
            }
            val sb = StringBuilder()
            for (item in iter) {
                if (item == null) continue
                if (item is Content.Text) {
                    sb.append(item.text)
                } else {
                    // Fall back to a `getText` method if present
                    val textGetter = item.javaClass.methods.firstOrNull {
                        it.name == "getText" && it.parameterCount == 0
                    }
                    val t = textGetter?.invoke(item) as? String
                    if (t != null) sb.append(t)
                }
            }
            return if (sb.isNotEmpty()) sb.toString() else response.toString()
        } catch (_: Throwable) {
            return response.toString()
        }
    }

    private fun readStringProp(obj: Any, vararg getterNames: String): String? {
        for (n in getterNames) {
            try {
                val m = obj.javaClass.methods.firstOrNull { it.name == n && it.parameterCount == 0 }
                val v = m?.invoke(obj) as? String
                if (v != null) return v
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun readAnyProp(obj: Any, vararg getterNames: String): Any? {
        for (n in getterNames) {
            try {
                val m = obj.javaClass.methods.firstOrNull { it.name == n && it.parameterCount == 0 }
                val v = m?.invoke(obj)
                if (v != null) return v
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun buildOpenAIResponse(text: String?, toolCalls: JsonArray? = null): String {
        val gson = Gson()
        val isToolCallResponse = toolCalls != null && toolCalls.size() > 0
        val response = JsonObject().apply {
            addProperty("id", "chatcmpl-${System.currentTimeMillis()}")
            addProperty("object", "chat.completion")
            addProperty("created", System.currentTimeMillis() / 1000)
            addProperty("model", activeModelName)
            val message = JsonObject().apply {
                addProperty("role", "assistant")
                if (isToolCallResponse) {
                    add("content", JsonNull.INSTANCE)
                    add("tool_calls", toolCalls)
                } else {
                    addProperty("content", text ?: "")
                }
            }
            val choice = JsonObject().apply {
                addProperty("index", 0)
                add("message", message)
                addProperty("finish_reason", if (isToolCallResponse) "tool_calls" else "stop")
            }
            add("choices", JsonArray().apply { add(choice) })
            add("usage", JsonObject().apply {
                addProperty("prompt_tokens", 0)
                addProperty("completion_tokens", 0)
                addProperty("total_tokens", 0)
            })
        }
        return gson.toJson(response)
    }

    private fun stopServerAndService() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { engine?.close() } catch (_: Exception) {}
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServerAndService()
        super.onDestroy()
    }
}

/**
 * Wraps a raw OpenAPI/JSON tool description so the engine can declare it to the model.
 * With automaticToolCalling = false, the engine should never invoke this — the model's
 * tool call is returned to us and forwarded to the OpenAI client. The execute() method
 * is required by the interface but should be unreachable.
 */
@OptIn(ExperimentalApi::class)
class DynamicOpenApiTool(private val descriptionJson: String) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = descriptionJson
    override fun execute(paramsJsonString: String): String =
        throw IllegalStateException(
            "DynamicOpenApiTool.execute should never be called when automaticToolCalling = false"
        )
}

class AndroidServerTools(private val service: Service) : ToolSet {
    @Tool("Search map/nearby")
    fun searchMap(@ToolParam("query") q: String): Map<String, String> {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(q)}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            mapOf("res" to "ok")
        } catch (e: Exception) {
            Log.e("AndroidServerTools", "Map error", e)
            mapOf("error" to "No map app found")
        }
    }

    @Tool("Set alarm")
    fun setAlarm(
        @ToolParam("hr 0-23") h: Int,
        @ToolParam("min 0-59") m: Int,
        @ToolParam("lbl") l: String
    ): Map<String, String> {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_MESSAGE, l)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            mapOf("res" to "ok")
        } catch (e: Exception) {
            Log.e("AndroidServerTools", "Alarm error", e)
            mapOf("error" to "No alarm app found")
        }
    }

    @Tool("Set timer")
    fun setTimer(seconds: Int, msg: String? = null, skipUi: Boolean = true): String {
        if (seconds < 1 || seconds > 86400) {
            return "Invalid duration: $seconds seconds. Must be between 1 and 86400 (24 hours)."
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            if (!msg.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(service.packageManager) == null) {
            return "Device is unable to handle timers."
        }

        return try {
            service.startActivity(intent)
            val label = if (!msg.isNullOrBlank()) " labeled \"$msg\"" else ""
            "Timer set for $seconds $label."
        } catch (e: SecurityException) {
            Log.w("AndroidServerTools", "Background activity start blocked", e)
            "Unable to set timer: the app is not allowed to start activities from the background right now."
        } catch (e: Exception) {
            Log.e("AndroidServerTools", "Failed to start timer intent", e)
            "Failed to set timer: ${e.message}"
        }
    }

    @Tool("Turn flashlight on/off")
    fun toggleFlashlight(@ToolParam("on") on: Boolean): Map<String, String> {
        return try {
            val cameraManager =
                service.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, on)
                mapOf("res" to "ok")
            } else {
                mapOf("error" to "No flash found")
            }
        } catch (e: Exception) {
            Log.e("AndroidServerTools", "Flashlight error", e)
            mapOf("error" to "Camera error")
        }
    }

    @Tool("Get battery charge %")
    fun getBattery(): Map<String, String> {
        return try {
            val bm = service.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            mapOf(
                "lvl" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString(),
                "chg" to bm.isCharging.toString()
            )
        } catch (e: Exception) {
            mapOf("error" to "Failed to read battery")
        }
    }

    @Tool("Search music")
    fun playMusic(@ToolParam("query") q: String): Map<String, String> {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, q)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            mapOf("res" to "ok")
        } catch (e: Exception) {
            Log.e("AndroidServerTools", "Music error", e)
            mapOf("error" to "No music app found")
        }
    }

    @Tool("Run Tasker task")
    fun runTaskerTask(
        @ToolParam("name") taskName: String
    ): Map<String, String> {
        return try {
            val intent = Intent("net.dinglisch.android.tasker.ACTION_TASK").apply {
                setPackage("net.dinglisch.android.tasker")
                putExtra("task_name", taskName)
            }
            service.sendBroadcast(intent)
            mapOf("res" to "ok", "task" to taskName)
        } catch (e: Exception) {
            Log.e("AndroidServerTools", "Tasker error", e)
            mapOf("error" to (e.message ?: "Failed"))
        }
    }

    @Tool(description = "Get BTC price.")
    fun getBitcoinPrice(): String = try {
        fetchPrice("https://www.bitstamp.net/api/v2/ticker/btcusd/") {
            it.get("last").asString
        }
    } catch (_: Exception) {
        try {
            fetchPrice("https://api.blockchain.info/ticker") {
                it.getAsJsonObject("USD").get("last").asString
            }
        } catch (e: Exception) {
            "Failed to fetch BTC price: ${e.message}"
        }
    }

    private fun fetchPrice(url: String, extract: (JsonObject) -> String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val json = JsonParser.parseString(conn.inputStream.bufferedReader().use { it.readText() }).asJsonObject
            return "BTC: $%,.2f USD".format(extract(json).toDouble())
        } finally {
            conn.disconnect()
        }
    }
}