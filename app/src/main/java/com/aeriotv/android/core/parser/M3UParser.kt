package com.aeriotv.android.core.parser

import com.aeriotv.android.core.data.M3UChannel

/**
 * M3U/M3U8 playlist parser. Ports iOS Aerio/Networking/PlaylistParsers.swift
 * (lines 16-105) to Kotlin. Matches algorithm and edge-case handling line-for-line:
 *  - Line-by-line scan with lookahead for the URL line after #EXTINF
 *  - Skip blank lines and # comments between #EXTINF and its URL
 *  - Attributes parsed by regex `([\w-]+)="([^"]*)"`, keys lowercased
 *  - Display name comes from text after the last comma in the #EXTINF line
 *  - Aggressive whitespace trimming on every candidate line
 *  - UTF-8 first, fall back to ISO-8859-1 (handled by [parseBytes])
 *  - Unquoted attributes silently ignored
 *  - #EXTINF without a URL line is silently dropped (not an error)
 *  - #EXTVLCOPT / #KODIPROP / #EXTGRP / #EXT-X-* are not parsed
 */
object M3UParser {

    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)="([^"]*)"""")

    /**
     * Try UTF-8, fall back to ISO-8859-1. Mirrors iOS String(data:encoding:) fallback.
     * BOM is consumed automatically by both decoders.
     */
    fun parseBytes(bytes: ByteArray): List<M3UChannel> {
        val text = runCatching { String(bytes, Charsets.UTF_8) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
        return parse(text)
    }

    fun parse(content: String): List<M3UChannel> {
        val channels = mutableListOf<M3UChannel>()
        val lines = content.split('\n', '\r').filter { true } // keep blanks for index math

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.startsWith("#EXTINF:")) {
                val attrs = parseExtInf(line)

                var j = i + 1
                var url = ""
                while (j < lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.isNotEmpty() && !candidate.startsWith("#")) {
                        url = candidate
                        i = j
                        break
                    }
                    j++
                }

                if (url.isNotEmpty()) {
                    channels += M3UChannel(
                        name = attrs["name"]?.ifBlank { null } ?: "Unknown Channel",
                        url = url,
                        groupTitle = attrs["group-title"].orEmpty(),
                        tvgID = attrs["tvg-id"].orEmpty(),
                        tvgName = attrs["tvg-name"].orEmpty(),
                        tvgLogo = attrs["tvg-logo"].orEmpty(),
                        channelNumber = attrs["tvg-chno"]?.trim()?.takeIf { it.isNotBlank() },
                        rawAttributes = attrs.filterKeys { it != "name" },
                    )
                }
            }
            i++
        }

        return channels
    }

    private fun parseExtInf(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        val commaIndex = line.lastIndexOf(',')
        if (commaIndex >= 0 && commaIndex < line.length - 1) {
            result["name"] = line.substring(commaIndex + 1).trim()
        }

        for (match in ATTRIBUTE_REGEX.findAll(line)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
            result[key] = value
        }

        return result
    }
}
