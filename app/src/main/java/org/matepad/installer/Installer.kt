package org.matepad.installer

import android.content.Context
import org.matepad.installer.core.BootImage
import org.matepad.installer.core.Fdt
import org.matepad.installer.core.Partitions
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** What `matepad.sh probe` reports about this tablet. */
data class Probe(val values: Map<String, String>) {
    operator fun get(key: String) = values[key].orEmpty()
    val rooted get() = this["uid"] == "0"
    val isMatePad get() = listOf("model", "device", "board").any { this[it].uppercase().startsWith("DBY") }
    val sd get() = this["sd"].ifEmpty { null }
    val sdBytes get() = (this["sd_sectors"].toLongOrNull() ?: 0L) * 512
    val staged get() = this["staged"] == "1"
}

enum class Step(val label: String) {
    CHECK("Check the tablet"),
    CARD("Erase and partition the card"),
    WRITE("Write Kubuntu"),
    FILES("Copy this tablet's own files"),
    SETUP("Set up your account and language"),
    BOOT("Prepare the boot images"),
}

/** Where the image comes from: a GitHub release or a local file. */
sealed class ImageSource {
    abstract val label: String

    class FromRelease(val github: GitHub, val release: Release) : ImageSource() {
        override val label get() = release.title
    }

    class FromFile(val name: String, val size: Long, val open: () -> InputStream) : ImageSource() {
        override val label get() = name
    }
}

class Installer(private val context: Context, private val report: (Report) -> Unit) {
    data class Report(val step: Step, val fraction: Float? = null, val detail: String = "", val log: String? = null)

    private val script get() = File(context.filesDir, "matepad.sh").absolutePath
    private val stage = "/cache/linux70"

    companion object {
        fun installAssets(context: Context) {
            for (name in listOf("matepad.sh", "boot-linux.sh")) {
                context.assets.open(name).use { input ->
                    File(context.filesDir, name).outputStream().use { input.copyTo(it) }
                }
            }
        }

        fun probe(context: Context): Probe {
            installAssets(context)
            val r = Root.run("sh ${File(context.filesDir, "matepad.sh").absolutePath} probe")
            val map = r.out.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i) to line.substring(i + 1).trim() else null
            }.toMap()
            if (map["uid"] != "0") throw IOException("no root access (allow this app in APatch, Magisk or KernelSU)")
            return Probe(map)
        }

        /** Writes the staged images to boot/ramdisk/dtbo and reboots (no return on success). */
        fun bootLinux(): Root.Result = Root.run("sh /cache/linux70/boot-linux.sh")
    }

    private fun log(step: Step, text: String) = report(Report(step, log = text))

    fun install(source: ImageSource, wlanMac: String, btMac: String, setup: SystemSetup) {
        // 1. the tablet, the card and the stock boot partitions
        report(Report(Step.CHECK, detail = "Reading this tablet's state"))
        val probe = probe(context)
        if (!probe.isMatePad) throw IOException("this is not a MatePad 11 (DBY-W09): ${probe["model"]}")
        val sd = probe.sd ?: throw IOException("no microSD card")
        if (probe.sdBytes < 16L shl 30) throw IOException("the card is smaller than 16 GB")
        val bootPart = probe["part_boot"].ifEmpty { throw IOException("no boot partition") }
        val ramdiskPart = probe["part_ramdisk"].ifEmpty { throw IOException("no ramdisk partition") }
        val bootHead = Root.read("head -c 4096 $bootPart")
        val stockBoot = Root.read("head -c ${BootImage.imageSize(bootHead)} $bootPart")
        val stock = BootImage.parse(stockBoot)
        if (!stock.cmdline.contains("androidboot.hardware=")) {
            throw IOException("the boot partition does not hold HarmonyOS's kernel")
        }
        val stockRamdisk = Root.read("cat $ramdiskPart")
        log(Step.CHECK, "${probe["model"]}, card ${probe["sd_name"]} ${probe.sdBytes / 1_000_000_000} GB at $sd")
        log(Step.CHECK, "stock boot image: header v${stock.headerVersion}, ${stockBoot.size / 1024} KiB")

        // 2. the card: one Linux partition over all of it
        report(Report(Step.CARD, detail = "Erasing $sd"))
        Root.check("sh $script release-card $sd")
        Root.check("sh $script wipe-card $sd") { log(Step.CARD, it) }
        val sectors = probe.sdBytes / 512
        Root.pipe("dd of=$sd bs=512 count=1 conv=fsync 2>/dev/null") { it.write(Partitions.mbr(sectors)) }
        val p1 = Root.check("sh $script reread $sd") { log(Step.CARD, it) }
            .out.lines().first { it.startsWith("p1=") }.substringAfter("p1=").substringBefore(' ')
        val partBytes = Partitions.partitionBytes(sectors)

        // 3. the image, decompressed on the way to the partition
        writeImage(source, p1, partBytes)

        // 4. this tablet's files (vendor files, firmware, MACs, stock images)
        report(Report(Step.FILES, detail = "Copying vendor files and firmware"))
        Root.check("sh $script populate $p1 ${wlanMac.ifBlank { "-" }} ${btMac.ifBlank { "-" }}") { line ->
            if (line.startsWith("step=")) report(Report(Step.FILES, detail = line.substringAfter("step=")))
            log(Step.FILES, line)
        }

        // 5. what Kubuntu's first-boot setup would ask (the password only over stdin)
        report(Report(Step.SETUP, detail = "User ${setup.userName}, ${setup.language.label}, ${setup.timezone}"))
        val configured = Root.pipe("sh $script configure $p1") { it.write(setup.toInput().toByteArray()) }
        configured.out.lines().filter { it.isNotBlank() }.forEach { log(Step.SETUP, it) }

        // 6. boot images from the stock ones and the image's boot bundle
        report(Report(Step.BOOT, detail = "Building boot, ramdisk and dtbo"))
        val b = "$stage/bundle"
        Root.check("cd $b && sha256sum -c SHA256SUMS")
        val dtb = Root.read("cat $b/sm8250-huawei-dby-w09.dtb")
        if (btMac.isNotBlank()) Fdt.setBdAddress(dtb, btMac)
        stock.kernel = Root.read("cat $b/Image.gz")
        stock.dtb = dtb
        stock.cmdline = String(Root.read("cat $b/cmdline.txt")).trim()
        val images = linkedMapOf(
            "boot" to stock.build(),
            "ramdisk" to Partitions.overlayRamdisk(stockRamdisk, Root.read("cat $b/ramdisk-payload.img")),
            "dtbo" to Root.read("cat $b/dtbo.img"),
        )
        val kernel = String(Root.read("cat $b/release.txt")).trim()
        for ((name, data) in images) {
            Root.write("$stage/$name.img", data)
            log(Step.BOOT, "$name.img: ${data.size / 1024} KiB")
        }
        Root.write("$stage/SHA256SUMS", images.entries.joinToString("") { (n, d) -> "${sha256(d)}  $n.img\n" }.toByteArray())
        Root.check("cp ${File(context.filesDir, "boot-linux.sh").absolutePath} $stage/boot-linux.sh && rm -rf $b && sync")
        Root.check("sh $script stage-check") { log(Step.BOOT, it) }
        log(Step.BOOT, "Linux $kernel is ready: Boot Linux writes it and restarts")
    }

    private fun writeImage(source: ImageSource, p1: String, partBytes: Long) {
        val (raw, compressedTotal, expectSha, imageSize) = when (source) {
            is ImageSource.FromRelease -> {
                val m = source.release.manifest ?: throw IOException("the release has no image manifest")
                val parts = source.release.parts ?: throw IOException("the release misses image parts")
                Quad(ResumingStream(source.github, parts), m.compressedSize, m.imageSha256, m.imageSize)
            }
            is ImageSource.FromFile -> Quad(source.open(), source.size, null, null)
        }
        if (imageSize != null && imageSize > partBytes) throw IOException("the image does not fit this card")
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = CountingStream(DigestInputStream(BufferedInputStream(raw, 1 shl 20), digest))
        val image = BufferedInputStream(decompressor(counted), 1 shl 20)
        val started = System.nanoTime()
        var written = 0L
        report(Report(Step.WRITE, 0f, "Starting"))
        Root.pipe("dd of=$p1 bs=4194304 2>/dev/null") { out ->
            val buf = ByteArray(4 shl 20)
            var lastReport = 0L
            while (true) {
                val n = image.read(buf)
                if (n < 0) break
                if (written + n > partBytes) throw IOException("the image is larger than the card")
                out.write(buf, 0, n)
                written += n
                val now = System.nanoTime()
                if (now - lastReport > 500_000_000L) {
                    lastReport = now
                    val fraction = when {
                        imageSize != null && imageSize > 0 -> written.toFloat() / imageSize
                        compressedTotal > 0 -> counted.count.toFloat() / compressedTotal
                        else -> null
                    }
                    val mbps = written / 1e6 / ((now - started) / 1e9)
                    report(Report(Step.WRITE, fraction?.coerceIn(0f, 1f),
                        "%,d MB written, %.0f MB/s".format(written / 1_000_000, mbps)))
                }
            }
            image.close()
        }
        if (expectSha != null) {
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != expectSha) throw IOException("download checksum mismatch: the card holds a damaged image")
        }
        if (imageSize != null && written != imageSize) throw IOException("image size $written, expected $imageSize")
        Root.check("sync")
        log(Step.WRITE, "%,d MB written in %d s".format(written / 1_000_000, (System.nanoTime() - started) / 1_000_000_000))
    }

    /** xz (releases), gzip, or a raw ext4 image, told apart by their first bytes. */
    private fun decompressor(input: InputStream): InputStream {
        val s = BufferedInputStream(input, 1 shl 16)
        s.mark(8)
        val head = ByteArray(6)
        var n = 0
        while (n < 6) {
            val r = s.read(head, n, 6 - n)
            if (r < 0) break
            n += r
        }
        s.reset()
        return when {
            n >= 6 && head.contentEquals(byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(),
                'X'.code.toByte(), 'Z'.code.toByte(), 0)) -> XZInputStream(s)
            n >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> GZIPInputStream(s, 1 shl 16)
            else -> s
        }
    }

    private data class Quad(val a: InputStream, val b: Long, val c: String?, val d: Long?)

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }
}

fun sha256(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
