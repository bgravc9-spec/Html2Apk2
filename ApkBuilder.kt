package com.htmltoapk.studio

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Calisma prensibi: uygulama kendi APK'sini sablon olarak kullanir.
 * 1) Kendi APK'sini acar, 2) assets/www altina kullanicinin sitesini koyar,
 * 3) manifest'te paket adini, resources.arsc'ta uygulama adini degistirir,
 * 4) zipalign benzeri hizalama yapar, 5) apksig ile imzalar.
 */
object ApkBuilder {
    private const val APP_NAME_IN_TEMPLATE = "HTML to APK Studio"
    private const val STORE_PASS = "html2apk"

    fun build(ctx: Context, input: ByteArray, appName: String, pkg: String): File {
        require(pkg != ctx.packageName) { "Paket adi bu uygulamanin paket adiyla ayni olamaz" }
        val site = readSite(input)

        val src = ZipFile(ctx.applicationInfo.sourceDir)
        val unsigned = File(ctx.cacheDir, "unsigned.apk")
        val signed = File(ctx.cacheDir, "signed.apk")
        unsigned.delete(); signed.delete()

        fun bytes(name: String) = src.getInputStream(src.getEntry(name)).use { it.readBytes() }
        val manifest = ResPatcher.patch(bytes("AndroidManifest.xml"), mapOf(ctx.packageName to pkg))
        val arsc = ResPatcher.patch(bytes("resources.arsc"), mapOf(APP_NAME_IN_TEMPLATE to appName))

        AlignedZip(unsigned).use { w ->
            for (e in src.entries()) {
                val n = e.name
                if (e.isDirectory || n.startsWith("META-INF/") || n == "AndroidManifest.xml" ||
                    n == "resources.arsc" || n.startsWith("assets/www/")
                ) continue
                w.add(n, bytes(n), e.method == ZipEntry.STORED)
            }
            w.add("AndroidManifest.xml", manifest, false)
            w.add("resources.arsc", arsc, true)      // Android 11+ icin sikistirilmamis + hizali olmali
            site.forEach { (n, b) -> w.add("assets/www/$n", b, false) }
        }
        src.close()

        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open("signing.p12").use { ks.load(it, STORE_PASS.toCharArray()) }
        val key = ks.getKey("key", STORE_PASS.toCharArray()) as PrivateKey
        val cert = ks.getCertificate("key") as X509Certificate
        val cfg = ApkSigner.SignerConfig.Builder("CERT", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(29)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .build()
            .sign()
        unsigned.delete()
        return signed
    }

    /** Tek .html dosyasi ya da kokunde index.html olan .zip kabul eder. */
    private fun readSite(data: ByteArray): Map<String, ByteArray> {
        if (data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
            val map = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(data)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = e.name.replace('\\', '/')
                    if (e.isDirectory || n.startsWith("/") || n.split('/').contains("..")) continue
                    map[n] = z.readBytes()
                }
            }
            require(map.containsKey("index.html")) { "ZIP'in kokunde index.html bulunmali" }
            return map
        }
        return mapOf("index.html" to data)
    }

    private class Counter(o: OutputStream) : FilterOutputStream(o) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private class AlignedZip(f: File) : Closeable {
        private val counter = Counter(FileOutputStream(f))
        private val zos = ZipOutputStream(counter)

        fun add(name: String, data: ByteArray, stored: Boolean) {
            val e = ZipEntry(name)
            if (stored) {
                e.method = ZipEntry.STORED
                e.size = data.size.toLong(); e.compressedSize = e.size
                e.crc = CRC32().also { it.update(data) }.value
                val dataOffset = counter.count + 30 + name.toByteArray(Charsets.UTF_8).size
                val pad = ((4 - dataOffset % 4) % 4).toInt()
                if (pad > 0) e.extra = ByteArray(pad)
            } else e.method = ZipEntry.DEFLATED
            zos.putNextEntry(e); zos.write(data); zos.closeEntry()
        }

        override fun close() = zos.close()
    }
}
