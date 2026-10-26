package com.yuxuan.lingo.data

import com.yuxuan.lingo.domain.Collocation
import com.yuxuan.lingo.domain.Confusable
import com.yuxuan.lingo.domain.Derivative
import com.yuxuan.lingo.domain.Example
import com.yuxuan.lingo.domain.Word
import com.yuxuan.lingo.domain.Synonym
import com.yuxuan.lingo.domain.WordForm
import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** The packaged public-data vocabulary is validated before its atomic first-run import. */
internal object WordJsonCodec {
    /** Complete build-time cache; no JSON trees or database CursorWindows on the hot path. */
    fun readPreparedVocabulary(input: InputStream, sourceHash: String, expectedCount: Int): List<Word> {
        val data = VocabularyInput(input)
        val magic = ByteArray(8).also(data::readFully)
        require(magic.contentEquals("LINGOV04".toByteArray(Charsets.US_ASCII))) { "词库缓存版本错误" }
        require(data.rawString() == sourceHash) { "词库缓存来源错误" }
        val count = data.readInt()
        require(count == expectedCount && count in 1..100_000) { "词库缓存数量错误" }
        data.readStringTable()
        val ids = HashSet<String>(count)
        val words = List(count) {
            Word(
                id = data.string(), word = data.string(), pronunciationUs = data.string(),
                partOfSpeech = data.string(), meaningZh = data.string(),
                difficulty = data.readInt(), rank = data.readInt(),
                examples = data.items { Example(string(), string(), string(), string()) },
                collocations = data.items { Collocation(string(), string()) },
                derivatives = data.items { Derivative(string(), string()) },
                confusables = data.items { Confusable(string(), string(), string()) },
                wordForms = data.items { WordForm(string(), string()) },
                synonyms = data.items { Synonym(string(), string(), string()) },
                pronunciationUk = data.string(),
            ).also { word ->
                require(ids.add(word.id) && word.id == word.word && word.id.isNotBlank()) { "词库缓存单词错误" }
                require(word.difficulty in 1..10 && word.meaningZh.isNotBlank()) { "词库缓存内容错误" }
            }
        }
        require(data.read() == -1) { "词库缓存包含多余数据" }
        return words
    }

    private class VocabularyInput(input: InputStream) : DataInputStream(input) {
        private var scratch = ByteArray(1024)
        private lateinit var strings: Array<String>

        fun readStringTable() {
            val count = readInt()
            require(count in 1..1_000_000) { "词库字符串表数量错误" }
            strings = Array(count) { rawString() }
        }

        fun string(): String {
            val index = readInt()
            require(index in strings.indices) { "词库字符串引用错误" }
            return strings[index]
        }

        fun rawString(): String {
            val length = readInt()
            require(length in 0..1_048_576) { "词库缓存字符串长度错误" }
            if (length == 0) return ""
            if (scratch.size < length) scratch = ByteArray(length)
            readFully(scratch, 0, length)
            return String(scratch, 0, length, Charsets.UTF_8)
        }

        fun <T> items(readItem: VocabularyInput.() -> T): List<T> {
            val count = readInt()
            require(count in 0..10_000) { "词库缓存列表长度错误" }
            return List(count) { readItem() }
        }
    }

    /** Validates the exact bytes Room copies as well as the binary cache at EOF. */
    fun checkedStream(input: InputStream, expectedHash: String): InputStream {
        val digest = MessageDigest.getInstance("SHA-256")
        return object : FilterInputStream(DigestInputStream(input, digest)) {
            private var verified = false
            private fun verify() {
                if (verified) return
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != expectedHash) throw IOException("预编译词库校验失败")
                verified = true
            }
            override fun read(): Int = super.read().also { if (it == -1) verify() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                `in`.read(buffer, offset, length).also { if (it == -1) verify() }
        }
    }

    fun parseVocabulary(json: String): List<WordEntity> {
        val array = JSONArray(json)
        require(array.length() > 0) { "内置词库为空" }
        val ids = HashSet<String>()
        return List(array.length()) { index ->
            val payload = array.getJSONObject(index)
            val word = decode(payload)
            require(ids.add(word.id)) { "词库包含重复单词：${word.id}" }
            WordEntity(word.id, word.difficulty, word.rank, payload.toString())
        }
    }

    /** Read only canonical IDs when an older install lacks the active-ID metadata and
     * the optional binary cache is unavailable. Large detail payloads are skipped. */
    fun readVocabularyIds(reader: Reader): Set<String> {
        val json = JsonReader(reader)
        val ids = HashSet<String>()
        json.beginArray()
        while (json.hasNext()) {
            var id: String? = null
            var spelling: String? = null
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "id" -> id = json.nextString().trim()
                    "word" -> spelling = json.nextString().trim()
                    else -> json.skipValue()
                }
            }
            json.endObject()
            val canonical = id ?: spelling
            require(!canonical.isNullOrBlank() && canonical == spelling && ids.add(canonical)) { "词库单词 ID 错误" }
        }
        json.endArray()
        require(ids.isNotEmpty() && json.peek() == JsonToken.END_DOCUMENT) { "词库内容错误" }
        return ids
    }

    /** Caller owns the reader. At most one JSON object is materialized at a time. */
    fun readVocabulary(reader: Reader): Sequence<WordEntity> = sequence {
        val json = JsonReader(reader)
        val ids = HashSet<String>()
        json.beginArray()
        while (json.hasNext()) {
            val payload = readValue(json) as JSONObject
            val word = decode(payload)
            require(ids.add(word.id)) { "词库包含重复单词：${word.id}" }
            yield(WordEntity(word.id, word.difficulty, word.rank, payload.toString()))
        }
        json.endArray()
        require(ids.isNotEmpty()) { "内置词库为空" }
        require(json.peek() == JsonToken.END_DOCUMENT) { "词库包含多余数据" }
    }

    private fun readValue(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().also { value ->
            reader.beginObject()
            while (reader.hasNext()) value.put(reader.nextName(), readValue(reader))
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().also { value ->
            reader.beginArray()
            while (reader.hasNext()) value.put(readValue(reader))
            reader.endArray()
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
        else -> error("无效词库 JSON")
    }

    fun decode(payload: String): Word = decode(JSONObject(payload))

    private fun decode(value: JSONObject): Word {
        val spelling = value.getString("word").trim()
        val id = value.optString("id", spelling).trim()
        val difficulty = value.getInt("difficulty")
        require(spelling.isNotBlank() && id == spelling) { "词库单词 ID 必须与拼写相同" }
        require(difficulty in 1..10) { "$spelling 的难度必须介于 1 与 10" }
        val meaning = value.getString("meaningZh").trim()
        require(meaning.isNotBlank()) { "$spelling 缺少中文释义" }
        return Word(
            id = id,
            word = spelling,
            pronunciationUs = value.getString("pronunciationUs"),
            partOfSpeech = value.getString("partOfSpeech"),
            meaningZh = meaning,
            difficulty = difficulty,
            rank = value.getInt("rank"),
            examples = value.optJSONArray("examples").objects {
                Example(it.getString("en"), it.getString("zh"), it.optString("attribution"), it.optString("sourceUrl"))
            },
            collocations = value.optJSONArray("collocations").objects {
                Collocation(it.getString("en"), it.getString("zh"))
            },
            derivatives = value.optJSONArray("derivatives").objects {
                Derivative(it.getString("word"), it.getString("meaningZh"))
            },
            confusables = value.optJSONArray("confusables").objects {
                Confusable(it.getString("word"), it.getString("meaningZh"), it.getString("note"))
            },
            wordForms = value.optJSONArray("wordForms").objects {
                WordForm(it.getString("word"), it.getString("meaningZh"))
            },
            synonyms = value.optJSONArray("synonyms").objects { Synonym(it.getString("word"),it.getString("meaningZh"),it.optString("senseEn")) },
            pronunciationUk = value.optString("pronunciationUk", ""),
        )
    }

    private fun <T> JSONArray?.objects(transform: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else List(length()) { transform(getJSONObject(it)) }
}
