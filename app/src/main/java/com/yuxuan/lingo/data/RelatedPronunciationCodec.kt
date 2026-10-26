package com.yuxuan.lingo.data

import android.util.JsonReader
import com.yuxuan.lingo.domain.Word
import java.io.Reader

/** Supplemental readings only. Main-vocabulary IPA already lives in the word index. */
internal object RelatedPronunciationCodec {
    fun read(reader: Reader, vocabulary: Map<String, Word>): Map<String, Pair<String, String>> {
        val result = HashMap<String, Pair<String, String>>()
        JsonReader(reader).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                val spelling = json.nextName()
                val own = vocabulary[spelling]
                val needsUs = own?.pronunciationUs.isNullOrBlank()
                val needsUk = own?.pronunciationUk.isNullOrBlank()
                if (!needsUs && !needsUk) {
                    json.skipValue()
                    continue
                }
                var us = ""
                var uk = ""
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "us" -> if (needsUs) us = json.nextString() else json.skipValue()
                        "uk" -> if (needsUk) uk = json.nextString() else json.skipValue()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                if (us.isNotBlank() || uk.isNotBlank()) result[spelling] = us to uk
            }
            json.endObject()
        }
        return result
    }
}
