package vn.trinhdoc.app

import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import java.io.File
import kotlin.math.roundToInt

class DocxResult(val bodyHtml: String, val images: Map<String, ByteArray>)

/**
 * Chuyển file Word (.docx) sang HTML để hiển thị.
 * Giữ: tiêu đề, đậm/nghiêng/gạch chân, màu chữ, cỡ chữ, căn lề, danh sách đánh số,
 * bảng (gộp cột), hình ảnh, khung văn bản.
 */
object DocxConverter {

    private const val ROOT = 0
    private const val PARA = 1
    private const val RUN = 2

    private class Frame(val kind: Int) {
        val sb = StringBuilder()
        /** Số thứ tự đoạn văn trong document.xml (dùng khi lưu bản sửa). */
        var pid = -1
        // đoạn văn
        var style: String? = null
        var jc: String? = null
        var numId: String? = null
        var ilvl = 0
        // đoạn chữ (run)
        var b = false
        var i = false
        var u = false
        var strike = false
        var color: String? = null
        var halfPt = 0
        var vert: String? = null
        var highlight: String? = null
    }

    /** Các phần tử bỏ qua khi đọc; DocxEditor dùng chung để đếm đoạn văn khớp với HTML. */
    internal val SKIP = setOf(
        "mc:Fallback", "w:instrText", "w:delText", "w:del", "w:moveFrom",
        "w:rPrChange", "w:pPrChange", "w:tblPrChange", "w:tcPrChange", "w:trPrChange",
        "w:sectPr", "w:sdtPr", "w:sdtEndPr", "w:fldData", "w:tabs"
    )

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg")

    fun convert(file: File): DocxResult = Zip(file).use { zip ->
        val main = mainPart(zip) ?: "word/document.xml"
        val xml = zip.bytes(main) ?: throw ReaderException("File Word bị hỏng hoặc không có nội dung.")
        val rels = relsOf(zip, main)
        val relMap = rels.filter { !it.external }.associate { it.id to it.target }
        val stylesPart = rels.firstOrNull { it.type.endsWith("/styles") }?.target ?: (dirOf(main) + "styles.xml")
        val numberingPart = rels.firstOrNull { it.type.endsWith("/numbering") }?.target ?: (dirOf(main) + "numbering.xml")
        val headings = parseHeadingStyles(zip.bytes(stylesPart))
        val numbering = Numbering(zip.bytes(numberingPart))
        val images = HashMap<String, ByteArray>()

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(ROOT))
        // vị trí dấu '>' của thẻ <td> đang mở, để chèn colspan/nền ô sau
        val cells = ArrayDeque<Pair<StringBuilder, Int>>()
        var pCount = 0
        var skip = 0
        var inText = false
        var inTcPr = false
        var extentCx = 0L

        fun top() = stack.last()
        fun para() = stack.last().takeIf { it.kind == PARA }
        fun curRun() = stack.last().takeIf { it.kind == RUN }

        fun cellAttr(a: String) {
            val c = cells.lastOrNull() ?: return
            c.first.insert(c.second, a)
            cells.removeLast()
            cells.addLast(Pair(c.first, c.second + a.length))
        }

        fun addImage(relId: String?) {
            val target = relId?.let { relMap[it] } ?: return
            val ext = target.substringAfterLast('.', "").lowercase()
            if (ext !in IMAGE_EXT) {
                top().sb.append("<span class=\"ph\">[Hình ảnh]</span>")
                return
            }
            if (!images.containsKey(target)) {
                val bytes = zip.bytes(target) ?: return
                images[target] = bytes
            }
            val w = if (extentCx > 0) " style=\"width:${(extentCx / 9525.0).roundToInt()}px\"" else ""
            top().sb.append("<img src=\"/img/").append(Uri.encode(target, "/")).append("\"").append(w).append(" alt=\"\">")
            extentCx = 0
        }

        val p = newParser(xml)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    if (skip > 0) {
                        skip++
                    } else if (n in SKIP) {
                        skip = 1
                    } else when (n) {
                        "w:p" -> stack.addLast(Frame(PARA).also { it.pid = pCount++ })
                        "w:r" -> stack.addLast(Frame(RUN))
                        "w:pStyle" -> para()?.style = p.attr("val")
                        "w:jc" -> para()?.jc = p.attr("val")
                        "w:ilvl" -> para()?.ilvl = p.attr("val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                        "w:numId" -> para()?.numId = p.attr("val")
                        "w:b" -> curRun()?.b = p.isOn()
                        "w:i" -> curRun()?.i = p.isOn()
                        "w:u" -> curRun()?.u = p.isOn()
                        "w:strike", "w:dstrike" -> curRun()?.strike = p.isOn()
                        "w:color" -> curRun()?.color = p.attr("val")?.takeIf { it.length == 6 && it != "000000" && !it.equals("auto", true) }
                        "w:sz" -> curRun()?.halfPt = p.attr("val")?.toIntOrNull() ?: 0
                        "w:vertAlign" -> curRun()?.vert = p.attr("val")
                        "w:highlight" -> curRun()?.highlight = p.attr("val")
                        "w:t" -> inText = true
                        "w:tab" -> curRun()?.sb?.append("&emsp;")
                        "w:br", "w:cr" -> curRun()?.sb?.append("<br>")
                        "w:noBreakHyphen" -> curRun()?.sb?.append("-")
                        "w:tbl" -> top().sb.append("<table>")
                        "w:tr" -> top().sb.append("<tr>")
                        "w:tc" -> {
                            val sb = top().sb
                            sb.append("<td>")
                            cells.addLast(Pair(sb, sb.length - 1))
                        }
                        "w:tcPr" -> inTcPr = true
                        "w:gridSpan" -> if (inTcPr) {
                            val span = p.attr("val")?.toIntOrNull() ?: 1
                            if (span > 1) cellAttr(" colspan=\"$span\"")
                        }
                        "w:vMerge" -> if (inTcPr) {
                            val v = p.attr("val")
                            if (v == null || v == "continue") cellAttr(" class=\"vm\"")
                        }
                        "w:shd" -> if (inTcPr) {
                            val fill = p.attr("fill")
                            if (fill != null && fill.length == 6 && !fill.equals("auto", true) && !fill.equals("FFFFFF", true)) {
                                cellAttr(" style=\"background:#$fill\"")
                            }
                        }
                        "wp:extent" -> extentCx = p.attr("cx")?.toLongOrNull() ?: 0
                        "a:blip" -> addImage(p.attr("embed"))
                        "v:imagedata" -> addImage(p.attr("id"))
                    }
                }

                XmlPullParser.TEXT -> if (skip == 0 && inText) top().sb.append(esc(p.text))

                XmlPullParser.END_TAG -> {
                    if (skip > 0) {
                        skip--
                    } else when (p.name) {
                        "w:t" -> inText = false
                        "w:r" -> if (top().kind == RUN && stack.size > 1) {
                            val r = stack.removeLast()
                            top().sb.append(wrapRun(r))
                        }
                        "w:p" -> if (top().kind == PARA && stack.size > 1) {
                            val para = stack.removeLast()
                            top().sb.append(wrapPara(para, top().kind == RUN, headings, numbering))
                        }
                        "w:tbl" -> top().sb.append("</table>")
                        "w:tr" -> top().sb.append("</tr>")
                        "w:tc" -> {
                            top().sb.append("</td>")
                            if (cells.isNotEmpty()) cells.removeLast()
                        }
                        "w:tcPr" -> inTcPr = false
                    }
                }
            }
            ev = p.next()
        }

        // Phòng khi file thiếu thẻ đóng: gom hết vào gốc.
        while (stack.size > 1) {
            val f = stack.removeLast()
            top().sb.append(f.sb)
        }
        val body = top().sb.toString()
        DocxResult(if (body.isBlank()) "<p class=\"ph\">Tài liệu trống.</p>" else body, images)
    }

    private fun wrapRun(r: Frame): String {
        if (r.sb.isEmpty()) return ""
        var s = r.sb.toString()
        if (r.b) s = "<b>$s</b>"
        if (r.i) s = "<i>$s</i>"
        if (r.u) s = "<u>$s</u>"
        if (r.strike) s = "<s>$s</s>"
        when (r.vert) {
            "superscript" -> s = "<sup>$s</sup>"
            "subscript" -> s = "<sub>$s</sub>"
        }
        val css = StringBuilder()
        r.color?.let { css.append("color:#").append(it).append(';') }
        if (r.halfPt > 0 && r.halfPt != 22 && r.halfPt != 24 && r.halfPt != 26) {
            val em = (r.halfPt / 2.0 / 13.0).coerceIn(0.7, 2.6)
            css.append("font-size:").append(String.format(java.util.Locale.US, "%.2f", em)).append("em;")
        }
        r.highlight?.let { h -> highlightColor(h)?.let { css.append("background:").append(it).append(';') } }
        return if (css.isEmpty()) s else "<span style=\"$css\">$s</span>"
    }

    private fun highlightColor(h: String): String? = when (h) {
        "yellow" -> "#ffff00"
        "green" -> "#00ff00"
        "cyan" -> "#00ffff"
        "magenta" -> "#ff00ff"
        "blue" -> "#0000ff"
        "red" -> "#ff0000"
        "darkBlue" -> "#000080"
        "darkCyan" -> "#008080"
        "darkGreen" -> "#008000"
        "darkMagenta" -> "#800080"
        "darkRed" -> "#800000"
        "darkYellow" -> "#808000"
        "darkGray" -> "#808080"
        "lightGray" -> "#c0c0c0"
        "black" -> "#000000"
        else -> null
    }

    private fun wrapPara(f: Frame, nested: Boolean, headings: Map<String, Int>, numbering: Numbering): String {
        val content = f.sb.toString()
        val level = f.style?.let { headings[it] } ?: 0
        val css = StringBuilder()
        when (f.jc) {
            "center" -> css.append("text-align:center;")
            "right", "end" -> css.append("text-align:right;")
            "both", "distribute" -> css.append("text-align:justify;")
        }
        var prefix = ""
        val numId = f.numId
        if (numId != null && numId != "0") {
            val label = numbering.label(numId, f.ilvl)
            if (label != null) {
                if (label.isNotEmpty()) prefix = "<span class=\"num\">${esc(label)}</span>"
                css.append("margin-left:").append(String.format(java.util.Locale.US, "%.1f", 1.3 * (f.ilvl + 1))).append("em;")
            }
        }
        val tag = when {
            nested -> "span"
            level in 1..6 -> "h$level"
            else -> "p"
        }
        val cls = StringBuilder()
        if (nested) cls.append("blk")
        if (content.isBlank() && prefix.isEmpty()) cls.append(if (cls.isEmpty()) "e" else " e")
        val clsAttr = if (cls.isEmpty()) "" else " class=\"$cls\""
        val styleAttr = if (css.isEmpty()) "" else " style=\"$css\""
        return "<$tag data-p=\"${f.pid}\"$clsAttr$styleAttr>$prefix$content</$tag>"
    }

    /** styleId -> cấp tiêu đề (1..6). */
    private fun parseHeadingStyles(bytes: ByteArray?): Map<String, Int> {
        if (bytes == null) return emptyMap()
        val out = HashMap<String, Int>()
        val p = newParser(bytes)
        var id: String? = null
        var name: String? = null
        var outline: Int? = null
        var ev = p.eventType
        val headingRe = Regex("heading\\s*(\\d)")
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:style" -> { id = p.attr("styleId"); name = null; outline = null }
                    "w:name" -> if (id != null) name = p.attr("val")?.lowercase()
                    "w:outlineLvl" -> if (id != null) outline = p.attr("val")?.toIntOrNull()
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "w:style") {
                val sid = id
                if (sid != null) {
                    val nm = name ?: ""
                    val lvl = when {
                        nm == "title" -> 1
                        nm == "subtitle" -> 2
                        headingRe.find(nm) != null -> headingRe.find(nm)!!.groupValues[1].toInt()
                        outline != null && outline!! < 6 -> outline!! + 1
                        else -> 0
                    }
                    if (lvl in 1..6) out[sid] = lvl
                }
                id = null
            }
            ev = p.next()
        }
        return out
    }
}

/** Đánh số danh sách theo numbering.xml. */
internal class Numbering(bytes: ByteArray?) {
    private class Lvl(var fmt: String = "decimal", var text: String = "%1.", var start: Int = 1)

    private val abstracts = HashMap<String, HashMap<Int, Lvl>>()
    private val nums = HashMap<String, String>()
    private val counters = HashMap<String, IntArray>()

    init {
        if (bytes != null) parse(bytes)
    }

    private fun parse(bytes: ByteArray) {
        val p = newParser(bytes)
        var curAbs: HashMap<Int, Lvl>? = null
        var curLvl: Lvl? = null
        var curIlvl = 0
        var curNum: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:abstractNum" -> {
                        val m = HashMap<Int, Lvl>()
                        abstracts[p.attr("abstractNumId") ?: ""] = m
                        curAbs = m
                    }
                    "w:lvl" -> if (curAbs != null && curNum == null) {
                        curLvl = Lvl()
                        curIlvl = p.attr("ilvl")?.toIntOrNull() ?: 0
                    }
                    "w:numFmt" -> curLvl?.fmt = p.attr("val") ?: "decimal"
                    "w:lvlText" -> curLvl?.text = p.attr("val") ?: ""
                    "w:start" -> curLvl?.start = p.attr("val")?.toIntOrNull() ?: 1
                    "w:num" -> curNum = p.attr("numId")
                    "w:abstractNumId" -> {
                        val n = curNum
                        val v = p.attr("val")
                        if (n != null && v != null) nums[n] = v
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "w:lvl" -> {
                        val l = curLvl
                        if (l != null) curAbs?.put(curIlvl, l)
                        curLvl = null
                    }
                    "w:abstractNum" -> curAbs = null
                    "w:num" -> curNum = null
                }
            }
            ev = p.next()
        }
    }

    /** Nhãn cho đoạn danh sách; null nếu không có định nghĩa, "" nếu không hiện số. */
    fun label(numId: String, ilvl: Int): String? {
        val lvls = nums[numId]?.let { abstracts[it] } ?: return null
        val lvl = lvls[ilvl] ?: return null
        if (lvl.fmt == "none") return ""
        if (lvl.fmt == "bullet") return arrayOf("•", "◦", "▪")[ilvl % 3]
        val c = counters.getOrPut(numId) { IntArray(9) { -1 } }
        c[ilvl] = if (c[ilvl] < 0) lvl.start else c[ilvl] + 1
        for (k in ilvl + 1 until 9) c[k] = -1
        return Regex("%([1-9])").replace(lvl.text) { m ->
            val k = m.groupValues[1].toInt() - 1
            val l = lvls[k] ?: Lvl()
            val v = if (c[k] < 0) l.start else c[k]
            fmtNum(v, l.fmt)
        }
    }

    private fun fmtNum(v: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> letters(v).lowercase()
        "upperLetter" -> letters(v)
        "lowerRoman" -> roman(v).lowercase()
        "upperRoman" -> roman(v)
        "decimalZero" -> if (v < 10) "0$v" else v.toString()
        "bullet" -> "•"
        else -> v.toString()
    }

    private fun letters(v: Int): String {
        if (v <= 0) return v.toString()
        val ch = 'A' + ((v - 1) % 26)
        return ch.toString().repeat((v - 1) / 26 + 1)
    }

    private fun roman(v: Int): String {
        if (v <= 0 || v >= 4000) return v.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = v
        val sb = StringBuilder()
        for (i in vals.indices) while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i] }
        return sb.toString()
    }
}
