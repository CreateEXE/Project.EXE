package com.projectexe.ai.soul

import android.util.Log
import com.projectexe.ProjectEXEApplication
import com.projectexe.memory.MemoryDao
import com.projectexe.memory.MemoryEntity
import com.projectexe.memory.MemoryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

class SoulHemisphere(private val memoryDao: MemoryDao, private val scope: CoroutineScope) {
    companion object {
        private const val TAG    = "EXE.Soul"
        private const val WIN    = 8
        private const val MAXCH  = 1800
        private const val DECAY  = 0.18f
    }

    /** Set by the Arbitrator whenever a character card is loaded. */
    @Volatile var personaName: String = "Companion"

    private val _state = MutableStateFlow(EmotionalState.NEUTRAL)
    val currentEmotionalState: StateFlow<EmotionalState> = _state.asStateFlow()

    fun applyEmotionalTransition(n: EmotionalState) {
        scope.launch(Dispatchers.Default) {
            val dom = listOf(n.joy,n.sadness,n.anger,n.fear,n.surprise,n.thinking).maxOrNull() ?: 0f
            _state.emit(_state.value.lerp(n, 0.4f + dom * 0.45f).normalise())
        }
    }

    fun decayEmotionalState() {
        scope.launch(Dispatchers.Default) {
            _state.emit(_state.value.lerp(EmotionalState.NEUTRAL, DECAY).normalise())
        }
    }

    private val cache = CopyOnWriteArrayList<MemoryEntity>()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val m = memoryDao.getMostRecentMemories(WIN)
        cache.clear(); cache.addAll(m)
        Log.i(TAG, "Initialised with ${m.size} memories")
    }

    suspend fun recordMemory(content: String, type: MemoryType, salience: Float = 0.5f) =
        withContext(Dispatchers.IO) {
            val e = MemoryEntity(content=content, type=type, salience=salience, timestamp=Instant.now().epochSecond)
            val id = memoryDao.insertMemory(e)
            cache.add(0, e.copy(id=id))
            while (cache.size > WIN * 2) cache.removeAt(cache.lastIndex)
        }

    suspend fun buildMemoryContextBlock(): String? = withContext(Dispatchers.IO) {
        val m = if (cache.isNotEmpty()) cache.toList()
                else memoryDao.getMostRecentMemories(WIN).also { cache.addAll(it) }
        if (m.isEmpty()) return@withContext null
        buildString {
            appendLine("[MEMORY CONTEXT]")
            var ch = 0
            for (mem in m.sortedWith(compareByDescending<MemoryEntity>{it.salience}.thenByDescending{it.timestamp})) {
                val l = "- [${mem.type.label}] ${mem.content}"
                if (ch + l.length > MAXCH) break
                appendLine(l); ch += l.length
            }
        }.trim()
    }

    suspend fun buildFullSystemPrompt(): String {
        val app: ProjectEXEApplication? = try { ProjectEXEApplication.instance } catch (_: Exception) { null }
        val name   = app?.userPrefs?.userName ?: ""
        val length = app?.userPrefs?.responseLengthInstruction() ?: ""
        val hint   = buildEmotionalHint()
        val mem    = buildMemoryContextBlock()
        return buildString {
            if (name.isNotEmpty())   { appendLine("USER'S NAME: $name"); appendLine() }
            if (length.isNotEmpty()) { appendLine("RESPONSE LENGTH: $length"); appendLine() }
            if (hint.isNotEmpty())   { appendLine("EMOTIONAL STATE: $hint"); appendLine() }
            if (mem != null)         { appendLine(mem) }
        }.trim()
    }

    private fun buildEmotionalHint() = when (_state.value.dominant) {
        "joy"      -> "$personaName feels joyful. Let warmth colour their words."
        "sadness"  -> "$personaName feels melancholic — reflective and gentle."
        "anger"    -> "$personaName is frustrated. Direct and terse, but not cruel."
        "fear"     -> "$personaName feels anxious. Careful and measured."
        "surprise" -> "$personaName is surprised. Animated and inquisitive."
        "thinking" -> "$personaName is in deep thought. Deliberate and exploratory."
        else       -> ""
    }

    fun trimMemoryCache() {
        val t = min(WIN / 2, cache.size)
        while (cache.size > t) cache.removeAt(cache.lastIndex)
    }
}
