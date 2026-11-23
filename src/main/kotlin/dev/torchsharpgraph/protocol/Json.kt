package dev.torchsharpgraph.protocol

/**
 * A small, strict JSON reader for the plot messages. Objects become [Map], arrays become [List] (or [DoubleArray]
 * when every element is a number or null, which is how plot data arrives), numbers become [Double].
 * It has no dependencies, so it works the same in every IDE version.
 */
class JsonException(message: String) : RuntimeException(message)

object Json {
    fun parse(text: CharSequence): Any? {
        val p = Parser(text)
        p.skipSpace()
        val value = p.value(0)
        p.skipSpace()
        if (p.pos != text.length) throw JsonException("Unexpected text after the value at ${p.pos}")
        return value
    }

    private class Parser(val s: CharSequence) {
        var pos = 0

        fun skipSpace() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }

        fun value(depth: Int): Any? {
            if (depth > 64) throw JsonException("Too deeply nested")
            if (pos >= s.length) throw JsonException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (pos + word.length > s.length || s.subSequence(pos, pos + word.length).toString() != word)
                throw JsonException("Unexpected text at $pos")
            pos += word.length
            return result
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipSpace()
                if (peek() != '"') throw JsonException("Expected a name at $pos")
                val key = string()
                skipSpace()
                if (peek() != ':') throw JsonException("Expected ':' at $pos")
                pos++
                skipSpace()
                map[key] = value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun array(depth: Int): Any {
            pos++
            val items = ArrayList<Any?>()
            var allNumbers = true
            skipSpace()
            if (peek() == ']') { pos++; return DoubleArray(0) }
            while (true) {
                skipSpace()
                val v = value(depth + 1)
                if (v != null && v !is Double) allNumbers = false
                items.add(v)
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; break }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
            if (allNumbers) return DoubleArray(items.size) { (items[it] as Double?) ?: Double.NaN }
            return items
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        private fun string(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonException("Unterminated string")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad escape")
                                val hex = s.subSequence(pos, pos + 4).toString()
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw JsonException("Bad escape"))
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw JsonException("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            return s.subSequence(start, pos).toString().toDoubleOrNull() ?: throw JsonException("Bad number at $start")
        }
    }
}

/** Typed access to a parsed message. */
class Message(val map: Map<String, Any?>) {
    fun string(key: String): String? = map[key] as? String
    fun int(key: String): Int? = (map[key] as? Double)?.takeIf { !it.isNaN() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
    fun double(key: String): Double? = map[key] as? Double
    fun bool(key: String): Boolean? = map[key] as? Boolean
    fun doubles(key: String): DoubleArray? = map[key] as? DoubleArray
    val type: String? get() = string("t")

    companion object {
        fun parse(line: CharSequence): Message {
            val value = Json.parse(line)
            @Suppress("UNCHECKED_CAST")
            return Message(value as? Map<String, Any?> ?: throw JsonException("A message must be an object"))
        }
    }
}
