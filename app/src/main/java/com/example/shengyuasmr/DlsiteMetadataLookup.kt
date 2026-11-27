package com.example.shengyuasmr

import java.net.HttpURLConnection
import java.net.URL

data class DlsiteMetadata(
    val productId: String,
    val title: String,
    val coverUrl: String,
    val cv: List<String>,
    val circle: String,
    val tags: List<String>,
    val pageUrl: String
)

object DlsiteMetadataLookup {
    private val productIdRegex = Regex("(?i)\\bRJ\\d{6,8}\\b")
    private val htmlFlags = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    fun extractProductId(text: String): String? = productIdRegex.find(text)?.value?.uppercase()

    fun productUrl(productId: String): String {
        val id = productId.uppercase()
        return "https://www.dlsite.com/maniax/work/=/product_id/$id.html"
    }

    fun lookup(productId: String): DlsiteMetadata {
        val id = productId.uppercase()
        require(productIdRegex.matches(id)) { "RJ编号格式不正确" }
        val connection = URL(productUrl(id)).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        try {
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            fun meta(key: String, attribute: String = "property"): String {
                val patterns = listOf(
                    Regex("""<meta[^>]+$attribute=["']$key["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
                    Regex("""<meta[^>]+content=["']([^"']*)["'][^>]+$attribute=["']$key["']""", RegexOption.IGNORE_CASE)
                )
                return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
                    ?.htmlDecode()?.trim().orEmpty()
            }

            fun links(fragment: String): List<String> =
                Regex("""<a\b[^>]*>(.*?)</a>""", htmlFlags)
                    .findAll(fragment)
                    .map { it.groupValues[1].htmlText() }
                    .filter { it.isNotBlank() && it.length < 100 }
                    .distinct()
                    .toList()

            // DLsite's visible labels are 声優 and サークル. Read the links
            // immediately following each label instead of relying on table markup.
            fun labeledLinks(label: String, nextLabel: String): List<String> {
                val start = Regex(Regex.escape(label), RegexOption.IGNORE_CASE).find(html)?.range?.last?.plus(1) ?: return emptyList()
                val tail = html.substring(start, (start + 7000).coerceAtMost(html.length))
                val end = Regex(nextLabel, RegexOption.IGNORE_CASE).find(tail)?.range?.first ?: tail.length
                return links(tail.substring(0, end))
            }

            val title = meta("og:title").ifBlank {
                Regex("""<title[^>]*>(.*?)</title>""", htmlFlags)
                    .find(html)?.groupValues?.getOrNull(1)?.htmlText().orEmpty()
            }
            val cover = meta("og:image")

            val cv = labeledLinks("声優", "サークル|作品形式|販売日|シナリオ")
                .ifEmpty { labeledLinks("CV", "サークル|作品形式|販売日") }

            val circle = labeledLinks("サークル", "声優|作品形式|販売日|シナリオ")
                .firstOrNull().orEmpty()

            // The tag chips are rendered as anchors whose visible text starts with #.
            // Strip the marker and keep each tag as an individual item.
            val hashTags = Regex("""<a\b[^>]*>(.*?)</a>""", htmlFlags)
                .findAll(html)
                .map { it.groupValues[1].htmlText() }
                .filter { it.startsWith("#") }
                .map { it.removePrefix("#").trim() }
                .filter { it.isNotBlank() && it.length < 100 }
                .distinct()
                .toList()

            val genreFragment = Regex("""<(?:div|ul|td)[^>]*class=["'][^"']*(?:main_genre|work_genre|genre)[^"']*["'][^>]*>(.*?)</(?:div|ul|td)>""", htmlFlags)
                .find(html)?.groupValues?.getOrNull(1).orEmpty()
            val tags = hashTags.ifEmpty { links(genreFragment) }

            return DlsiteMetadata(id, title, cover, cv, circle, tags, productUrl(id))
        } finally {
            connection.disconnect()
        }
    }

    private fun String.htmlText(): String = replace(Regex("""<[^>]+>"""), " ")
        .htmlDecode().replace(Regex("""\s+"""), " ").trim()

    private fun String.htmlDecode(): String = replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&#x27;", "'").replace("&nbsp;", " ")
}
