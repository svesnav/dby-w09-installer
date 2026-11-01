package org.matepad.installer

import android.content.Context
import java.io.IOException

/**
 * This app's own updates: its newest GitHub release, installed over it as
 * root. The APK streams from GitHub straight into `pm install` (nothing is
 * stored on the tablet); every build is signed with the same key, so it
 * installs over this one without Android's confirmation prompt.
 */
object AppUpdate {
    const val REPO = "svesnav/dby-w09-installer"

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /**
     * Whether [latest] is newer than [installed], both dotted numbers
     * ("0.1.10" after "0.1.9"); null for a development build ("0.1-dev.42").
     */
    fun isNewer(latest: String, installed: String): Boolean? {
        fun parts(v: String): List<Int>? = v.split('.').map { it.toIntOrNull() ?: return null }
        val a = parts(latest) ?: return null
        val b = parts(installed) ?: return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun latest(): AppRelease? = GitHub(REPO, "").latestApp()

    /** Downloads and installs [release]; Android stops this app once the new one is in place. */
    fun install(release: AppRelease, progress: (Float) -> Unit) {
        val size = release.asset.size
        val result = Root.pipe("pm install -r -S $size") { out ->
            GitHub(REPO, "").open(release.asset, 0).use { input ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress((done.toFloat() / size).coerceAtMost(1f))
                }
                if (done != size) throw IOException("download: $done of $size bytes")
            }
        }
        if (!result.out.contains("Success")) throw IOException("pm install: ${result.message()}")
    }
}
