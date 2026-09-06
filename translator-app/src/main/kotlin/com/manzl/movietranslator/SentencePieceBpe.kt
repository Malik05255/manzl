package com.manzl.movietranslator

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal SentencePiece BPE reader used by the on-device NLLB translator.
 *
 * Adapted from the MIT-licensed Android implementation used by Light-Translator /
 * InstantVoiceTranslate. Keeping tokenization in Kotlin avoids adding another native tokenizer
 * library beside ONNX Runtime.
 */
internal class SentencePieceBpe {
    private data class VocabPiece(
        val piece: String,
        val score: Float,
        val type: Int,
    )

    private var pieces = emptyList<VocabPiece>()
    private var pieceToId = emptyMap<String, Int>()
    private var idToPiece = emptyMap<Int, String>()
    private var unkId = 0

    val vocabSize: Int get() = pieces.size

    fun loadModel(modelFile: File) {
        val bytes = modelFile.readBytes()
        pieces = parseModelProto(bytes)
        require(pieces.size > 250_000) {
            "ملف مفردات NLLB غير صالح أو غير مكتمل."
        }
        pieceToId = HashMap<String, Int>(pieces.size * 2).also { map ->
            pieces.forEachIndexed { index, piece -> map[piece.piece] = index }
        }
        idToPiece = HashMap<Int, String>(pieces.size * 2).also { map ->
            pieces.forEachIndexed { index, piece -> map[index] = piece.piece }
        }
        unkId = pieceToId["<unk>"] ?: 0
    }

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf()
        val normalized = SPACE_MARKER_STR + text.replace(" ", SPACE_MARKER_STR)
        val symbols = ArrayList<String>(normalized.length)
        normalized.forEach { symbols += it.toString() }

        while (symbols.size > 1) {
            var bestScore = Float.NEGATIVE_INFINITY
            var bestIndex = -1
            for (index in 0 until symbols.size - 1) {
                val merged = symbols[index] + symbols[index + 1]
                val id = pieceToId[merged] ?: continue
                val score = pieces[id].score
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] = symbols[bestIndex] + symbols[bestIndex + 1]
            symbols.removeAt(bestIndex + 1)
        }

        return IntArray(symbols.size) { index -> pieceToId[symbols[index]] ?: unkId }
    }

    fun decode(ids: IntArray): String {
        val result = StringBuilder()
        ids.forEach { id -> result.append(idToPiece[id].orEmpty()) }
        return result.toString().replace(SPACE_MARKER, ' ').trimStart()
    }

    fun close() {
        pieces = emptyList()
        pieceToId = emptyMap()
        idToPiece = emptyMap()
    }

    private fun parseModelProto(data: ByteArray): List<VocabPiece> {
        val result = mutableListOf<VocabPiece>()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            val tag = readVarint(buffer)
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            if (fieldNumber == 1 && wireType == 2) {
                val length = readVarint(buffer).toInt()
                require(length >= 0 && length <= buffer.remaining()) { "ملف SentencePiece تالف." }
                val pieceBytes = ByteArray(length)
                buffer.get(pieceBytes)
                result += parseSentencePiece(pieceBytes)
            } else {
                skipField(buffer, wireType)
            }
        }
        return result
    }

    private fun parseSentencePiece(data: ByteArray): VocabPiece {
        var piece = ""
        var score = 0f
        var type = 1
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            val tag = readVarint(buffer)
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when {
                fieldNumber == 1 && wireType == 2 -> {
                    val length = readVarint(buffer).toInt()
                    require(length >= 0 && length <= buffer.remaining()) { "ملف SentencePiece تالف." }
                    val value = ByteArray(length)
                    buffer.get(value)
                    piece = String(value, Charsets.UTF_8)
                }
                fieldNumber == 2 && wireType == 5 -> score = buffer.float
                fieldNumber == 3 && wireType == 0 -> type = readVarint(buffer).toInt()
                else -> skipField(buffer, wireType)
            }
        }
        return VocabPiece(piece, score, type)
    }

    private fun readVarint(buffer: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (buffer.hasRemaining()) {
            val byte = buffer.get().toLong() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0L) return result
            shift += 7
            require(shift <= 63) { "Varint غير صالح داخل SentencePiece." }
        }
        error("انتهى ملف SentencePiece قبل اكتمال Varint.")
    }

    private fun skipField(buffer: ByteBuffer, wireType: Int) {
        when (wireType) {
            0 -> readVarint(buffer)
            1 -> advance(buffer, 8)
            2 -> advance(buffer, readVarint(buffer).toInt())
            5 -> advance(buffer, 4)
            else -> error("نوع protobuf غير مدعوم: $wireType")
        }
    }

    private fun advance(buffer: ByteBuffer, count: Int) {
        require(count >= 0 && count <= buffer.remaining()) { "ملف SentencePiece تالف." }
        buffer.position(buffer.position() + count)
    }

    companion object {
        private const val SPACE_MARKER = '\u2581'
        private const val SPACE_MARKER_STR = "\u2581"
    }
}
