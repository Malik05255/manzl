package com.manzl.movietranslator

import org.json.JSONObject
import java.io.File

/**
 * Minimal SMaLL-100 tokenizer fixed to Turkish dialogue -> Arabic subtitles.
 *
 * SMaLL-100 differs from M2M100 generation: the target-language token is prepended to the SOURCE
 * sequence. Arabic is therefore [base vocab + 2] == 128006 for the official 128004-token vocab.
 */
internal class Small100Tokenizer {
    private var processor: SentencePieceBpe? = null
    private var tokenToId = emptyMap<String, Long>()
    private var idToToken = emptyMap<Long, String>()
    private var arabicLanguageToken = -1L
    private var baseVocabularySize = 0

    fun initialize(modelDir: File) {
        val spmFile = File(modelDir, SENTENCEPIECE_FILENAME)
        val vocabFile = File(modelDir, VOCAB_FILENAME)
        require(spmFile.isFile) { "ملف SentencePiece الخاص بـ SMaLL-100 غير موجود." }
        require(vocabFile.isFile) { "ملف مفردات SMaLL-100 غير موجود." }

        val sp = SentencePieceBpe().also { it.loadModel(spmFile) }
        val json = JSONObject(vocabFile.readText())
        val forward = HashMap<String, Long>(json.length() * 2)
        val reverse = HashMap<Long, String>(json.length() * 2)
        val keys = json.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            val id = json.getLong(token)
            forward[token] = id
            reverse[id] = token
        }

        require(forward.size in 127_000..129_000) { "مفردات SMaLL-100 غير صالحة أو غير مكتملة." }
        require(forward["<s>"] == BOS_TOKEN_ID)
        require(forward["<pad>"] == PAD_TOKEN_ID)
        require(forward["</s>"] == EOS_TOKEN_ID)
        require(forward["<unk>"] == UNK_TOKEN_ID)

        processor = sp
        tokenToId = forward
        idToToken = reverse
        baseVocabularySize = forward.size
        arabicLanguageToken = baseVocabularySize.toLong() + ARABIC_LANGUAGE_INDEX
        require(arabicLanguageToken == EXPECTED_ARABIC_TOKEN_ID) {
            "ترتيب مفردات SMaLL-100 غير متوقع: Arabic=$arabicLanguageToken"
        }
    }

    /** Target-language token + Turkish BPE pieces + EOS. */
    fun encodeForArabic(text: String): LongArray {
        val sp = processor ?: error("SMaLL-100 tokenizer غير جاهز.")
        check(arabicLanguageToken > 0L)
        val ids = sp.encodePieces(text).map { piece -> tokenToId[piece] ?: UNK_TOKEN_ID }.toLongArray()
        return longArrayOf(arabicLanguageToken) + ids + longArrayOf(EOS_TOKEN_ID)
    }

    fun decodeArabic(tokenIds: LongArray): String {
        val sp = processor ?: error("SMaLL-100 tokenizer غير جاهز.")
        val pieces = tokenIds.mapNotNull { id ->
            when {
                id == BOS_TOKEN_ID || id == PAD_TOKEN_ID || id == EOS_TOKEN_ID || id == UNK_TOKEN_ID -> null
                id >= baseVocabularySize -> null
                else -> idToToken[id]
            }
        }
        if (pieces.isEmpty()) return ""
        return sp.decodePieces(pieces).trim()
    }

    fun close() {
        processor?.close()
        processor = null
        tokenToId = emptyMap()
        idToToken = emptyMap()
        arabicLanguageToken = -1L
        baseVocabularySize = 0
    }

    companion object {
        const val BOS_TOKEN_ID = 0L
        const val PAD_TOKEN_ID = 1L
        const val EOS_TOKEN_ID = 2L
        const val UNK_TOKEN_ID = 3L
        const val DECODER_START_TOKEN_ID = 2L

        private const val SENTENCEPIECE_FILENAME = "sentencepiece.bpe.model"
        private const val VOCAB_FILENAME = "vocab.json"
        private const val ARABIC_LANGUAGE_INDEX = 2L
        internal const val EXPECTED_ARABIC_TOKEN_ID = 128006L
    }
}
