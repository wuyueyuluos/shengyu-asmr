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
    val releaseDate: String,
    val description: String,
    val pageUrl: String
)

object DlsiteMetadataLookup {
    private val productIdRegex = Regex("(?i)\\bRJ\\d{6,8}\\b")

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
            fun rowValue(label: Regex): String {
                val row = Regex("""<tr[^>]*>\\s*<th[^>]*>.*?</th>\\s*<td[^>]*>(.*?)</td>\\s*</tr>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                for (match in row.findAll(html)) {
                    val fullRow = match.value
                    val header = Regex("""<th[^>]*>(.*?)</th>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(fullRow)?.groupValues?.getOrNull(1)?.htmlText().orEmpty()
                    if (label.containsMatchIn(header)) return match.groupValues[1].htmlText()
                }
                return ""
            }
            fun links(fragment: String): List<String> =
                Regex("""<a[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(fragment).map { it.groupValues[1].htmlText() }
                    .filter { it.isNotBlank() && it.length < 100 }.distinct().toList()

            val title = meta("og:title").ifBlank {
                Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.getOrNull(1)?.htmlText().orEmpty()
            }
            val cover = meta("og:image")
            val cv = links(rowValue(Regex("声優|CV|ボイス|Voice Actor", RegexOption.IGNORE_CASE)))
            val makerSection = Regex("""id=["']work_maker["']""", RegexOption.IGNORE_CASE).find(html)
                ?.let { html.substring(it.range.first, (it.range.first + 12000).coerceAtMost(html.length)) }.orEmpty()
            val circle = Regex("""class=["'][^"']*maker_name[^"']*["'][^>]*>\\s*<a[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(makerSection)?.groupValues?.getOrNull(1)?.htmlText().orEmpty()
                .ifBlank { rowValue(Regex("サークル名|制作サークル|Circle", RegexOption.IGNORE_CASE)) }
            val genreFragment = Regex("""<(?:div|ul|td)[^>]*class=["'][^"']*(?:main_genre|work_genre|genre)[^"']*["'][^>]*>(.*?)</(?:div|ul|td)>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.getOrNull(1).orEmpty()
            val tags = links(genreFragment).ifEmpty {
                meta("keywords", "name").split(',', '、', '|').map { it.trim() }.filter { it.isNotBlank() }
            }.distinct()
            val date = rowValue(Regex("販売日|販売開始日|発売日|発售日|Release Date", RegexOption.IGNORE_CASE))
            val description = meta("og:description").ifBlank { meta("description", "name") }
            return DlsiteMetadata(id, title, cover, cv, circle, tags, date, description, productUrl(id))
        } finally {
            connection.disconnect()
        }
    }

    private fun String.htmlText(): String = replace(Regex("""<[^>]+>"""), " ")
        .htmlDecode().replace(Regex("""\\s+"""), " ").trim()

    private fun String.htmlDecode(): String = replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&#x27;", "'").replace("&nbsp;", " ")
}
