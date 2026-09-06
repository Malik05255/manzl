package com.manzl.movietranslator

import org.json.JSONObject
import java.io.File

/**
 * Minimal tokenizer for facebook/m2m100_418M, fixed to Turkish -> Arabic.
 *
 * Hugging Face's M2M100 tokenizer prefixes the source with a language token and appends EOS. The
 * model vocabulary is stored in vocab.json; language ids are appended after that vocabulary in the
 * canonical 100-language ordering.
 */
internal class M2M100Tokenizer {
    private var processor: SentencePieceBpe? = null
    private var tokenToId = emptyMap<String, Long>()
    private var idToToken = emptyMap<Long, String>()
    private var sourceLanguageToken = -1L
    private var targetLanguageToken = -1L
    private var baseVocabularySize = 0

    fun initialize(modelDir: File) {
        val spmFile = File(modelDir, SENTENCEPIECE_FILENAME)
        val vocabFile = File(modelDir, VOCAB_FILENAME)
        require(spmFile.isFile) { "ملف SentencePiece الخاص بـ M2M100 غير موجود." }
        require(vocabFile.isFile) { "ملف مفردات M2M100 غير موجود." }

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

        require(forward.size in 120_000..130_000) { "مفردات M2M100 غير صالحة أو غير مكتملة." }
        require(forward["<s>"] == BOS_TOKEN_ID)
        require(forward["<pad>"] == PAD_TOKEN_ID)
        require(forward["</s>"] == EOS_TOKEN_ID)
        require(forward["<unk>"] == UNK_TOKEN_ID)

        processor = sp
        tokenToId = forward
        idToToken = reverse
        baseVocabularySize = forward.size
        sourceLanguageToken = baseVocabularySize.toLong() + TURKISH_LANGUAGE_INDEX
        targetLanguageToken = baseVocabularySize.toLong() + ARABIC_LANGUAGE_INDEX
    }

    fun encodeTurkish(text: String): LongArray {
        val sp = processor ?: error("M2M100 tokenizer غير جاهز.")
        check(sourceLanguageToken > 0L)
        val ids = sp.encodePieces(text).map { piece ->
            tokenToId[piece] ?: UNK_TOKEN_ID
        }.toLongArray()
        return longArrayOf(sourceLanguageToken) + ids + longArrayOf(EOS_TOKEN_ID)
    }

    fun arabicTargetTokenId(): Long {
        check(targetLanguageToken > 0L) { "M2M100 tokenizer غير جاهز." }
        return targetLanguageToken
    }

    fun decodeArabic(tokenIds: LongArray): String {
        val sp = processor ?: error("M2M100 tokenizer غير جاهز.")
        val pieces = tokenIds.mapNotNull { id ->
            when {
                id == BOS_TOKEN_ID || id == PAD_TOKEN_ID || id == EOS_TOKEN_ID || id == UNK_TOKEN_ID -> null
                id >= baseVocabularySize -> null // language and made-up special tokens
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
        sourceLanguageToken = -1L
        targetLanguageToken = -1L
        baseVocabularySize = 0
    }

    companion object {
        const val BOS_TOKEN_ID = 0L
        const val PAD_TOKEN_ID = 1L
        const val EOS_TOKEN_ID = 2L
        const val UNK_TOKEN_ID = 3L

        private const val SENTENCEPIECE_FILENAME = "sentencepiece.bpe.model"
        private const val VOCAB_FILENAME = "vocab.json"

        // Positions in Hugging Face FAIRSEQ_LANGUAGE_CODES["m2m100"].
        internal const val ARABIC_LANGUAGE_INDEX = 2L
        internal const val TURKISH_LANGUAGE_INDEX = 89L
    }
}
