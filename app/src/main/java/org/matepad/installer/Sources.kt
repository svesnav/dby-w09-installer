package org.matepad.installer

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One release asset of the image repository. */
data class Asset(val name: String, val size: Long, val apiUrl: String)

/** `<name>.json` of a release (kubuntu-dby-w09 image/release-manifest.py). */
data class ImageManifest(
    val name: String,
    val imageSha256: String,
    val compressedSize: Long,
    val imageSize: Long?,
    val parts: List<String>,
    val kernel: String,
)

data class Release(
    val tag: String,
    val title: String,
    val published: String,
    val prerelease: Boolean,
    val assets: Map<String, Asset>,
    val manifest: ImageManifest?,
) {
    val parts: List<Asset>? get() = manifest?.parts?.map { assets[it] ?: return null }
}

/** GitHub releases of the image repository; a token only for private repositories. */
class GitHub(private val repo: String, private val token: String) {
    private fun connect(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        return c
    }

    private fun getText(url: String, accept: String = "application/vnd.github+json"): String {
        val c = connect(url, accept)
        try {
            if (c.responseCode in 300..399) return getPlain(c.getHeaderField("Location"))
            if (c.responseCode != 200) throw IOException(httpError(c))
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun getPlain(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} from ${URL(url).host}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun httpError(c: HttpURLConnection): String = when (c.responseCode) {
        401 -> "GitHub rejected the token"
        403 -> "GitHub refused the request (rate limit or token without access)"
        404 -> if (token.isBlank()) "$repo not found (a private repository needs a token)" else "$repo not found"
        else -> "GitHub: HTTP ${c.responseCode}"
    }

    fun releases(): List<Release> {
        val list = JSONArray(getText("https://api.github.com/repos/$repo/releases?per_page=20"))
        return (0 until list.length()).map { i ->
            val r = list.getJSONObject(i)
            val assets = r.getJSONArray("assets").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.associate {
                    it.getString("name") to Asset(it.getString("name"), it.getLong("size"), it.getString("url"))
                }
            }
            val manifestAsset = assets.values.firstOrNull { it.name.endsWith(".json") }
            val manifest = manifestAsset?.let {
                runCatching { parseManifest(JSONObject(getText(it.apiUrl, "application/octet-stream"))) }.getOrNull()
            }
            Release(
                tag = r.getString("tag_name"),
                title = r.optString("name").ifBlank { r.getString("tag_name") },
                published = r.optString("published_at").take(10),
                prerelease = r.optBoolean("prerelease"),
                assets = assets,
                manifest = manifest,
            )
        }.filter { it.manifest != null }
    }

    private fun parseManifest(m: JSONObject) = ImageManifest(
        name = m.getString("name"),
        imageSha256 = m.getString("image_sha256"),
        compressedSize = m.getLong("image_compressed_size"),
        imageSize = if (m.isNull("image_size")) null else m.getLong("image_size"),
        parts = m.getJSONArray("parts").let { p -> (0 until p.length()).map { p.getString(it) } },
        kernel = m.optString("kernel"),
    )

    /** The asset from [offset] on; GitHub redirects to a signed URL that gets no token. */
    fun open(asset: Asset, offset: Long): InputStream {
        val c = connect(asset.apiUrl, "application/octet-stream")
        val target = if (c.responseCode in 300..399) {
            val location = c.getHeaderField("Location")
            c.disconnect()
            URL(location).openConnection() as HttpURLConnection
        } else if (c.responseCode == 200 && offset == 0L) {
            return c.inputStream
        } else {
            val msg = httpError(c)
            c.disconnect()
            throw IOException(msg)
        }
        target.connectTimeout = 20_000
        target.readTimeout = 60_000
        if (offset > 0) target.setRequestProperty("Range", "bytes=$offset-")
        val code = target.responseCode
        if (code != 200 && code != 206) throw IOException("download: HTTP $code")
        if (offset > 0 && code != 206) throw IOException("download: the server cannot resume")
        return target.inputStream
    }
}

/**
 * The release parts back to back as one stream, reopening a dropped
 * connection where it stopped (a few times per part).
 */
class ResumingStream(private val github: GitHub, private val parts: List<Asset>) : InputStream() {
    private var index = 0
    private var offset = 0L
    private var current: InputStream? = null
    private var retries = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (index < parts.size) {
            val part = parts[index]
            if (offset >= part.size) {
                current?.close()
                current = null
                index++
                offset = 0
                continue
            }
            try {
                val s = current ?: github.open(part, offset).also { current = it }
                val n = s.read(b, off, minOf(len.toLong(), part.size - offset).toInt())
                if (n < 0) throw IOException("connection closed early")
                offset += n
                retries = 0
                return n
            } catch (e: IOException) {
                current?.runCatching { close() }
                current = null
                if (++retries > 8) throw e
                Thread.sleep(2000L * retries)
            }
        }
        return -1
    }

    override fun close() {
        current?.close()
    }
}
