package vn.trinhdoc.app

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Chuyển file Excel (.xlsx) sang bảng HTML: nhiều trang tính (tab), ô gộp,
 * định dạng số/ngày, in đậm, màu nền, căn lề, ẩn hàng/cột.
 */
object XlsxConverter {

    private const val MAX_ROWS = 5000
    private const val MAX_COLS = 150

    private class Xf(val numFmtId: Int, val bold: Boolean, val fill: String?, val align: String?, val wrap: Boolean)
    private class Styles(val numFmts: Map<Int, String>, val xfs: List<Xf>)
    private class Cell(val text: String, val xf: Int, val numeric: Boolean)
    private class SheetRef(val name: String, val target: String?)

    private class SheetData {
        val rows = TreeMap<Int, HashMap<Int, Cell>>()
        var maxRow = 0
        var maxCol = 0
        val colWidths = HashMap<Int, Double>()
        val hiddenCols = HashSet<Int>()
        val hiddenRows = HashSet<Int>()
        val merges = ArrayList<IntArray>() // r1, c1, r2, c2
        var truncated = false
    }

    private val BUILTIN = mapOf(
        1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00",
        37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;(#,##0)",
        39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;(#,##0.00)",
        48 to "##0.0E+0", 49 to "@"
    )

    fun convert(file: File): String = Zip(file).use { zip ->
        val main = mainPart(zip) ?: "xl/workbook.xml"
        val wb = zip.bytes(main) ?: throw ReaderException("File Excel bị hỏng hoặc không có nội dung.")
        val rels = relsOf(zip, main)
        val relById = rels.associateBy { it.id }
        val shared = rels.firstOrNull { it.type.endsWith("/sharedStrings") }
            ?.let { zip.bytes(it.target) }?.let { parseShared(it) } ?: emptyList()
        val styles = parseStyles(rels.firstOrNull { it.type.endsWith("/styles") }?.let { zip.bytes(it.target) })

        // Danh sách trang tính
        val sheets = ArrayList<SheetRef>()
        var date1904 = false
        run {
            val p = newParser(wb)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (lname(p.name)) {
                        "workbookPr" -> date1904 = p.attr("date1904").let { it == "1" || it == "true" }
                        "sheet" -> {
                            val state = p.attr("state")
                            if (state != "hidden" && state != "veryHidden") {
                                val rid = p.attr("id")
                                sheets.add(SheetRef(p.attr("name") ?: "Sheet", rid?.let { relById[it]?.target }))
                            }
                        }
                    }
                }
                ev = p.next()
            }
        }
        if (sheets.isEmpty()) throw ReaderException("File Excel không có trang tính nào để hiển thị.")

        val fmt = Formatter(styles, date1904)
        val sb = StringBuilder()
        sheets.forEachIndexed { i, s ->
            sb.append("<div class=\"sheet\" id=\"s").append(i).append('"')
            if (i > 0) sb.append(" style=\"display:none\"")
            sb.append('>')
            val bytes = s.target?.let { zip.bytes(it) }
            if (bytes == null) {
                sb.append("<p class=\"empty\">Trang tính này là biểu đồ hoặc không đọc được.</p>")
            } else {
                val data = parseSheet(bytes, shared, fmt)
                renderSheet(sb, data, styles)
            }
            sb.append("</div>")
        }
        if (sheets.size > 1) {
            sb.append("<div class=\"tabs\">")
            sheets.forEachIndexed { i, s ->
                sb.append("<button class=\"tab").append(if (i == 0) " on" else "")
                    .append("\" onclick=\"showSheet(").append(i).append(")\">")
                    .append(esc(s.name)).append("</button>")
            }
            sb.append("</div>")
        }
        sb.toString()
    }

    private val ESCAPED_CHAR = Regex("_x([0-9A-Fa-f]{4})_")

    private fun unescapeX(s: String): String =
        if (!s.contains("_x")) s else ESCAPED_CHAR.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }

    private fun parseShared(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = newParser(bytes)
        var sb: StringBuilder? = null
        var inT = false
        var inPh = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (lname(p.name)) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> inPh++
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT && inPh == 0) sb?.append(p.text)
                XmlPullParser.END_TAG -> when (lname(p.name)) {
                    "t" -> inT = false
                    "rPh" -> inPh--
                    "si" -> { out.add(unescapeX(sb?.toString() ?: "")); sb = null }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseStyles(bytes: ByteArray?): Styles {
        val numFmts = HashMap<Int, String>()
        val xfs = ArrayList<Xf>()
        if (bytes == null) return Styles(numFmts, xfs)
        val fonts = ArrayList<Boolean>()
        val fills = ArrayList<String?>()
        var section = ""
        var fontBold = false
        var fillSolid = false
        var fillColor: String? = null
        var xNumFmt = 0
        var xFont = 0
        var xFill = 0
        var xAlign: String? = null
        var xWrap = false
        val p = newParser(bytes)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (val n = lname(p.name)) {
                    "numFmts", "fonts", "fills", "cellXfs", "cellStyleXfs", "dxfs", "borders", "cellStyles" -> section = n
                    "numFmt" -> if (section == "numFmts") {
                        val id = p.attr("numFmtId")?.toIntOrNull()
                        val code = p.attr("formatCode")
                        if (id != null && code != null) numFmts[id] = code
                    }
                    "font" -> if (section == "fonts") fontBold = false
                    "b" -> if (section == "fonts") fontBold = p.isOn()
                    "fill" -> if (section == "fills") { fillSolid = false; fillColor = null }
                    "patternFill" -> if (section == "fills") fillSolid = p.attr("patternType") == "solid"
                    "fgColor" -> if (section == "fills") {
                        val rgb = p.attr("rgb")
                        if (rgb != null && rgb.length >= 6) fillColor = rgb.takeLast(6)
                    }
                    "xf" -> if (section == "cellXfs") {
                        xNumFmt = p.attr("numFmtId")?.toIntOrNull() ?: 0
                        xFont = p.attr("fontId")?.toIntOrNull() ?: 0
                        xFill = p.attr("fillId")?.toIntOrNull() ?: 0
                        xAlign = null
                        xWrap = false
                    }
                    "alignment" -> if (section == "cellXfs") {
                        xAlign = p.attr("horizontal")
                        xWrap = p.attr("wrapText").let { it == "1" || it == "true" }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (val n = lname(p.name)) {
                    "font" -> if (section == "fonts") fonts.add(fontBold)
                    "fill" -> if (section == "fills") fills.add(if (fillSolid) fillColor else null)
                    "xf" -> if (section == "cellXfs") {
                        val fill = fills.getOrNull(xFill)?.takeIf { !it.equals("FFFFFF", true) }
                        xfs.add(Xf(xNumFmt, fonts.getOrNull(xFont) == true, fill, xAlign, xWrap))
                    }
                    else -> if (n == section) section = ""
                }
            }
            ev = p.next()
        }
        return Styles(numFmts, xfs)
    }

    /** "B12" -> (12, 2) */
    private fun parseRef(ref: String): Pair<Int, Int>? {
        var col = 0
        var i = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            i++
        }
        val row = ref.substring(i).filter { it.isDigit() }.toIntOrNull() ?: return null
        if (col == 0) return null
        return Pair(row, col)
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>, fmt: Formatter): SheetData {
        val d = SheetData()
        val p = newParser(bytes)
        var row = 0
        var col = 0
        var cType: String? = null
        var cStyle = 0
        var value = StringBuilder()
        var inV = false
        var inIs = false
        var inT = false
        var inPh = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (lname(p.name)) {
                    "col" -> {
                        val min = p.attr("min")?.toIntOrNull() ?: 0
                        val max = (p.attr("max")?.toIntOrNull() ?: min).coerceAtMost(MAX_COLS)
                        val w = p.attr("width")?.toDoubleOrNull()
                        val hidden = p.attr("hidden").let { it == "1" || it == "true" }
                        for (c in min..max) {
                            if (w != null) d.colWidths[c] = w
                            if (hidden) d.hiddenCols.add(c)
                        }
                    }
                    "row" -> {
                        row = p.attr("r")?.toIntOrNull() ?: (row + 1)
                        col = 0
                        if (p.attr("hidden").let { it == "1" || it == "true" }) d.hiddenRows.add(row)
                    }
                    "c" -> {
                        val ref = p.attr("r")?.let { parseRef(it) }
                        if (ref != null) { row = ref.first; col = ref.second } else col++
                        cType = p.attr("t")
                        cStyle = p.attr("s")?.toIntOrNull() ?: 0
                        value = StringBuilder()
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> inT = true
                    "rPh" -> inPh++
                    "mergeCell" -> {
                        val ref = p.attr("ref")
                        if (ref != null && ref.contains(':')) {
                            val a = parseRef(ref.substringBefore(':'))
                            val b = parseRef(ref.substringAfter(':'))
                            if (a != null && b != null) d.merges.add(intArrayOf(a.first, a.second, b.first, b.second))
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inV || (inIs && inT && inPh == 0)) value.append(p.text)
                }
                XmlPullParser.END_TAG -> when (lname(p.name)) {
                    "v" -> inV = false
                    "is" -> inIs = false
                    "t" -> inT = false
                    "rPh" -> inPh--
                    "c" -> {
                        val raw = value.toString()
                        if (raw.isNotEmpty()) {
                            if (row > MAX_ROWS || col > MAX_COLS) {
                                d.truncated = true
                            } else {
                                val cell = when (cType) {
                                    "s" -> Cell(raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: "", cStyle, false)
                                    "str", "inlineStr" -> Cell(unescapeX(raw), cStyle, false)
                                    "b" -> Cell(if (raw.trim() == "1") "TRUE" else "FALSE", cStyle, false)
                                    "e" -> Cell(raw, cStyle, false)
                                    "d" -> Cell(raw.replace('T', ' '), cStyle, true)
                                    else -> Cell(fmt.number(raw, cStyle), cStyle, true)
                                }
                                if (cell.text.isNotEmpty()) {
                                    d.rows.getOrPut(row) { HashMap() }[col] = cell
                                    if (row > d.maxRow) d.maxRow = row
                                    if (col > d.maxCol) d.maxCol = col
                                }
                            }
                        }
                    }
                }
            }
            ev = p.next()
        }
        return d
    }

    private fun colName(c: Int): String {
        var n = c
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun renderSheet(sb: StringBuilder, d: SheetData, styles: Styles) {
        if (d.maxRow == 0) {
            sb.append("<p class=\"empty\">Trang tính trống.</p>")
            return
        }
        val lastRow = d.maxRow
        val lastCol = d.maxCol
        fun key(r: Int, c: Int) = (r.toLong() shl 20) or c.toLong()
        val spanAt = HashMap<Long, IntArray>()
        val covered = HashSet<Long>()
        for (m in d.merges) {
            val r1 = m[0]; val c1 = m[1]
            val r2 = minOf(m[2], lastRow); val c2 = minOf(m[3], lastCol)
            if (r1 > lastRow || c1 > lastCol) continue
            spanAt[key(r1, c1)] = intArrayOf(r2 - r1 + 1, c2 - c1 + 1)
            for (r in r1..r2) for (c in c1..c2) if (r != r1 || c != c1) covered.add(key(r, c))
        }

        sb.append("<table class=\"xl\"><colgroup><col class=\"rh\">")
        for (c in 1..lastCol) {
            if (c in d.hiddenCols) continue
            val w = d.colWidths[c]?.let { (it * 7 + 5).toInt().coerceIn(24, 600) } ?: 64
            sb.append("<col style=\"width:").append(w).append("px\">")
        }
        sb.append("</colgroup><thead><tr><th class=\"c0\"></th>")
        for (c in 1..lastCol) {
            if (c in d.hiddenCols) continue
            sb.append("<th>").append(colName(c)).append("</th>")
        }
        sb.append("</tr></thead><tbody>")
        for (r in 1..lastRow) {
            if (r in d.hiddenRows) continue
            val rowCells = d.rows[r]
            sb.append("<tr><th>").append(r).append("</th>")
            for (c in 1..lastCol) {
                if (c in d.hiddenCols) continue
                val k = key(r, c)
                if (k in covered) continue
                val cell = rowCells?.get(c)
                sb.append("<td")
                spanAt[k]?.let { s ->
                    if (s[0] > 1) sb.append(" rowspan=\"").append(s[0]).append('"')
                    if (s[1] > 1) sb.append(" colspan=\"").append(s[1]).append('"')
                }
                if (cell != null) {
                    val xf = styles.xfs.getOrNull(cell.xf)
                    val css = StringBuilder()
                    if (xf?.bold == true) css.append("font-weight:bold;")
                    xf?.fill?.let { css.append("background:#").append(it).append(';') }
                    when (xf?.align) {
                        "center", "centerContinuous" -> css.append("text-align:center;")
                        "right" -> css.append("text-align:right;")
                        "left" -> css.append("text-align:left;")
                        else -> if (cell.numeric) css.append("text-align:right;")
                    }
                    if (xf?.wrap == true) css.append("white-space:pre-wrap;")
                    if (css.isNotEmpty()) sb.append(" style=\"").append(css).append('"')
                    sb.append('>').append(esc(cell.text))
                } else {
                    sb.append('>')
                }
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        if (d.truncated) {
            sb.append("<p class=\"empty\">Trang tính quá lớn, chỉ hiển thị ")
                .append(MAX_ROWS).append(" hàng và ").append(MAX_COLS).append(" cột đầu.</p>")
        }
    }

    /** Định dạng số và ngày tháng theo kiểu Excel. */
    private class Formatter(private val styles: Styles, date1904: Boolean) {
        private val locale = Locale.getDefault()
        private val decSep = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        private val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)

        fun number(raw: String, xfIndex: Int): String {
            val v = raw.trim().toDoubleOrNull() ?: return raw
            val id = styles.xfs.getOrNull(xfIndex)?.numFmtId ?: 0
            val code = styles.numFmts[id] ?: BUILTIN[id]
            return try {
                when {
                    isDate(id, code) -> date(v, id, code)
                    code == null || code.equals("General", true) || code == "@" -> general(v)
                    else -> withCode(v, code)
                }
            } catch (e: Exception) {
                general(v)
            }
        }

        private fun strip(code: String): String = code
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\\\."), "")
            .replace(Regex("\\[(?![hHmMsS]+\\])[^\\]]*\\]"), "")

        private fun isDate(id: Int, code: String?): Boolean {
            if (id in 14..22 || id in 27..36 || id in 45..47 || id in 50..58) return true
            if (code == null || code.equals("General", true)) return false
            val c = strip(code)
            if (c.contains('0') || c.contains('#') || c.contains('?')) return false
            return Regex("[dDyYhHsS]").containsMatchIn(c) || c.contains('m', ignoreCase = true)
        }

        private fun date(v: Double, id: Int, code: String?): String {
            if (v < 0 || v > 2958465) return general(v)
            val c = code?.let { strip(it).lowercase() } ?: ""
            val timeOnly = id in 18..21 || id in 45..47 ||
                (code != null && !c.contains('d') && !c.contains('y') && c.contains('h'))
            val hasTime = timeOnly || id == 22 || c.contains('h') || c.contains('s')
            val dt = base.plusSeconds((v * 86400).roundToLong())
            val sbFmt = StringBuilder()
            if (!timeOnly) sbFmt.append("dd/MM/yyyy")
            if (hasTime) {
                if (sbFmt.isNotEmpty()) sbFmt.append(' ')
                sbFmt.append("HH:mm")
                if (c.contains('s')) sbFmt.append(":ss")
            }
            return dt.format(DateTimeFormatter.ofPattern(sbFmt.toString()))
        }

        fun general(v: Double): String {
            if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
            val s = BigDecimal(v).round(MathContext(11, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
            return if (decSep == '.') s else s.replace('.', decSep)
        }

        private fun withCode(v: Double, code: String): String {
            val parts = code.split(';')
            val sec0 = when {
                v == 0.0 && parts.size > 2 -> parts[2]
                v < 0 && parts.size > 1 -> parts[1]
                else -> parts[0]
            }
            val useNeg = v < 0 && parts.size > 1
            val sec = sec0.replace(Regex("_.|\\*."), "")
            if (sec.contains("E+", true) || sec.contains("E-", true)) return String.format(locale, "%.2E", v)
            val lit = Regex("\"([^\"]*)\"|\\[\\$([^\\]\\-]*)[^\\]]*\\]|\\\\(.)")
            val firstDigit = run {
                var i = 0
                var inQ = false
                var found = -1
                while (i < sec.length) {
                    val ch = sec[i]
                    if (ch == '"') inQ = !inQ
                    else if (!inQ && (ch == '0' || ch == '#' || ch == '?')) { found = i; break }
                    i++
                }
                found
            }
            if (firstDigit < 0) {
                // Không có chữ số: chỉ có chữ (vd phần dành cho số 0 là "-")
                val text = lit.findAll(sec).joinToString("") { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "" }
                return text.ifEmpty { general(v) }
            }
            val prefix = StringBuilder()
            val suffix = StringBuilder()
            lit.findAll(sec).forEach { m ->
                val t = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
                if (m.range.first < firstDigit) prefix.append(t) else suffix.append(t)
            }
            val core = lit.replace(sec, "").replace(Regex("\\[[^\\]]*\\]"), "")
            val pct = core.contains('%')
            val x0 = if (useNeg) -v else v
            val x = if (pct) x0 * 100 else x0
            val dot = core.indexOf('.')
            val frac = if (dot < 0) "" else core.substring(dot + 1).takeWhile { it == '0' || it == '#' || it == '?' }
            val nf = NumberFormat.getNumberInstance(locale).apply {
                isGroupingUsed = Regex("[0#?],[0#?]").containsMatchIn(core)
                maximumFractionDigits = frac.length
                minimumFractionDigits = frac.count { it == '0' }
                roundingMode = RoundingMode.HALF_UP
            }
            var s = if (x == 0.0 && !core.contains('0')) "" else nf.format(x)
            if (useNeg) {
                if (core.contains('(')) s = "($s)"
                else if (core.trimStart().startsWith("-")) s = "-$s"
            }
            if (pct && s.isNotEmpty()) s += "%"
            return (prefix.toString() + s + suffix.toString()).trim()
        }
    }
}
