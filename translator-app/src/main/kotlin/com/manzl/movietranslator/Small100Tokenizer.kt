package com.manzl.movietranslator

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import org.json.JSONObject
import java.io.File

/** Validated Hugging Face fast tokenizer + target-language map for SMaLL-100. */
internal class Small100Tokenizer {
    private var tokenizer: HuggingFaceTokenizer? = null
    private var arabicLanguageToken = -1L

    fun initialize(modelDir: File) {
        val tokenizerFile = File(modelDir, TOKENIZER_FILENAME)
        val languageFile = File(modelDir, LANGUAGE_MAP_FILENAME)
        require(tokenizerFile.isFile) { "ملف tokenizer.json الخاص بـ SMaLL-100 غير موجود." }
        require(languageFile.isFile) { "ملف lang_tokens.json الخاص بـ SMaLL-100 غير موجود." }

        val languageJson = JSONObject(languageFile.readText())
        val langToId = languageJson.getJSONObject("lang_to_id")
        val ar = langToId.getLong("ar")
        require(ar == EXPECTED_ARABIC_TOKEN_ID) {
            "رمز اللغة العربية في SMaLL-100 غير متوقع: $ar"
        }

        val loaded = tokenizerFile.inputStream().use { input ->
            HuggingFaceTokenizer.newInstance(input, emptyMap())
        }
        tokenizer = loaded
        arabicLanguageToken = ar
    }

    /** SMaLL-100 selects the target language by prefixing Arabic to the SOURCE token sequence. */
    fun encodeForArabic(text: String): LongArray {
        val tok = tokenizer ?: error("SMaLL-100 tokenizer غير جاهز.")
        check(arabicLanguageToken > 0L)
        val encoded = tok.encode(text).ids
        val withEos = if (encoded.lastOrNull() == EOS_TOKEN_ID) encoded else encoded + EOS_TOKEN_ID
        return longArrayOf(arabicLanguageToken) + withEos
    }

    fun decodeArabic(tokenIds: LongArray): String {
        val tok = tokenizer ?: error("SMaLL-100 tokenizer غير جاهز.")
        if (tokenIds.isEmpty()) return ""
        return tok.decode(tokenIds, true).trim()
    }

    fun close() {
        runCatching { tokenizer?.close() }
        tokenizer = null
        arabicLanguageToken = -1L
    }

    companion object {
        const val EOS_TOKEN_ID = 2L
        const val DECODER_START_TOKEN_ID = 2L
        private const val TOKENIZER_FILENAME = "tokenizer.json"
        private const val LANGUAGE_MAP_FILENAME = "lang_tokens.json"
        internal const val EXPECTED_ARABIC_TOKEN_ID = 128006L
    }
}
