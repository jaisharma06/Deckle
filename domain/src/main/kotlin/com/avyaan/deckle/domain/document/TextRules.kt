package com.avyaan.deckle.domain.document

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** FR-43: default title `Scan YYYY-MM-DD HH:mm`. */
public object DefaultTitle {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    public fun at(instant: Instant, zone: ZoneId): String = "Scan " + FORMAT.format(instant.atZone(zone))
}

/**
 * Turns what the user typed into a safe FTS4 MATCH expression (FR-3).
 * Each token is quoted and prefix-matched: `hello wor` → `"hello*" "wor*"` (FTS4 wants the star inside the quotes).
 * Returns null when nothing searchable is left (then only titles are searched).
 */
public object SearchQuery {
    private val FTS_OPERATORS = setOf("AND", "OR", "NOT", "NEAR")
    private val STRIP = Regex("[\"*():^\\-]")

    public fun toFts(raw: String): String? {
        val tokens = raw.split(Regex("\\s+"))
            .map { it.replace(STRIP, "") }
            .filter { it.isNotEmpty() && it.uppercase() !in FTS_OPERATORS }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"$it*\"" }
    }

    /** Escapes LIKE wildcards; the DAO uses `ESCAPE '\'`. */
    public fun toLike(raw: String): String =
        raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/** Parses an FTS `snippet()` result whose matches are wrapped in control-character markers. */
public object SnippetParser {
    public const val START: Char = '\u0002'
    public const val END: Char = '\u0003'

    public fun parse(marked: String): Snippet {
        val out = StringBuilder(marked.length)
        val ranges = mutableListOf<IntRange>()
        var start = -1
        for (c in marked) {
            when (c) {
                START -> start = out.length
                END -> if (start >= 0) {
                    if (out.length > start) ranges += start until out.length
                    start = -1
                }
                else -> out.append(if (c == '\n') ' ' else c)
            }
        }
        return Snippet(out.toString(), ranges)
    }
}

/** Sanitises a user-entered title. Blank → null, so the caller can refuse it. */
public object TitleRules {
    public const val MAX_LENGTH: Int = 120

    public fun clean(raw: String): String? = raw.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).ifEmpty { null }

    /** Safe base name for exported files. */
    public fun fileName(title: String): String =
        title.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "Deckle scan" }.take(80)
}
