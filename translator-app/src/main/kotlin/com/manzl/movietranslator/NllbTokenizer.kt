package com.manzl.movietranslator

import java.io.File

/** Turkish (tur_Latn) -> Modern Standard Arabic (arb_Arab) NLLB tokenizer. */
internal class NllbTokenizer {
    private var processor: SentencePieceBpe? = null
    private var sourceLanguageToken = -1L
    private var targetLanguageToken = -1L

    fun initialize(modelDir: File) {
        val vocabulary = File(modelDir, TOKENIZER_FILENAME)
        require(vocabulary.isFile) { "ملف مفردات NLLB غير موجود." }
        val sp = SentencePieceBpe().also { it.loadModel(vocabulary) }
        processor = sp

        // NLLB fairseq inserts one token before SentencePiece IDs. Language tokens then follow the
        // whole SPM vocabulary in the canonical 202-language ordering. We only need the two fixed
        // languages used by this app, so keeping their canonical indices avoids shipping a large
        // language map in the runtime.
        val languageBase = sp.vocabSize.toLong() + FAIRSEQ_OFFSET
        sourceLanguageToken = languageBase + TURKISH_LANGUAGE_INDEX
        targetLanguageToken = languageBase + ARABIC_LANGUAGE_INDEX
    }

    fun encodeTurkish(text: String): LongArray {
        val sp = processor ?: error("NLLB tokenizer غير جاهز.")
        check(sourceLanguageToken > 0L)
        val pieces = sp.encode(text)
        val modelIds = LongArray(pieces.size) { index ->
            val id = pieces[index]
            if (id == 0) UNK_MODEL_ID else id.toLong() + FAIRSEQ_OFFSET
        }
        return longArrayOf(sourceLanguageToken) + modelIds + longArrayOf(EOS_TOKEN_ID)
    }

    fun arabicTargetTokenId(): Long {
        check(targetLanguageToken > 0L) { "NLLB tokenizer غير جاهز." }
        return targetLanguageToken
    }

    fun decodeArabic(tokenIds: LongArray): String {
        val sp = processor ?: error("NLLB tokenizer غير جاهز.")
        val filtered = tokenIds.filter { id ->
            id > UNK_MODEL_ID && id != sourceLanguageToken && id != targetLanguageToken
        }
        if (filtered.isEmpty()) return ""
        val sentencePieceIds = filtered.map { (it - FAIRSEQ_OFFSET).toInt() }.toIntArray()
        return sp.decode(sentencePieceIds).trim()
    }

    fun close() {
        processor?.close()
        processor = null
        sourceLanguageToken = -1L
        targetLanguageToken = -1L
    }

    companion object {
        const val EOS_TOKEN_ID = 2L
        private const val TOKENIZER_FILENAME = "sentencepiece.bpe.model"
        private const val FAIRSEQ_OFFSET = 1L
        private const val UNK_MODEL_ID = 3L

        // Canonical FAIRSEQ_LANGUAGE_CODES positions used by NLLB-200.
        internal const val ARABIC_LANGUAGE_INDEX = 10L // arb_Arab
        internal const val TURKISH_LANGUAGE_INDEX = 183L // tur_Latn
    }
}
