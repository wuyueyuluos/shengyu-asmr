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

            // Read metadata from the actual information row. Searching the whole page
            // for a label is unsafe: promotional banners can contain unrelated text.
            fun rowFor(label: String): String {
                val rows = Regex("""<(?:tr|li|div)\\b[^>]*>(.*?)</(?:tr|li|div)>""", htmlFlags)
                    .findAll(html)
                    .map { it.groupValues[1] }
                return rows.firstOrNull { row ->
                    val plain = row.htmlText()
                    plain.startsWith(label) || plain.contains(Regex("""(?:^|\\s)$label(?:\\s|$)"""))
                }.orEmpty()
            }

            fun rowLinks(row: String): List<String> = links(row)
                .filterNot { it.matches(Regex("""(?i).*(?:まで|セール|割引|%OFF).*""")) }

            val title = meta("og:title").ifBlank {
                Regex("""<title[^>]*>(.*?)</title>""", htmlFlags)
                    .find(html)?.groupValues?.getOrNull(1)?.htmlText().orEmpty()
            }
            val cover = meta("og:image")

            // Keep the already-working 声優 extraction behavior.
            val cv = labeledLinks("声優", "サークル|作品形式|販売日|シナリオ")
                .ifEmpty { labeledLinks("CV", "サークル|作品形式|販売日") }

            // Circle is read only from the row whose label is サークル.
            val circleRow = rowFor("サークル")
            val circle = rowLinks(circleRow).firstOrNull().orEmpty()

            // DLsite genre chips may be individual links and may render as "# tag"
            // rather than "#tag". Prefer the ジャンル row; fall back to genre-class
            // containers and finally to hash-prefixed anchors.
            val genreRow = rowFor("ジャンル")
            val genreFragment = Regex(
                """<(?:div|ul|section)[^>]*class=["'][^"']*(?:main_genre|work_genre|genre)[^"']*["'][^>]*>(.*?)</(?:div|ul|section)>""",
                htmlFlags
            ).find(html)?.groupValues?.getOrNull(1).orEmpty()

            fun cleanTag(value: String): String = value
                .replace(Regex("""^#\\s*"""), "")
                .trim()
                .removeSuffix(">").trim()

            val rowTags = rowLinks(genreRow).map(::cleanTag)
            val containerTags = rowLinks(genreFragment).map(::cleanTag)
            val hashTags = Regex("""<a\\b[^>]*>(.*?)</a>""", htmlFlags)
                .findAll(html)
                .map { cleanTag(it.groupValues[1].htmlText()) }
                .filter { it != it.replace(Regex("""^#"""), "") }
                .toList()

            val tags = (rowTags.ifEmpty { containerTags.ifEmpty { hashTags } })
                .filter { it.isNotBlank() && it.length < 100 }
                .distinct()

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
