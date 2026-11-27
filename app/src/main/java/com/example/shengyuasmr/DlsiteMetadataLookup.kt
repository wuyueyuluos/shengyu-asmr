package com.example.shengyuasmr

import java.net.HttpURLConnection
import java.net.URL

data class DlsiteMetadata(val productId: String, val title: String, val coverUrl: String, val pageUrl: String)

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
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; mobile) ShengyuASMR/metadata-test")
        connection.setRequestProperty("Accept", "text/html")
        try {
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            fun meta(property: String): String {
                val patterns = listOf(
                    Regex("""<meta[^>]+property=["']$property["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
                    Regex("""<meta[^>]+content=["']([^"']*)["'][^>]+property=["']$property["']""", RegexOption.IGNORE_CASE)
                )
                return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }?.htmlDecode()?.trim().orEmpty()
            }
            val title = meta("og:title").ifBlank {
                Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.getOrNull(1)?.htmlDecode()?.trim().orEmpty()
            }
            val cover = meta("og:image")
            return DlsiteMetadata(id, title, cover, productUrl(id))
        } finally {
            connection.disconnect()
        }
    }

    private fun String.htmlDecode(): String = replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
