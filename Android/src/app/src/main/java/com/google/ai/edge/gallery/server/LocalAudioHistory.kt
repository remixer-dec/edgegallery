/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.server

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * In-memory rolling history of (user-audio-transcript, assistant-response) pairs for the
 * audio-chat use case. The smart watch only sends [system, audio] on every turn, so we
 * keep prior turns here and inject them as a context prefix on the next request.
 *
 * Lifecycle: created when the server starts with history enabled, lives for the lifetime
 * of the service, cleared on stop.
 *
 * Thread-safety: callers come from concurrent client-handling coroutines, so all mutation
 * goes through a monitor lock.
 */
@OptIn(ExperimentalApi::class)
class LocalAudioHistory(
    private val maxTokens: Int,
    private val reserveTokens: Int = 256,
    private val currentTurnReserveTokens: Int = 300,
    private val trimPairCount: Int = 2,
) {
    data class Entry(val userTranscript: String, val assistantResponse: String)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) {
        val n = entries.size
        entries.clear()
        Log.i(TAG, "History cleared ($n entries removed)")
    }

    /**
     * Append an entry, then trim from the front in batches of [trimPairCount] until the
     * estimated token count is back under the safe budget. Always keeps at least the
     * just-added entry, even if it alone exceeds the budget.
     */
    fun addAndTrim(entry: Entry) {
        synchronized(lock) {
            entries.addLast(entry)
            val tokensAfterAdd = estimateTokensLocked()
            Log.i(
                TAG,
                "Stored entry: transcriptLen=${entry.userTranscript.length}, " +
                    "responseLen=${entry.assistantResponse.length}, " +
                    "totalEntries=${entries.size}, estTokens=$tokensAfterAdd"
            )
            val budget = maxTokens - reserveTokens - currentTurnReserveTokens
            while (entries.size > 1 && estimateTokensLocked() > budget) {
                val drop = minOf(trimPairCount, entries.size - 1)
                repeat(drop) { entries.removeFirst() }
                Log.i(
                    TAG,
                    "Trimmed $drop entries (over budget $budget); " +
                        "remaining=${entries.size}, estTokens=${estimateTokensLocked()}"
                )
            }
        }
    }

    override fun toString(): String = synchronized(lock) {
        val budget = maxTokens - reserveTokens - currentTurnReserveTokens
        "LocalAudioHistory(entries=${entries.size}, estTokens=${estimateTokensLocked()}, budget=$budget)"
    }

    /** 
     * Format the snapshot as a highly explicit prompt prefix. 
     * Multimodal models need strong instructions to connect the text to the audio.
     */
    fun formatForPrompt(snapshot: List<Entry> = snapshot()): String {
        if (snapshot.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("We are having an ongoing voice conversation. Below is the text transcript of our previous turns for context:\n")
        sb.append("====================\n")
        for (e in snapshot) {
            sb.append("Me (User): ").append(e.userTranscript).append('\n')
            sb.append("You (Assistant): ").append(e.assistantResponse).append('\n')
        }
        sb.append("====================\n")
        sb.append("Please carefully read the context above, then listen to my latest audio input and respond to it, continuing our conversation.\n")
        return sb.toString()
    }

    private fun estimateTokensLocked(): Int {
        var chars = 0
        for (e in entries) {
            // Padding increased to account for the larger prompt wrappers
            chars += e.userTranscript.length + e.assistantResponse.length + 100
        }
        return chars / 4
    }

    /**
     * Run a one-shot transcription on a fresh conversation. 
     * Note: originalSystemPrompt and samplerConfig are intentionally ignored here
     * to enforce a pure, factual transcription task without tools or persona contamination.
     */
    fun transcribeAudio(
        engine: Engine,
        audio: Content.AudioBytes,
    ): String {
        val instruction =
            "Transcribe the following audio verbatim into text. " +
            "Output ONLY the transcription, with no commentary, prefix, or quotes."
            
        var convo: Conversation? = null
        return try {
            Log.d(TAG, "Transcribing audio")
            convo = engine.createConversation(
                ConversationConfig(
                    // Hardcoded low-temperature sampler for strict factuality
                    samplerConfig = SamplerConfig(topK = 1, topP = 0.1, temperature = 0.1),
                    systemInstruction = Contents.of(Content.Text(instruction)),
                    tools = emptyList() // Ensure no tools are passed
                )
            )
            val resp = convo.sendMessage(Contents.of(listOf<Content>(audio)))
            val text = extractText(resp).trim()
            
            // Guardrail: If the model heard silence, it might output an apology instead of an empty string.
            // We reuse the guardrails here to prevent storing "USER: I can't hear you" in the user history.
            if (isAudioFailureResponse(text)) {
                Log.w(TAG, "Transcription model reported audio failure. Falling back to empty sentinel.")
                return "[empty transcription]"
            }
            
            val finalText = text.ifEmpty { "[empty transcription]" }
            Log.i(TAG, "Transcription ok (len=${finalText.length}): ${finalText.take(120)}")
            finalText
        } catch (e: Throwable) {
            Log.e(TAG, "Transcription failed", e)
            "[transcription failed]"
        } finally {
            try { convo?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Best-effort text extraction from a litertlm Message. Uses robust reflection
     * to ensure compatibility across litertlm versions and avoid ClassCastExceptions.
     */
    private fun extractText(response: Any?): String {
        if (response == null) return ""
        return try {
            val getter = response.javaClass.methods.firstOrNull {
                it.name == "getContents" && it.parameterCount == 0
            } ?: return response.toString()
            val contents = getter.invoke(response) ?: return response.toString()
            
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
                    val m = item.javaClass.methods.firstOrNull {
                        it.name == "getText" && it.parameterCount == 0
                    }
                    (m?.invoke(item) as? String)?.let { sb.append(it) }
                }
            }
            if (sb.isNotEmpty()) sb.toString() else response.toString()
        } catch (_: Throwable) {
            response.toString()
        }
    }

    companion object {
        private const val TAG = "LocalAudioHistory"

        private val AUDIO_FAILURE_PHRASES = listOf(
            "couldn't hear", "couldnt hear", "can't hear", "cant hear", "cannot hear",
            "didn't hear", "didnt hear", "unable to hear", "not able to hear",
            "no sound", "no audio", "empty audio", "silent audio", "silence",
            "couldn't understand the audio", "can't understand the audio",
            "couldn't recognize", "couldnt recognize", "can't recognize",
            "didn't recognize any", "unable to recognize",
            "no speech", "no voice", "inaudible",
        )
        
        private val APOLOGY_INABILITY_WORDS = listOf(
            "sorry", "apologize", "apologies", "unable", "cannot", "can't", "cant",
            "couldn't", "couldnt", "didn't", "didnt", "failed", "unclear",
        )
        
        private val AUDIO_REFERENCE_WORDS = listOf(
            "audio", "hear", "heard", "hearing", "sound", "voice", "speech",
            "recording", "recognize", "recognized", "understand the", "make out",
            "listen", "listened",
        )

        fun isAudioFailureResponse(responseText: String?): Boolean {
            if (responseText.isNullOrBlank()) return false
            val normalized = responseText.lowercase()
            
            val hit = AUDIO_FAILURE_PHRASES.firstOrNull { normalized.contains(it) }
            if (hit != null) {
                Log.i(TAG, "Audio-failure response detected (phrase=\"$hit\")")
                return true
            }
            
            val apologyHit = APOLOGY_INABILITY_WORDS.firstOrNull { normalized.contains(it) }
            val audioHit = AUDIO_REFERENCE_WORDS.firstOrNull { normalized.contains(it) }
            if (apologyHit != null && audioHit != null) {
                Log.i(
                    TAG,
                    "Audio-failure response detected (cross-match: \"$apologyHit\" + \"$audioHit\")"
                )
                return true
            }
 
            return false
        }

        fun isResetCommand(transcript: String?): Boolean {
            if (transcript.isNullOrBlank()) return false
            val normalized = transcript.trim()
                .lowercase()
                .trimEnd('.', '!', '?', ',', ' ')
            val isReset = normalized == "reset" || normalized == "reset history"
            if (isReset) {
                Log.i(TAG, "Reset command detected in transcript: \"$transcript\"")
            }
            return isReset
        }
    }
}