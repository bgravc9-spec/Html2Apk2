package com.htmltoapk.studio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ikili AndroidManifest.xml ve resources.arsc dosyalarindaki string havuzunda
 * birebir eslesen metinleri degistirir (paket adi, uygulama adi).
 */
object ResPatcher {
    fun patch(data: ByteArray, repl: Map<String, String>): ByteArray {
        val le = ByteOrder.LITTLE_ENDIAN
        val bb = ByteBuffer.wrap(data).order(le)
        val p = bb.getShort(2).toInt() and 0xFFFF            // dis baslik boyutu = havuzun konumu
        require(bb.getShort(p).toInt() == 1) { "String havuzu bulunamadi" }
        val headerSize = bb.getShort(p + 2).toInt() and 0xFFFF
        val size = bb.getInt(p + 4)
        val count = bb.getInt(p + 8)
        val styleCount = bb.getInt(p + 12)
        val flags = bb.getInt(p + 16)
        val strStart = bb.getInt(p + 20)
        val styStart = bb.getInt(p + 24)
        val utf8 = (flags and 0x100) != 0

        val strings = ArrayList<String>(count)
        for (i in 0 until count) {
            var pos = p + strStart + bb.getInt(p + headerSize + 4 * i)
            if (utf8) {
                val c = data[pos++].toInt() and 0xFF
                if ((c and 0x80) != 0) pos++
                var l = data[pos++].toInt() and 0xFF
                if ((l and 0x80) != 0) l = ((l and 0x7F) shl 8) or (data[pos++].toInt() and 0xFF)
                strings.add(String(data, pos, l, Charsets.UTF_8))
            } else {
                var l = bb.getShort(pos).toInt() and 0xFFFF
                pos += 2
                if ((l and 0x8000) != 0) {
                    l = ((l and 0x7FFF) shl 16) or (bb.getShort(pos).toInt() and 0xFFFF)
                    pos += 2
                }
                strings.add(String(data, pos, l * 2, Charsets.UTF_16LE))
            }
        }

        val body = ByteArrayOutputStream()
        val offs = IntArray(count)
        strings.forEachIndexed { i, s0 ->
            val s = repl[s0] ?: s0
            offs[i] = body.size()
            if (utf8) {
                val b = s.toByteArray(Charsets.UTF_8)
                len8(body, s.length); len8(body, b.size)
                body.write(b); body.write(0)
            } else {
                if (s.length > 0x7FFF) {
                    short(body, (s.length shr 16) or 0x8000); short(body, s.length and 0xFFFF)
                } else short(body, s.length)
                body.write(s.toByteArray(Charsets.UTF_16LE)); short(body, 0)
            }
        }
        while (body.size() % 4 != 0) body.write(0)
        val newStr = body.toByteArray()
        val styles = if (styleCount > 0) data.copyOfRange(p + styStart, p + size) else ByteArray(0)

        val hdrLen = headerSize + 4 * (count + styleCount)
        val hdr = ByteBuffer.allocate(hdrLen).order(le)
        hdr.put(data, p, headerSize)
        for (o in offs) hdr.putInt(o)
        hdr.put(data, p + headerSize + 4 * count, 4 * styleCount)
        hdr.putInt(4, hdrLen + newStr.size + styles.size)
        hdr.putInt(16, flags and 1.inv())                      // "sorted" bayragini kaldir
        hdr.putInt(20, hdrLen)
        hdr.putInt(24, if (styleCount > 0) hdrLen + newStr.size else 0)

        val out = ByteArrayOutputStream()
        out.write(data, 0, p)
        out.write(hdr.array()); out.write(newStr); out.write(styles)
        out.write(data, p + size, data.size - p - size)
        val r = out.toByteArray()
        ByteBuffer.wrap(r).order(le).putInt(4, r.size)          // toplam dosya boyutu
        return r
    }

    private fun len8(o: ByteArrayOutputStream, n: Int) {
        if (n > 0x7F) { o.write((n shr 8) or 0x80); o.write(n and 0xFF) } else o.write(n)
    }

    private fun short(o: ByteArrayOutputStream, n: Int) {
        o.write(n and 0xFF); o.write((n shr 8) and 0xFF)
    }
}
