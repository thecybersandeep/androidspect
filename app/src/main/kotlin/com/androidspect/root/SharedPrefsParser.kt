package com.androidspect.root

import android.util.Xml
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * Parser AND writer for Android's SharedPreferences XML format.
 *
 * The XML on disk looks like:
 *   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
 *   <map>
 *     <string name="k">v</string>
 *     <int name="k" value="42"/>
 *     <boolean name="k" value="true"/>
 *     <long .../>  <float .../>
 *     <set name="k"><string>...</string></set>
 *   </map>
 *
 * Typed entries are *critical* for editing - flipping `isPaid=true` only works
 * if we emit it back as `<boolean>` not `<string>`.
 */
object SharedPrefsParser {

    /** Backwards-compat string-only parse used by the prefs *view* endpoint. */
    fun parse(bytes: ByteArray): Map<String, String> =
        parseTyped(bytes).entries.associate { (k, v) -> k to v.value }

    /** Type-aware parse - every entry carries its XML type so we can round-trip. */
    fun parseTyped(bytes: ByteArray): LinkedHashMap<String, TypedEntry> {
        if (bytes.isEmpty()) return linkedMapOf()
        val parser = Xml.newPullParser()
        ByteArrayInputStream(bytes).use { input ->
            parser.setInput(input, Charsets.UTF_8.name())
            val out = linkedMapOf<String, TypedEntry>()
            var event = parser.eventType
            var currentKey: String? = null
            var currentType: PrefType? = null
            var inSet = false
            val setItems = mutableListOf<String>()
            var stringBuffer = StringBuilder()

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "string" -> {
                            if (inSet) {
                                stringBuffer = StringBuilder()
                            } else {
                                currentKey = parser.getAttributeValue(null, "name")
                                currentType = PrefType.STRING
                                stringBuffer = StringBuilder()
                            }
                        }
                        "int", "long", "float", "boolean" -> {
                            val k = parser.getAttributeValue(null, "name") ?: ""
                            val v = parser.getAttributeValue(null, "value") ?: ""
                            val t = when (parser.name) {
                                "int" -> PrefType.INT
                                "long" -> PrefType.LONG
                                "float" -> PrefType.FLOAT
                                "boolean" -> PrefType.BOOLEAN
                                else -> PrefType.STRING
                            }
                            if (k.isNotEmpty()) out[k] = TypedEntry(t, v)
                        }
                        "set" -> {
                            currentKey = parser.getAttributeValue(null, "name")
                            currentType = PrefType.SET
                            inSet = true
                            setItems.clear()
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inSet || (currentKey != null && currentType == PrefType.STRING)) {
                            val t = parser.text
                            if (t != null) stringBuffer.append(t)
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "string" -> {
                            if (inSet) {
                                setItems += stringBuffer.toString()
                            } else if (currentKey != null) {
                                out[currentKey!!] = TypedEntry(PrefType.STRING, stringBuffer.toString())
                                currentKey = null
                                currentType = null
                            }
                            stringBuffer = StringBuilder()
                        }
                        "set" -> {
                            currentKey?.let {
                                out[it] = TypedEntry(PrefType.SET, setItems.joinToString(prefix = "{", postfix = "}", separator = ", "))
                            }
                            inSet = false
                            currentKey = null
                            currentType = null
                        }
                    }
                }
                event = parser.next()
            }
            return out
        }
    }

    /**
     * Update one key in an existing prefs XML and return the new bytes. Type
     * is preserved if the key already exists; otherwise we default to [STRING]
     * unless a [typeHint] is supplied.
     * Pass `value = null` to delete the key.
     */
    fun upsert(bytes: ByteArray, key: String, value: String?, typeHint: PrefType? = null): ByteArray {
        val typed = parseTyped(bytes)
        if (value == null) {
            typed.remove(key)
        } else {
            val existing = typed[key]
            val t = typeHint ?: existing?.type ?: PrefType.STRING
            typed[key] = TypedEntry(t, value)
        }
        return serialize(typed)
    }

    /** Render the type-aware map back to the canonical XML format. */
    fun serialize(typed: Map<String, TypedEntry>): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        for ((k, e) in typed) {
            val key = xmlAttr(k)
            when (e.type) {
                PrefType.STRING ->
                    sb.append("    <string name=\"").append(key).append("\">")
                      .append(xmlText(e.value)).append("</string>\n")
                PrefType.INT ->
                    sb.append("    <int name=\"").append(key).append("\" value=\"")
                      .append(xmlAttr(e.value)).append("\" />\n")
                PrefType.LONG ->
                    sb.append("    <long name=\"").append(key).append("\" value=\"")
                      .append(xmlAttr(e.value)).append("\" />\n")
                PrefType.FLOAT ->
                    sb.append("    <float name=\"").append(key).append("\" value=\"")
                      .append(xmlAttr(e.value)).append("\" />\n")
                PrefType.BOOLEAN ->
                    sb.append("    <boolean name=\"").append(key).append("\" value=\"")
                      .append(if (e.value.toBoolean()) "true" else "false").append("\" />\n")
                PrefType.SET -> {
                    sb.append("    <set name=\"").append(key).append("\">\n")
                    val items = e.value.removePrefix("{").removeSuffix("}").split(",").map { it.trim() }
                    items.filter { it.isNotEmpty() }.forEach {
                        sb.append("        <string>").append(xmlText(it)).append("</string>\n")
                    }
                    sb.append("    </set>\n")
                }
            }
        }
        sb.append("</map>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun xmlAttr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    private fun xmlText(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    @Serializable
    data class TypedEntry(val type: PrefType, val value: String)

    @Serializable
    enum class PrefType { STRING, INT, LONG, FLOAT, BOOLEAN, SET }
}
