package vn.trinhdoc.app

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Lỗi có thông báo tiếng Việt để hiện cho người dùng. */
class ReaderException(message: String) : Exception(message)

internal fun newParser(bytes: ByteArray): XmlPullParser {
    val p = Xml.newPullParser()
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    p.setInput(ByteArrayInputStream(bytes), null)
    return p
}

/** Lấy thuộc tính theo tên, chấp nhận cả tên có tiền tố (vd "w:val" khi hỏi "val"). */
internal fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) {
        val n = getAttributeName(i)
        if (n == name || n.endsWith(":$name")) return getAttributeValue(i)
    }
    return null
}

/** Tên phần tử bỏ tiền tố namespace: "x:row" -> "row". */
internal fun lname(name: String?): String = name?.substringAfter(':') ?: ""

/** Thuộc tính bật/tắt kiểu OOXML: không có val = bật. */
internal fun XmlPullParser.isOn(): Boolean {
    val v = attr("val") ?: return true
    return !(v == "0" || v.equals("false", true) || v.equals("off", true) || v.equals("none", true))
}

internal fun esc(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (ch in s) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

internal class Zip(file: File) : Closeable {
    private val zf = ZipFile(file)

    fun bytes(name: String): ByteArray? {
        val e = zf.getEntry(name) ?: return null
        if (e.size > 80L * 1024 * 1024) return null
        return zf.getInputStream(e).use { it.readBytes() }
    }

    override fun close() = zf.close()
}

internal data class Rel(val id: String, val type: String, val target: String, val external: Boolean)

internal fun dirOf(path: String): String {
    val d = path.substringBeforeLast('/', "")
    return if (d.isEmpty()) "" else "$d/"
}

internal fun resolvePart(baseDir: String, target: String): String {
    val t = Uri.decode(target)
    if (t.startsWith("/")) return t.drop(1)
    val out = ArrayList<String>()
    for (seg in (baseDir + t).split('/')) {
        when (seg) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(seg)
        }
    }
    return out.joinToString("/")
}

private fun parseRelsFile(zip: Zip, relsPath: String, baseDir: String): List<Rel> {
    val bytes = zip.bytes(relsPath) ?: return emptyList()
    val result = ArrayList<Rel>()
    val p = newParser(bytes)
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG && lname(p.name) == "Relationship") {
            val id = p.attr("Id")
            val target = p.attr("Target")
            if (id != null && target != null) {
                val external = p.attr("TargetMode") == "External"
                result.add(Rel(id, p.attr("Type") ?: "", if (external) target else resolvePart(baseDir, target), external))
            }
        }
        ev = p.next()
    }
    return result
}

/** Quan hệ (relationships) của một phần trong gói OOXML. */
internal fun relsOf(zip: Zip, partPath: String): List<Rel> {
    val dir = dirOf(partPath)
    return parseRelsFile(zip, dir + "_rels/" + partPath.substringAfterLast('/') + ".rels", dir)
}

/** Phần chính của tài liệu (thường là word/document.xml hoặc xl/workbook.xml). */
internal fun mainPart(zip: Zip): String? =
    parseRelsFile(zip, "_rels/.rels", "")
        .firstOrNull { !it.external && it.type.endsWith("/officeDocument") }
        ?.target
