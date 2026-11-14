package vn.trinhdoc.app

import android.net.Uri
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ---------- Tiện ích DOM (bỏ tiền tố namespace: "p:sp" -> "sp") ----------

private fun lnm(n: Node): String = n.nodeName.substringAfter(':')

private fun Element.elems(): List<Element> {
    val out = ArrayList<Element>()
    var c = firstChild
    while (c != null) {
        if (c is Element) out.add(c)
        c = c.nextSibling
    }
    return out
}

private fun Element.kids(name: String): List<Element> = elems().filter { lnm(it) == name }

private fun Element.kid(name: String): Element? {
    var c = firstChild
    while (c != null) {
        if (c is Element && lnm(c) == name) return c
        c = c.nextSibling
    }
    return null
}

private fun Element.dig(vararg path: String): Element? {
    var e: Element? = this
    for (p in path) e = e?.kid(p)
    return e
}

/** Thuộc tính không có tiền tố. */
private fun Element.at(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

/** Thuộc tính quan hệ có tiền tố (r:embed, r:id, r:dm...). */
private fun Element.rid(name: String): String? {
    val a = attributes
    for (i in 0 until a.length) {
        val n = a.item(i).nodeName
        if (n.endsWith(":$name")) return a.item(i).nodeValue
    }
    return null
}

private fun Element.find(name: String): Element? {
    for (c in elems()) {
        if (lnm(c) == name) return c
        c.find(name)?.let { return it }
    }
    return null
}

private fun Element.num(name: String): Double? = at(name)?.toDoubleOrNull()

private fun Element.flag(name: String): Boolean? = at(name)?.let { it == "1" || it == "true" }

private fun f3(v: Double): String {
    val s = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    return if (s == "-0" || s.isEmpty()) "0" else s
}

private fun st(css: CharSequence): String = if (css.isEmpty()) "" else " style=\"" + esc(css.toString()) + "\""

/**
 * Chuyển file PowerPoint (.pptx) sang HTML. Mỗi trang chiếu là một khung giữ đúng tỉ lệ,
 * hình/chữ/ảnh đặt theo toạ độ gốc. Có kế thừa vị trí và kiểu chữ từ bố cục (layout) và
 * trang cái (master), màu theo theme, bảng, SmartArt (bản vẽ sẵn), hình tự do.
 */
object PptxConverter {

    fun convert(file: File): DocxResult = Zip(file).use { zip -> Job(zip).run() }

    private const val EMU_PT = 12700.0
    private const val SLIDE = 0
    private const val LAYOUT = 1
    private const val MASTER = 2
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg")
    private val LINE_GEOMS = setOf(
        "line", "straightConnector1", "bentConnector2", "bentConnector3", "bentConnector4",
        "curvedConnector2", "curvedConnector3", "curvedConnector4"
    )
    private val PRESET_COLORS = mapOf(
        "black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "green" to "008000",
        "blue" to "0000FF", "yellow" to "FFFF00", "gray" to "808080", "grey" to "808080",
        "orange" to "FFA500", "darkBlue" to "00008B", "darkRed" to "8B0000", "darkGreen" to "006400",
        "ltGray" to "D3D3D3", "dkGray" to "A9A9A9", "silver" to "C0C0C0", "navy" to "000080",
        "purple" to "800080", "cyan" to "00FFFF", "magenta" to "FF00FF"
    )

    /** Polygon (theo % khung) cho một số hình dựng sẵn hay gặp. */
    private val POLYGONS = mapOf(
        "triangle" to "50% 0,100% 100%,0 100%",
        "rtTriangle" to "0 0,100% 100%,0 100%",
        "diamond" to "50% 0,100% 50%,50% 100%,0 50%",
        "parallelogram" to "25% 0,100% 0,75% 100%,0 100%",
        "trapezoid" to "25% 0,75% 0,100% 100%,0 100%",
        "pentagon" to "50% 0,100% 38%,81% 100%,19% 100%,0 38%",
        "hexagon" to "25% 0,75% 0,100% 50%,75% 100%,25% 100%,0 50%",
        "octagon" to "29% 0,71% 0,100% 29%,100% 71%,71% 100%,29% 100%,0 71%,0 29%",
        "homePlate" to "0 0,80% 0,100% 50%,80% 100%,0 100%",
        "chevron" to "0 0,80% 0,100% 50%,80% 100%,0 100%,20% 50%",
        "rightArrow" to "0 25%,70% 25%,70% 0,100% 50%,70% 100%,70% 75%,0 75%",
        "leftArrow" to "30% 0,30% 25%,100% 25%,100% 75%,30% 75%,30% 100%,0 50%",
        "upArrow" to "50% 0,100% 30%,75% 30%,75% 100%,25% 100%,25% 30%,0 30%",
        "downArrow" to "25% 0,75% 0,75% 70%,100% 70%,50% 100%,0 70%,25% 70%",
        "plus" to "35% 0,65% 0,65% 35%,100% 35%,100% 65%,65% 65%,65% 100%,35% 100%,35% 65%,0 65%,0 35%,35% 35%",
        "star5" to "50% 0,61% 35%,98% 35%,68% 57%,79% 91%,50% 70%,21% 91%,32% 57%,2% 35%,39% 35%",
        "flowChartDecision" to "50% 0,100% 50%,50% 100%,0 50%",
        "flowChartInputOutput" to "20% 0,100% 0,80% 100%,0 100%"
    )

    /** Vùng chữ thu vào trong một số hình (trái, trên, phải, dưới; % khung). */
    private val TEXT_INSET = mapOf(
        "ellipse" to doubleArrayOf(14.6, 14.6, 14.6, 14.6),
        "flowChartConnector" to doubleArrayOf(14.6, 14.6, 14.6, 14.6),
        "diamond" to doubleArrayOf(25.0, 25.0, 25.0, 25.0),
        "flowChartDecision" to doubleArrayOf(25.0, 25.0, 25.0, 25.0),
        "chevron" to doubleArrayOf(20.0, 0.0, 20.0, 0.0),
        "homePlate" to doubleArrayOf(0.0, 0.0, 20.0, 0.0),
        "triangle" to doubleArrayOf(25.0, 50.0, 25.0, 0.0),
        "hexagon" to doubleArrayOf(12.5, 0.0, 12.5, 0.0),
        "octagon" to doubleArrayOf(8.8, 8.8, 8.8, 8.8),
        "rightArrow" to doubleArrayOf(0.0, 25.0, 30.0, 25.0),
        "leftArrow" to doubleArrayOf(30.0, 25.0, 0.0, 25.0)
    )

    private val WINGDINGS = mapOf(
        "§" to "▪", "Ø" to "➢", "ü" to "✓", "q" to "❑", "v" to "❖", "n" to "■", "l" to "●",
        "Ö" to "➤", "à" to "➔", "ð" to "⇨", "Ü" to "✔", "o" to "□", "u" to "◆", "w" to "⬥",
        "¨" to "□", "þ" to "☑", "®" to "•", "Ä" to "➢", "è" to "➜", "ê" to "➝"
    )

    private class Part(val path: String, val root: Element, val rels: List<Rel>) {
        fun target(rid: String?): String? =
            if (rid == null) null else rels.firstOrNull { it.id == rid && !it.external }?.target

        fun ofType(suffix: String): String? = rels.firstOrNull { !it.external && it.type.endsWith(suffix) }?.target
    }

    /** Ánh xạ toạ độ con (trong nhóm) ra toạ độ trang. */
    private class Xf(val sx: Double, val sy: Double, val ox: Double, val oy: Double) {
        fun x(v: Double) = ox + v * sx
        fun y(v: Double) = oy + v * sy
    }

    private class Box(
        val x: Double, val y: Double, val w: Double, val h: Double,
        val rot: Double, val flipH: Boolean, val flipV: Boolean,
        val extW: Double, val extH: Double
    )

    private class Ph(val type: String, val idx: String?)

    private class Paint(val css: String, val solid: String?)

    private class TextCtx(
        val lst: List<Element>,
        val glob: List<Element>,
        val color: String?,
        val bold: Boolean,
        val scale: Double,
        val lnRed: Double
    )

    private val NO_PAINT = Paint("none", null)

    private class Job(val zip: Zip) {
        val images = HashMap<String, ByteArray>()
        private val parts = HashMap<String, Part?>()
        private val phCache = HashMap<String, List<Pair<Ph, Element>>>()
        private val themeCache = HashMap<String, Map<String, String>>()
        private var cx = 9144000.0
        private var cy = 6858000.0
        private var wPt = cx / EMU_PT
        private var scheme: Map<String, String> = emptyMap()
        private var clrMap: Map<String, String> = emptyMap()
        private var defaultTextStyle: Element? = null
        private var tableStyles: Element? = null
        private var themeRoot: Element? = null
        private var layout: Part? = null
        private var master: Part? = null

        fun run(): DocxResult {
            val presPath = mainPart(zip) ?: "ppt/presentation.xml"
            val pres = part(presPath) ?: throw ReaderException("File PowerPoint bị hỏng hoặc không có nội dung.")
            pres.root.kid("sldSz")?.let { s ->
                s.num("cx")?.let { if (it > 0) cx = it }
                s.num("cy")?.let { if (it > 0) cy = it }
            }
            wPt = cx / EMU_PT
            defaultTextStyle = pres.root.kid("defaultTextStyle")
            tableStyles = pres.ofType("/tableStyles")?.let { part(it) }?.root

            val sb = StringBuilder()
            sb.append("<div class=\"deck\"").append(st("--r:" + f3(cx / cy))).append('>')
            var n = 0
            for (id in pres.root.kid("sldIdLst")?.kids("sldId") ?: emptyList()) {
                val slide = pres.target(id.rid("id"))?.let { part(it) } ?: continue
                if (slide.root.at("show") == "0") continue
                val one = StringBuilder()
                try {
                    renderSlide(one, slide)
                } catch (e: Exception) {
                    one.setLength(0)
                    one.append("<div class=\"slide\"><div class=\"sp chart\"")
                        .append(st("left:0;top:0;width:100%;height:100%"))
                        .append(">Không hiển thị được trang này</div></div>")
                }
                sb.append(one)
                n++
            }
            sb.append("</div>")
            if (n == 0) return DocxResult("<p class=\"empty\">Bài trình chiếu không có trang nào.</p>", images)
            return DocxResult(sb.toString(), images)
        }

        // ---------- Đọc các phần của gói ----------

        private fun part(path: String): Part? = parts.getOrPut(path) {
            val bytes = zip.bytes(path)
            if (bytes == null) {
                null
            } else {
                try {
                    Part(path, parse(bytes), relsOf(zip, path))
                } catch (e: Exception) {
                    null
                }
            }
        }

        private fun parse(bytes: ByteArray): Element {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            f.isValidating = false
            try {
                f.isExpandEntityReferences = false
            } catch (_: Exception) {
            }
            return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        }

        private fun imageUrl(rid: String?, p: Part): String? {
            val t = p.target(rid) ?: return null
            if (t.substringAfterLast('.', "").lowercase() !in IMAGE_EXT) return null
            if (!images.containsKey(t)) images[t] = zip.bytes(t) ?: return null
            return "/img/" + Uri.encode(t, "/")
        }

        // ---------- Đơn vị ----------

        private fun cqw(pt: Double) = f3(pt * 100.0 / wPt) + "cqw"
        private fun cqwE(emu: Double) = cqw(emu / EMU_PT)
        private fun pctX(v: Double) = f3(v * 100.0 / cx) + "%"
        private fun pctY(v: Double) = f3(v * 100.0 / cy) + "%"

        // ---------- Màu ----------

        private fun setTheme(m: Part?) {
            if (m == null) {
                scheme = emptyMap(); clrMap = emptyMap(); themeRoot = null; return
            }
            themeRoot = m.ofType("/theme")?.let { part(it) }?.root
            scheme = themeCache.getOrPut(m.path) {
                val out = HashMap<String, String>()
                val theme = m.ofType("/theme")?.let { part(it) }
                theme?.root?.dig("themeElements", "clrScheme")?.elems()?.forEach { c ->
                    val v = c.kid("srgbClr")?.at("val")
                        ?: c.kid("sysClr")?.let { s -> s.at("lastClr") ?: if (s.at("val") == "window") "FFFFFF" else "000000" }
                    if (v != null) out[lnm(c)] = v.uppercase()
                }
                out
            }
            clrMap = attrMap(m.root.kid("clrMap"))
        }

        private fun attrMap(e: Element?): Map<String, String> {
            if (e == null) return emptyMap()
            val m = HashMap<String, String>()
            val a = e.attributes
            for (i in 0 until a.length) m[a.item(i).nodeName] = a.item(i).nodeValue
            return m
        }

        private fun schemeHex(name: String?): String? {
            if (name == null || name == "phClr") return null
            val mapped = clrMap[name] ?: when (name) {
                "bg1" -> "lt1"; "tx1" -> "dk1"; "bg2" -> "lt2"; "tx2" -> "dk2"; else -> name
            }
            return scheme[mapped] ?: scheme[name]
        }

        /** Màu từ phần tử chứa một nút màu (solidFill, fillRef, buClr, gs...). */
        fun colorOf(c: Element?, ph: String? = null): String? {
            if (c == null) return null
            for (k in c.elems()) {
                val base: String = when (lnm(k)) {
                    "srgbClr" -> k.at("val")
                    "schemeClr" -> if (k.at("val") == "phClr") ph else schemeHex(k.at("val"))
                    "sysClr" -> k.at("lastClr") ?: if (k.at("val") == "window") "FFFFFF" else "000000"
                    "prstClr" -> PRESET_COLORS[k.at("val")]
                    "scrgbClr" -> {
                        fun ch(n: String) = ((k.num(n) ?: 0.0) / 100000.0).coerceIn(0.0, 1.0).pow(1 / 2.2) * 255
                        String.format(Locale.US, "%02X%02X%02X", ch("r").toInt(), ch("g").toInt(), ch("b").toInt())
                    }
                    else -> continue
                } ?: return null
                return applyMods(base, k)
            }
            return null
        }

        private fun applyMods(hex: String, el: Element): String {
            if (hex.length < 6) return "#000000"
            val v = hex.takeLast(6).toLongOrNull(16) ?: return "#000000"
            var r = ((v shr 16) and 255) / 255.0
            var g = ((v shr 8) and 255) / 255.0
            var b = (v and 255) / 255.0
            var alpha = 1.0
            for (m in el.elems()) {
                val x = (m.num("val") ?: continue) / 100000.0
                when (lnm(m)) {
                    "lumMod", "lumOff", "satMod" -> {
                        val hsl = toHsl(r, g, b)
                        when (lnm(m)) {
                            "lumMod" -> hsl[2] = (hsl[2] * x).coerceIn(0.0, 1.0)
                            "lumOff" -> hsl[2] = (hsl[2] + x).coerceIn(0.0, 1.0)
                            else -> hsl[1] = (hsl[1] * x).coerceIn(0.0, 1.0)
                        }
                        val rgb = fromHsl(hsl[0], hsl[1], hsl[2])
                        r = rgb[0]; g = rgb[1]; b = rgb[2]
                    }
                    "tint" -> { r = 1 - (1 - r) * x; g = 1 - (1 - g) * x; b = 1 - (1 - b) * x }
                    "shade" -> { r *= x; g *= x; b *= x }
                    "alpha" -> alpha = x
                }
            }
            val ri = (r.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
            val gi = (g.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
            val bi = (b.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
            return if (alpha < 0.995) "rgba($ri,$gi,$bi,${f3(alpha)})"
            else String.format(Locale.US, "#%02X%02X%02X", ri, gi, bi)
        }

        private fun toHsl(r: Double, g: Double, b: Double): DoubleArray {
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val l = (mx + mn) / 2
            if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
            val d = mx - mn
            val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            val h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6
            return doubleArrayOf(h, s, l)
        }

        private fun fromHsl(h: Double, s: Double, l: Double): DoubleArray {
            if (s == 0.0) return doubleArrayOf(l, l, l)
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            fun hue(t0: Double): Double {
                var t = t0
                if (t < 0) t += 1.0
                if (t > 1) t -= 1.0
                return when {
                    t < 1.0 / 6 -> p + (q - p) * 6 * t
                    t < 0.5 -> q
                    t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                    else -> p
                }
            }
            return doubleArrayOf(hue(h + 1.0 / 3), hue(h), hue(h - 1.0 / 3))
        }

        /** Mix màu hex với trắng: k=1 giữ nguyên, k=0 trắng. */
        private fun mixWhite(hex: String, k: Double): String {
            val v = hex.removePrefix("#").takeLast(6).toLongOrNull(16) ?: return "#FFFFFF"
            fun c(x: Long) = (255 - (255 - x) * k + 0.5).toInt().coerceIn(0, 255)
            return String.format(Locale.US, "#%02X%02X%02X", c((v shr 16) and 255), c((v shr 8) and 255), c(v and 255))
        }

        /** Nền: trả về Paint, NO_PAINT nếu "không tô", null nếu không khai báo. */
        private fun paint(c: Element?, p: Part): Paint? {
            if (c == null) return null
            for (k in c.elems()) {
                when (lnm(k)) {
                    "noFill" -> return NO_PAINT
                    "solidFill" -> return colorOf(k)?.let { Paint(it, it) } ?: NO_PAINT
                    "gradFill" -> return gradient(k)
                    "blipFill" -> {
                        val u = imageUrl(k.kid("blip")?.rid("embed"), p) ?: return NO_PAINT
                        return Paint("url(\"$u\") center / 100% 100% no-repeat", null)
                    }
                    "pattFill" -> {
                        val col = colorOf(k.kid("bgClr")) ?: colorOf(k.kid("fgClr"))
                        return if (col != null) Paint(col, col) else NO_PAINT
                    }
                }
            }
            return null
        }

        private fun gradient(g: Element, ph: String? = null): Paint {
            val stops = (g.kid("gsLst")?.kids("gs") ?: emptyList()).mapNotNull { gs ->
                colorOf(gs, ph)?.let { Pair((gs.num("pos") ?: 0.0) / 1000.0, it) }
            }.sortedBy { it.first }
            if (stops.isEmpty()) return NO_PAINT
            if (stops.size == 1) return Paint(stops[0].second, stops[0].second)
            val list = stops.joinToString(",") { it.second + " " + f3(it.first) + "%" }
            val lin = g.kid("lin")
            val css = if (lin == null && g.kid("path") != null) "radial-gradient(circle, $list)"
            else "linear-gradient(" + f3((lin?.num("ang") ?: 0.0) / 60000.0 + 90.0) + "deg, " + list + ")"
            return Paint(css, stops[0].second)
        }

        // ---------- Kiểu tô / đường viền lấy từ theme (fillRef, lnRef) ----------

        private fun styleList(name: String): List<Element> =
            themeRoot?.dig("themeElements", "fmtScheme", name)?.elems() ?: emptyList()

        private fun refFill(ref: Element?): Paint? {
            if (ref == null) return null
            val idx = ref.num("idx")?.toInt() ?: return null
            if (idx <= 0) return null
            val ph = colorOf(ref)?.takeIf { it.startsWith("#") }?.removePrefix("#")
            val el = if (idx >= 1001) styleList("bgFillStyleLst").getOrNull(idx - 1001)
            else styleList("fillStyleLst").getOrNull(idx - 1)
            return when (el?.let { lnm(it) }) {
                "solidFill" -> colorOf(el, ph)?.let { Paint(it, it) }
                "gradFill" -> gradient(el, ph)
                "noFill" -> NO_PAINT
                else -> ph?.let { Paint("#$it", "#$it") }
            }
        }

        private fun refLine(ref: Element?): Pair<String, Double>? {
            if (ref == null) return null
            val idx = ref.num("idx")?.toInt() ?: return null
            if (idx <= 0) return null
            val ph = colorOf(ref)?.takeIf { it.startsWith("#") }?.removePrefix("#")
            val el = styleList("lnStyleLst").getOrNull(idx - 1)
            if (el?.kid("noFill") != null) return null
            val col = colorOf(el?.kid("solidFill"), ph) ?: ph?.let { "#$it" } ?: return null
            return Pair(col, (el?.num("w") ?: 9525.0) / EMU_PT)
        }

        // ---------- Trang chiếu ----------

        private fun renderSlide(out: StringBuilder, slide: Part) {
            layout = slide.ofType("/slideLayout")?.let { part(it) }
            master = layout?.ofType("/slideMaster")?.let { part(it) }
            setTheme(master)
            listOf(layout, slide).forEach { p ->
                p?.root?.kid("clrMapOvr")?.kid("overrideClrMapping")?.let { clrMap = attrMap(it) }
            }
            val bg = listOf(slide, layout, master).firstNotNullOfOrNull { p -> p?.let { background(it) } } ?: "#FFFFFF"
            out.append("<div class=\"slide\"").append(st("background:$bg")).append('>')
            val id = Xf(1.0, 1.0, 0.0, 0.0)
            if (slide.root.at("showMasterSp") != "0") {
                val m = master
                val l = layout
                if (m != null && l?.root?.at("showMasterSp") != "0") tree(out, m, m.root.dig("cSld", "spTree"), id, MASTER)
                if (l != null) tree(out, l, l.root.dig("cSld", "spTree"), id, LAYOUT)
            }
            tree(out, slide, slide.root.dig("cSld", "spTree"), id, SLIDE)
            out.append("</div>")
        }

        private fun background(p: Part): String? {
            val bg = p.root.dig("cSld", "bg") ?: return null
            bg.kid("bgPr")?.let { pr -> paint(pr, p)?.let { if (it !== NO_PAINT) return it.css } }
            bg.kid("bgRef")?.let { ref -> refFill(ref)?.let { if (it !== NO_PAINT) return it.css }; return colorOf(ref) }
            return null
        }

        // ---------- Placeholder: kế thừa từ layout và master ----------

        private fun phOf(e: Element): Ph? {
            val nv = e.elems().firstOrNull { lnm(it).startsWith("nv") } ?: return null
            val ph = nv.kid("nvPr")?.kid("ph") ?: return null
            return Ph(ph.at("type") ?: "obj", ph.at("idx"))
        }

        private fun normType(t: String) = when (t) {
            "title", "ctrTitle" -> "title"
            "dt", "ftr", "sldNum", "hdr" -> t
            else -> "body"
        }

        private fun phList(p: Part): List<Pair<Ph, Element>> = phCache.getOrPut(p.path) {
            p.root.dig("cSld", "spTree")?.elems()?.mapNotNull { e -> phOf(e)?.let { Pair(it, e) } } ?: emptyList()
        }

        private fun findPh(p: Part?, ph: Ph): Element? {
            if (p == null) return null
            val list = phList(p)
            if (ph.idx != null) list.firstOrNull { it.first.idx == ph.idx }?.let { return it.second }
            list.firstOrNull { it.first.type == ph.type }?.let { return it.second }
            return list.firstOrNull { normType(it.first.type) == normType(ph.type) }?.second
        }

        private fun chainOf(e: Element, ph: Ph?, level: Int): List<Element> {
            if (ph == null || level != SLIDE) return listOf(e)
            val l = findPh(layout, ph)
            val m = findPh(master, l?.let { phOf(it) } ?: ph)
            return listOfNotNull(e, l, m)
        }

        private fun globalStyles(ph: Ph?): List<Element> {
            val tx = master?.root?.kid("txStyles")
            if (ph == null) return listOfNotNull(defaultTextStyle, tx?.kid("otherStyle"))
            return when (normType(ph.type)) {
                "title" -> listOfNotNull(tx?.kid("titleStyle"))
                "body" -> listOfNotNull(tx?.kid("bodyStyle"))
                else -> listOfNotNull(tx?.kid("otherStyle"), defaultTextStyle)
            }
        }

        // ---------- Duyệt cây hình ----------

        private fun tree(out: StringBuilder, p: Part, t: Element?, xf: Xf, level: Int) {
            if (t == null) return
            for (e in t.elems()) node(out, p, e, xf, level)
        }

        private fun node(out: StringBuilder, p: Part, e: Element, xf: Xf, level: Int) {
            when (lnm(e)) {
                "sp", "cxnSp" -> {
                    val ph = phOf(e)
                    if (ph != null && level != SLIDE) return
                    shape(out, p, e, xf, level, ph)
                }
                "pic" -> {
                    val ph = phOf(e)
                    if (ph != null && level != SLIDE) return
                    picture(out, p, e, xf, level, ph)
                }
                "grpSp" -> group(out, p, e, xf, level)
                "graphicFrame" -> {
                    if (phOf(e) != null && level != SLIDE) return
                    frame(out, p, e, xf, level)
                }
                "AlternateContent" -> {
                    val alt = e.kid("Fallback") ?: e.kid("Choice")
                    alt?.elems()?.forEach { node(out, p, it, xf, level) }
                }
            }
        }

        private fun boxOf(x: Element?, xf: Xf): Box? {
            if (x == null) return null
            val off = x.kid("off") ?: return null
            val ext = x.kid("ext") ?: return null
            val ex = off.num("x") ?: 0.0
            val ey = off.num("y") ?: 0.0
            val w = ext.num("cx") ?: 0.0
            val h = ext.num("cy") ?: 0.0
            return Box(
                xf.x(ex), xf.y(ey), w * xf.sx, h * xf.sy,
                (x.num("rot") ?: 0.0) / 60000.0, x.flag("flipH") == true, x.flag("flipV") == true, w, h
            )
        }

        private fun boxFor(e: Element, chain: List<Element>, xf: Xf): Box? {
            for (c in chain) {
                val x = c.kid("spPr")?.kid("xfrm") ?: continue
                return boxOf(x, if (c === e) xf else Xf(1.0, 1.0, 0.0, 0.0))
            }
            return null
        }

        private fun pos(b: Box): String {
            val s = StringBuilder()
            s.append("left:").append(pctX(b.x)).append(";top:").append(pctY(b.y))
                .append(";width:").append(pctX(b.w)).append(";height:").append(pctY(b.h)).append(';')
            if (b.rot != 0.0) s.append("transform:rotate(").append(f3(b.rot)).append("deg);")
            return s.toString()
        }

        private fun group(out: StringBuilder, p: Part, e: Element, xf: Xf, level: Int) {
            val gx = e.kid("grpSpPr")?.kid("xfrm")
            var child = xf
            if (gx != null) {
                val off = gx.kid("off")
                val ext = gx.kid("ext")
                val chOff = gx.kid("chOff")
                val chExt = gx.kid("chExt")
                if (off != null && ext != null && chOff != null && chExt != null) {
                    val ccx = chExt.num("cx") ?: 0.0
                    val ccy = chExt.num("cy") ?: 0.0
                    val sx = if (ccx > 0) (ext.num("cx") ?: 0.0) / ccx else 1.0
                    val sy = if (ccy > 0) (ext.num("cy") ?: 0.0) / ccy else 1.0
                    val ox = (off.num("x") ?: 0.0) - (chOff.num("x") ?: 0.0) * sx
                    val oy = (off.num("y") ?: 0.0) - (chOff.num("y") ?: 0.0) * sy
                    child = Xf(xf.sx * sx, xf.sy * sy, xf.ox + xf.sx * ox, xf.oy + xf.sy * oy)
                }
            }
            for (c in e.elems()) {
                val n = lnm(c)
                if (n == "grpSpPr" || n.startsWith("nv")) continue
                node(out, p, c, child, level)
            }
        }

        // ---------- Hình có chữ ----------

        private fun shape(out: StringBuilder, p: Part, e: Element, xf: Xf, level: Int, ph: Ph?) {
            val chain = chainOf(e, ph, level)
            val box = boxFor(e, chain, xf) ?: return
            val spPr = e.kid("spPr")
            val style = e.kid("style")

            var fill = paint(spPr, p)
            if (fill == null && ph != null) {
                fill = chain.drop(1).firstNotNullOfOrNull { c -> c.kid("spPr")?.let { paint(it, p) }?.takeIf { it.solid != null } }
            }
            if (fill == null) fill = refFill(style?.kid("fillRef"))
            if (fill === NO_PAINT) fill = null

            val ln = spPr?.kid("ln")
            var lineColor: String? = null
            var lineW = 0.75
            var dash = ""
            if (ln?.kid("noFill") == null) {
                lineColor = ln?.kid("solidFill")?.let { colorOf(it) }
                    ?: ln?.kid("gradFill")?.let { gradient(it).solid }
                val rl = if (lineColor == null) refLine(style?.kid("lnRef")) else null
                if (rl != null) lineColor = rl.first
                val refW = rl?.second
                lineW = ln?.num("w")?.let { it / EMU_PT } ?: refW ?: 0.75
                dash = when (ln?.kid("prstDash")?.at("val")) {
                    "dash", "sysDash", "lgDash", "dashDot", "lgDashDot", "lgDashDotDot", "sysDashDot" -> "dashed"
                    "dot", "sysDot" -> "dotted"
                    else -> ""
                }
            }

            val prst = spPr?.kid("prstGeom")?.at("prst")
            val cust = spPr?.kid("custGeom")
            val isLine = lnm(e) == "cxnSp" || prst in LINE_GEOMS
            val tb = e.kid("txBody")
            val textOk = tb != null && hasText(tb)

            if (!textOk && fill == null && lineColor == null) return

            val o = StringBuilder()
            o.append("<div class=\"sp").append(if (isLine) " lsp" else "").append('"').append(st(pos(box))).append('>')
            if (isLine) {
                if (lineColor != null) lineSvg(o, box, lineColor, lineW, dash)
            } else if (cust != null) {
                custSvg(o, cust, box, fill, lineColor, lineW)
            } else if (fill != null || lineColor != null) {
                val css = StringBuilder()
                if (fill != null) css.append("background:").append(fill.css).append(';')
                if (lineColor != null) {
                    css.append("border:").append(cqw(max(lineW, 0.4))).append(' ').append(dash.ifEmpty { "solid" })
                        .append(' ').append(lineColor).append(';')
                }
                when (prst) {
                    "ellipse", "flowChartConnector" -> css.append("border-radius:50%;")
                    "roundRect", "flowChartAlternateProcess" -> {
                        val adj = spPr?.kid("prstGeom")?.kid("avLst")?.kids("gd")?.firstOrNull()
                            ?.at("fmla")?.removePrefix("val ")?.trim()?.toDoubleOrNull() ?: 16667.0
                        css.append("border-radius:").append(cqwE(min(box.extW, box.extH) * adj / 100000.0)).append(';')
                    }
                    "snip1Rect", "round1Rect", "round2SameRect" -> css.append("border-radius:")
                        .append(cqwE(min(box.extW, box.extH) * 0.16667)).append(';')
                    else -> POLYGONS[prst]?.let { css.append("clip-path:polygon(").append(it).append(");") }
                }
                if (box.flipH || box.flipV) {
                    css.append("transform:scale(").append(if (box.flipH) "-1" else "1").append(',')
                        .append(if (box.flipV) "-1" else "1").append(");")
                }
                o.append("<div class=\"fl\"").append(st(css)).append("></div>")
            }

            if (textOk && tb != null) {
                val txX = e.kid("txXfrm")
                if (txX != null) {
                    o.append("</div>")
                    val tbox = boxOf(txX, xf) ?: box
                    o.append("<div class=\"sp\"").append(st(pos(tbox))).append('>')
                }
                textBody(o, tb, chain, ph, style, if (txX == null) TEXT_INSET[prst] else null)
            }
            o.append("</div>")
            out.append(o)
        }

        private fun hasText(tb: Element): Boolean {
            for (p in tb.kids("p")) for (r in p.elems()) {
                val n = lnm(r)
                if ((n == "r" || n == "fld") && !r.kid("t")?.textContent.isNullOrEmpty()) return true
            }
            return false
        }

        private fun textBody(o: StringBuilder, tb: Element, chain: List<Element>, ph: Ph?, style: Element?, inset: DoubleArray?) {
            val bodyPrs = chain.mapNotNull { it.kid("txBody")?.kid("bodyPr") }
            fun bp(n: String) = bodyPrs.firstNotNullOfOrNull { it.at(n) }
            val l = bp("lIns")?.toDoubleOrNull() ?: 91440.0
            val t = bp("tIns")?.toDoubleOrNull() ?: 45720.0
            val r = bp("rIns")?.toDoubleOrNull() ?: 91440.0
            val b = bp("bIns")?.toDoubleOrNull() ?: 45720.0
            val auto = tb.kid("bodyPr")?.kid("normAutofit")
            val scale = (auto?.num("fontScale") ?: 100000.0) / 100000.0
            val lnRed = (auto?.num("lnSpcReduction") ?: 0.0) / 100000.0
            val css = StringBuilder()
            css.append("padding:").append(cqwE(t)).append(' ').append(cqwE(r)).append(' ')
                .append(cqwE(b)).append(' ').append(cqwE(l)).append(';')
            css.append("justify-content:").append(
                when (bp("anchor")) { "ctr" -> "center"; "b" -> "flex-end"; else -> "flex-start" }
            ).append(';')
            if (bp("wrap") == "none") css.append("white-space:nowrap;")
            if (inset != null) {
                css.append("left:").append(f3(inset[0])).append("%;top:").append(f3(inset[1])).append("%;right:")
                    .append(f3(inset[2])).append("%;bottom:").append(f3(inset[3])).append("%;")
            }
            o.append("<div class=\"tx\"").append(st(css)).append('>')
            val ctx = TextCtx(
                chain.mapNotNull { it.kid("txBody")?.kid("lstStyle") },
                globalStyles(ph),
                style?.kid("fontRef")?.let { colorOf(it) },
                false, scale, lnRed
            )
            paragraphs(o, tb, ctx)
            o.append("</div>")
        }

        // ---------- Đoạn văn ----------

        private fun paragraphs(o: StringBuilder, tb: Element, tc: TextCtx) {
            val counters = IntArray(9)
            val numType = arrayOfNulls<String>(9)
            var first = true
            for (p in tb.kids("p")) {
                val pPr = p.kid("pPr")
                val lvl = (pPr?.num("lvl")?.toInt() ?: 0).coerceIn(0, 8)
                val key = "lvl${lvl + 1}pPr"
                val local = listOfNotNull(pPr) + tc.lst.mapNotNull { it.kid(key) }
                val global = tc.glob.mapNotNull { it.kid(key) }
                val all = local + global
                fun pa(n: String) = all.firstNotNullOfOrNull { it.at(n) }
                fun pk(n: String) = all.firstNotNullOfOrNull { it.kid(n) }
                fun dr(n: String) = all.firstNotNullOfOrNull { it.kid("defRPr")?.at(n) }

                val defSz = (dr("sz")?.toDoubleOrNull() ?: 1800.0) / 100.0 * tc.scale
                val defColor = local.firstNotNullOfOrNull { colorOf(it.kid("defRPr")?.kid("solidFill")) }
                    ?: tc.color
                    ?: global.firstNotNullOfOrNull { colorOf(it.kid("defRPr")?.kid("solidFill")) }
                    ?: schemeHex("tx1")?.let { "#$it" } ?: "#000000"
                val defBold = tc.bold || dr("b").let { it == "1" || it == "true" }
                val defItalic = dr("i").let { it == "1" || it == "true" }

                val runs = StringBuilder()
                var hasText = false
                var firstColor: String? = null
                var firstSz: Double? = null
                for (c in p.elems()) {
                    when (lnm(c)) {
                        "r", "fld" -> {
                            val text = c.kid("t")?.textContent ?: ""
                            if (text.isEmpty()) continue
                            hasText = true
                            val rPr = c.kid("rPr")
                            val col = colorOf(rPr?.kid("solidFill"))
                            val sz = rPr?.num("sz")?.let { it / 100.0 * tc.scale }
                            if (firstColor == null) firstColor = col
                            if (firstSz == null) firstSz = sz
                            runs.append(run(text, rPr, col, sz, defBold, defItalic))
                        }
                        "br" -> runs.append("<br>")
                    }
                }

                val pcss = StringBuilder()
                when (pa("algn")) {
                    "ctr" -> pcss.append("text-align:center;")
                    "r" -> pcss.append("text-align:right;")
                    "just", "dist" -> pcss.append("text-align:justify;")
                }
                pcss.append("font-size:").append(cqw(if (hasText) defSz else (p.kid("endParaRPr")?.num("sz")?.let { it / 100.0 * tc.scale } ?: defSz))).append(';')
                pcss.append("color:").append(defColor).append(';')
                if (defBold) pcss.append("font-weight:bold;")
                if (defItalic) pcss.append("font-style:italic;")
                val lnSpc = pk("lnSpc")
                val pct = lnSpc?.kid("spcPct")?.num("val")
                val pts = lnSpc?.kid("spcPts")?.num("val")
                when {
                    pts != null -> pcss.append("line-height:").append(cqw(pts / 100.0 * (1 - tc.lnRed))).append(';')
                    else -> pcss.append("line-height:").append(f3((pct ?: 100000.0) / 100000.0 * 1.2 * (1 - tc.lnRed))).append(';')
                }
                if (!first) spacing(pk("spcBef"), tc)?.let { pcss.append("margin-top:").append(it).append(';') }
                spacing(pk("spcAft"), tc)?.let { pcss.append("margin-bottom:").append(it).append(';') }
                val marL = pa("marL")?.toDoubleOrNull() ?: 0.0
                val indent = pa("indent")?.toDoubleOrNull() ?: 0.0
                if (marL != 0.0) pcss.append("padding-left:").append(cqwE(marL)).append(';')
                if (indent != 0.0) pcss.append("text-indent:").append(cqwE(indent)).append(';')

                // Gạch đầu dòng / đánh số
                var bullet: String? = null
                val holder = all.firstOrNull {
                    it.kid("buNone") != null || it.kid("buChar") != null || it.kid("buAutoNum") != null || it.kid("buBlip") != null
                }
                val auto = holder?.kid("buAutoNum")
                if (hasText && holder != null) {
                    holder.kid("buChar")?.let { ch ->
                        val font = all.firstNotNullOfOrNull { it.kid("buFont")?.at("typeface") } ?: ""
                        bullet = mapBullet(ch.at("char") ?: "•", font)
                    }
                    if (auto != null) {
                        val type = auto.at("type") ?: "arabicPeriod"
                        if (numType[lvl] != type) counters[lvl] = (auto.num("startAt")?.toInt() ?: 1) - 1
                        counters[lvl]++
                        numType[lvl] = type
                        bullet = autoNum(type, counters[lvl])
                    }
                    if (holder.kid("buBlip") != null) bullet = "•"
                }
                if (auto == null) numType[lvl] = null
                for (k in lvl + 1 until 9) numType[k] = null

                o.append("<p").append(st(pcss)).append('>')
                val bu = bullet
                if (bu != null) {
                    val bcss = StringBuilder()
                    val buColor = colorOf(pk("buClr")) ?: firstColor
                    if (buColor != null) bcss.append("color:").append(buColor).append(';')
                    firstSz?.let { bcss.append("font-size:").append(cqw(it)).append(';') }
                    pk("buSzPct")?.num("val")?.let { bcss.append("font-size:").append(f3(it / 100000.0)).append("em;") }
                    if (indent < 0) bcss.append("width:").append(cqwE(-indent)).append(';')
                    else bcss.append("margin-right:.35em;")
                    o.append("<span class=\"bu\"").append(st(bcss)).append('>').append(esc(bu)).append("</span>")
                }
                if (hasText) {
                    o.append(runs)
                    if (runs.endsWith("<br>")) o.append("&#8203;")
                } else {
                    o.append("&#8203;")
                }
                o.append("</p>")
                first = false
            }
        }

        private fun spacing(e: Element?, tc: TextCtx): String? {
            if (e == null) return null
            e.kid("spcPts")?.num("val")?.let { return cqw(it / 100.0 * (1 - tc.lnRed)) }
            e.kid("spcPct")?.num("val")?.let { return f3(it / 100000.0 * 1.2 * (1 - tc.lnRed)) + "em" }
            return null
        }

        private fun run(text: String, rPr: Element?, color: String?, sz: Double?, defBold: Boolean, defItalic: Boolean): String {
            val css = StringBuilder()
            if (sz != null) css.append("font-size:").append(cqw(sz)).append(';')
            if (color != null) css.append("color:").append(color).append(';')
            rPr?.at("b")?.let { val on = it == "1" || it == "true"; if (on != defBold) css.append(if (on) "font-weight:bold;" else "font-weight:normal;") }
            rPr?.at("i")?.let { val on = it == "1" || it == "true"; if (on != defItalic) css.append(if (on) "font-style:italic;" else "font-style:normal;") }
            val deco = ArrayList<String>()
            rPr?.at("u")?.let { if (it != "none") deco.add("underline") }
            rPr?.at("strike")?.let { if (it != "noStrike") deco.add("line-through") }
            if (deco.isNotEmpty()) css.append("text-decoration:").append(deco.joinToString(" ")).append(';')
            rPr?.num("baseline")?.let {
                if (it > 0) css.append("vertical-align:super;font-size:70%;") else if (it < 0) css.append("vertical-align:sub;font-size:70%;")
            }
            if (rPr?.at("cap") == "all") css.append("text-transform:uppercase;")
            colorOf(rPr?.kid("highlight"))?.let { css.append("background:").append(it).append(';') }
            rPr?.kid("latin")?.at("typeface")?.let { tf ->
                val low = tf.lowercase()
                if (listOf("times", "georgia", "cambria", "garamond", "serif", "book antiqua", "palatino").any { low.contains(it) }) {
                    css.append("font-family:serif;")
                }
            }
            val t = esc(text).replace("\t", "&emsp;")
            return if (css.isEmpty()) t else "<span" + st(css) + ">" + t + "</span>"
        }

        private fun mapBullet(ch: String, font: String): String {
            val f = font.lowercase()
            return when {
                f.contains("wingdings") -> WINGDINGS[ch] ?: "•"
                f.contains("symbol") && ch == "·" -> "•"
                f.contains("courier") && ch == "o" -> "◦"
                else -> ch
            }
        }

        private fun autoNum(type: String, n: Int): String {
            val body = when {
                type.startsWith("alphaLc") -> letters(n).lowercase()
                type.startsWith("alphaUc") -> letters(n)
                type.startsWith("romanLc") -> roman(n).lowercase()
                type.startsWith("romanUc") -> roman(n)
                else -> n.toString()
            }
            return when {
                type.endsWith("ParenBoth") -> "($body)"
                type.endsWith("ParenR") -> "$body)"
                type.endsWith("Plain") -> body
                else -> "$body."
            }
        }

        private fun letters(v: Int): String {
            if (v <= 0) return v.toString()
            return (('A' + (v - 1) % 26).toString()).repeat((v - 1) / 26 + 1)
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

        // ---------- Đường kẻ, hình tự do ----------

        private fun lineSvg(o: StringBuilder, b: Box, color: String, wPt: Double, dash: String) {
            val x1 = if (b.flipH) 100 else 0
            val y1 = if (b.flipV) 100 else 0
            val css = StringBuilder("stroke:").append(color).append(";stroke-width:").append(cqw(max(wPt, 0.5)))
                .append(";vector-effect:non-scaling-stroke;")
            if (dash == "dashed") css.append("stroke-dasharray:6 4;") else if (dash == "dotted") css.append("stroke-dasharray:1.5 3;")
            o.append("<svg class=\"ln\" viewBox=\"0 0 100 100\" preserveAspectRatio=\"none\"><line x1=\"")
                .append(x1).append("\" y1=\"").append(y1).append("\" x2=\"").append(100 - x1).append("\" y2=\"")
                .append(100 - y1).append('"').append(st(css)).append("/></svg>")
        }

        private fun custSvg(o: StringBuilder, cust: Element, b: Box, fill: Paint?, line: String?, lineW: Double) {
            val paths = cust.kid("pathLst")?.kids("path") ?: return
            for (path in paths) {
                val pw = path.num("w")?.takeIf { it > 0 } ?: b.extW
                val ph = path.num("h")?.takeIf { it > 0 } ?: b.extH
                if (pw <= 0 || ph <= 0) continue
                val d = pathData(path) ?: continue
                val noFill = path.at("fill") == "none"
                val noStroke = path.at("stroke").let { it == "0" || it == "false" }
                val fc = if (noFill) null else fill?.solid
                val css = StringBuilder("fill:").append(fc ?: "none").append(';')
                if (line != null && !noStroke) {
                    css.append("stroke:").append(line).append(";stroke-width:").append(cqw(max(lineW, 0.5)))
                        .append(";vector-effect:non-scaling-stroke;")
                }
                val tr = if (b.flipH || b.flipV) " transform=\"translate(${if (b.flipH) f3(pw) else "0"} ${if (b.flipV) f3(ph) else "0"}) scale(${if (b.flipH) -1 else 1} ${if (b.flipV) -1 else 1})\"" else ""
                o.append("<svg class=\"cg\" viewBox=\"0 0 ").append(f3(pw)).append(' ').append(f3(ph))
                    .append("\" preserveAspectRatio=\"none\"><path d=\"").append(d).append('"').append(tr)
                    .append(st(css)).append("/></svg>")
            }
        }

        private fun pathData(path: Element): String? {
            val d = StringBuilder()
            var cx0 = 0.0
            var cy0 = 0.0
            fun pt(e: Element?): Pair<Double, Double>? {
                val x = e?.num("x") ?: return null
                val y = e.num("y") ?: return null
                return Pair(x, y)
            }
            for (c in path.elems()) {
                when (lnm(c)) {
                    "moveTo", "lnTo" -> {
                        val p = pt(c.kid("pt")) ?: return null
                        d.append(if (lnm(c) == "moveTo") "M" else "L").append(f3(p.first)).append(' ').append(f3(p.second)).append(' ')
                        cx0 = p.first; cy0 = p.second
                    }
                    "cubicBezTo", "quadBezTo" -> {
                        val ps = c.kids("pt").map { pt(it) ?: return null }
                        if (ps.isEmpty()) return null
                        d.append(if (lnm(c) == "cubicBezTo") "C" else "Q")
                        ps.forEach { d.append(f3(it.first)).append(' ').append(f3(it.second)).append(' ') }
                        cx0 = ps.last().first; cy0 = ps.last().second
                    }
                    "arcTo" -> {
                        val wr = c.num("wR") ?: return null
                        val hr = c.num("hR") ?: return null
                        val stA = (c.num("stAng") ?: 0.0) / 60000.0 * PI / 180
                        var sw = (c.num("swAng") ?: 0.0) / 60000.0 * PI / 180
                        if (wr <= 0 || hr <= 0 || sw == 0.0) continue
                        fun par(a: Double) = atan2(wr * sin(a), hr * cos(a))
                        val t1 = par(stA)
                        val ccx = cx0 - wr * cos(t1)
                        val ccy = cy0 - hr * sin(t1)
                        val pieces = if (abs(sw) > 1.9 * PI) 2 else 1
                        sw /= pieces
                        var a = stA
                        repeat(pieces) {
                            val t2 = par(a + sw)
                            val ex = ccx + wr * cos(t2)
                            val ey = ccy + hr * sin(t2)
                            d.append("A").append(f3(wr)).append(' ').append(f3(hr)).append(" 0 ")
                                .append(if (abs(sw) > PI) 1 else 0).append(' ').append(if (sw > 0) 1 else 0).append(' ')
                                .append(f3(ex)).append(' ').append(f3(ey)).append(' ')
                            cx0 = ex; cy0 = ey
                            a += sw
                        }
                    }
                    "close" -> d.append("Z ")
                }
            }
            return if (d.isEmpty()) null else d.toString().trim()
        }

        // ---------- Ảnh ----------

        private fun picture(out: StringBuilder, p: Part, e: Element, xf: Xf, level: Int, ph: Ph?) {
            val chain = chainOf(e, ph, level)
            val box = boxFor(e, chain, xf) ?: return
            val blipFill = e.kid("blipFill")
            val url = imageUrl(blipFill?.kid("blip")?.rid("embed"), p) ?: return
            val spPr = e.kid("spPr")
            val css = StringBuilder(pos(box))
            when (spPr?.kid("prstGeom")?.at("prst")) {
                "ellipse" -> css.append("border-radius:50%;")
                "roundRect" -> css.append("border-radius:").append(cqwE(min(box.extW, box.extH) * 0.16667)).append(';')
            }
            val ln = spPr?.kid("ln")
            if (ln != null && ln.kid("noFill") == null) {
                colorOf(ln.kid("solidFill"))?.let { c ->
                    css.append("outline:").append(cqw(max((ln.num("w") ?: 9525.0) / EMU_PT, 0.4))).append(" solid ").append(c)
                        .append(";outline-offset:0;")
                }
            }
            val img = StringBuilder()
            val src = blipFill?.kid("srcRect")
            if (src != null) {
                val l = (src.num("l") ?: 0.0) / 100000.0
                val t = (src.num("t") ?: 0.0) / 100000.0
                val r = (src.num("r") ?: 0.0) / 100000.0
                val b = (src.num("b") ?: 0.0) / 100000.0
                val w = 1 - l - r
                val h = 1 - t - b
                if (w > 0.01 && h > 0.01) {
                    img.append("width:").append(f3(100 / w)).append("%;height:").append(f3(100 / h))
                        .append("%;left:").append(f3(-l / w * 100)).append("%;top:").append(f3(-t / h * 100)).append("%;")
                }
            }
            if (box.flipH || box.flipV) {
                img.append("transform:scale(").append(if (box.flipH) "-1" else "1").append(',')
                    .append(if (box.flipV) "-1" else "1").append(");")
            }
            out.append("<div class=\"sp pic\"").append(st(css)).append("><img src=\"").append(esc(url))
                .append("\" alt=\"\"").append(st(img)).append("></div>")
        }

        // ---------- Bảng, biểu đồ, SmartArt ----------

        private fun frame(out: StringBuilder, p: Part, e: Element, xf: Xf, level: Int) {
            val box = boxOf(e.kid("xfrm"), xf) ?: return
            val gd = e.dig("graphic", "graphicData") ?: return
            val uri = gd.at("uri") ?: ""
            when {
                gd.kid("tbl") != null -> table(out, gd.kid("tbl")!!, box)
                uri.contains("diagram") -> diagram(out, p, gd, box, xf, level)
                uri.contains("chart") -> chart(out, p, gd, box)
                else -> {
                    // Đối tượng nhúng (OLE...): dùng ảnh xem trước nếu có
                    val pic = e.find("pic")
                    if (pic != null) picture(out, p, pic, Xf(1.0, 1.0, 0.0, 0.0), SLIDE, null)
                }
            }
        }

        private fun diagram(out: StringBuilder, p: Part, gd: Element, box: Box, xf: Xf, level: Int) {
            val relIds = gd.kid("relIds")
            val dm = p.target(relIds?.rid("dm"))?.let { part(it) }
            val drawingRid = dm?.root?.find("dataModelExt")?.at("relId")
            val drawingPath = p.target(drawingRid)
                ?: p.rels.filter { !it.external && it.type.endsWith("/diagramDrawing") }.singleOrNull()?.target
            val drawing = drawingPath?.let { part(it) }
            val spTree = drawing?.root?.find("spTree")
            if (drawing == null || spTree == null) {
                out.append("<div class=\"sp chart\"").append(st(pos(box))).append(">SmartArt</div>")
                return
            }
            val inner = Xf(xf.sx, xf.sy, box.x, box.y)
            tree(out, drawing, spTree, inner, SLIDE)
        }

        private fun table(out: StringBuilder, tbl: Element, box: Box) {
            val grid = tbl.kid("tblGrid")?.kids("gridCol")?.map { it.num("w") ?: 0.0 } ?: return
            val total = grid.sum()
            if (total <= 0) return
            val tblPr = tbl.kid("tblPr")
            val styleId = tblPr?.kid("tableStyleId")?.textContent?.trim()
            val tStyle = tableStyles?.kids("tblStyle")?.firstOrNull { it.at("styleId") == styleId }
            val firstRow = tblPr?.flag("firstRow") == true
            val lastRow = tblPr?.flag("lastRow") == true
            val firstCol = tblPr?.flag("firstCol") == true
            val lastCol = tblPr?.flag("lastCol") == true
            val bandRow = tblPr?.flag("bandRow") == true
            val bandCol = tblPr?.flag("bandCol") == true
            val accent = "#" + (schemeHex("accent1") ?: "4472C4")
            val grid0 = styleId == "{5940675A-B579-460E-94D1-54222C63F5DA}"
            val noStyle = styleId == null || styleId == "{2D5ABB26-0587-4C30-8999-92F81FD0307C}"
            val glob = listOfNotNull(defaultTextStyle, master?.root?.kid("txStyles")?.kid("otherStyle"))
            val rows = tbl.kids("tr")
            val nRows = rows.size
            val nCols = grid.size

            val o = StringBuilder()
            o.append("<div class=\"sp\"").append(st(pos(box))).append("><table class=\"tb\"><colgroup>")
            grid.forEach { o.append("<col").append(st("width:" + f3(it * 100 / total) + "%")).append('>') }
            o.append("</colgroup>")
            rows.forEachIndexed { ri, tr ->
                o.append("<tr").append(st("height:" + cqwE(tr.num("h") ?: 0.0))).append('>')
                var ci = 0
                for (tc in tr.kids("tc")) {
                    val span = tc.num("gridSpan")?.toInt()?.coerceAtLeast(1) ?: 1
                    val col = ci
                    ci += if (tc.flag("hMerge") == true) 1 else span
                    if (tc.flag("hMerge") == true || tc.flag("vMerge") == true) continue
                    val tcPr = tc.kid("tcPr")
                    val attrs = StringBuilder()
                    if (span > 1) attrs.append(" colspan=\"").append(span).append('"')
                    tc.num("rowSpan")?.toInt()?.takeIf { it > 1 }?.let { attrs.append(" rowspan=\"").append(it).append('"') }

                    // Các phần kiểu bảng áp cho ô này, ưu tiên tăng dần
                    val isHead = firstRow && ri == 0
                    val isFoot = lastRow && ri == nRows - 1
                    val parts = ArrayList<Element>()
                    if (tStyle != null) {
                        tStyle.kid("wholeTbl")?.let { parts.add(it) }
                        if (bandRow && !isHead && !isFoot) {
                            val bi = ri - (if (firstRow) 1 else 0)
                            tStyle.kid(if (bi % 2 == 0) "band1H" else "band2H")?.let { parts.add(it) }
                        }
                        if (bandCol) tStyle.kid(if (col % 2 == 0) "band1V" else "band2V")?.let { parts.add(it) }
                        if (firstCol && col == 0) tStyle.kid("firstCol")?.let { parts.add(it) }
                        if (lastCol && col + span >= nCols) tStyle.kid("lastCol")?.let { parts.add(it) }
                        if (isFoot) tStyle.kid("lastRow")?.let { parts.add(it) }
                        if (isHead) tStyle.kid("firstRow")?.let { parts.add(it) }
                    }

                    val css = StringBuilder()
                    var bg = tcPr?.let { paint(it, Part("", it, emptyList())) }
                    if (bg == null) {
                        for (pt in parts.asReversed()) {
                            val ts = pt.kid("tcStyle") ?: continue
                            bg = ts.kid("fill")?.let { paint(it, Part("", it, emptyList())) } ?: refFill(ts.kid("fillRef"))
                            if (bg != null) break
                        }
                    }
                    if (bg == null && tStyle == null && !noStyle && !grid0) {
                        val bi = ri - (if (firstRow) 1 else 0)
                        bg = when {
                            isHead -> Paint(accent, accent)
                            bandRow && bi % 2 == 0 -> mixWhite(accent, 0.28).let { Paint(it, it) }
                            else -> mixWhite(accent, 0.14).let { Paint(it, it) }
                        }
                    }
                    if (bg != null && bg !== NO_PAINT) css.append("background:").append(bg.css).append(';')

                    for ((side, name) in listOf("left" to "lnL", "right" to "lnR", "top" to "lnT", "bottom" to "lnB")) {
                        val ln = tcPr?.kid(name)
                        val lnFill = ln?.kid("solidFill")
                        val lnW = ln?.num("w") ?: 12700.0
                        var bc: String? = when {
                            ln?.kid("noFill") != null -> "none"
                            lnFill != null -> colorOf(lnFill)?.let { cqw(max(lnW / EMU_PT, 0.4)) + " solid " + it }
                            else -> null
                        }
                        if (bc == null && tStyle != null) {
                            val edge = when (side) {
                                "left" -> if (col == 0) "left" else "insideV"
                                "right" -> if (col + span >= nCols) "right" else "insideV"
                                "top" -> if (ri == 0) "top" else "insideH"
                                else -> if (ri == nRows - 1) "bottom" else "insideH"
                            }
                            for (pt in parts.asReversed()) {
                                val b = pt.dig("tcStyle", "tcBdr")?.let { it.kid(edge) ?: it.kid(side) } ?: continue
                                val l = b.kid("ln")
                                bc = when {
                                    l == null -> refLine(b.kid("lnRef"))?.let { cqw(max(it.second, 0.4)) + " solid " + it.first }
                                    l.kid("noFill") != null -> "none"
                                    else -> colorOf(l.kid("solidFill"))?.let { cqw(max((l.num("w") ?: 12700.0) / EMU_PT, 0.4)) + " solid " + it }
                                }
                                if (bc != null) break
                            }
                        }
                        if (bc == null && tStyle == null) {
                            bc = when {
                                grid0 -> "0.1cqw solid #000000"
                                noStyle -> null
                                else -> "0.12cqw solid #FFFFFF"
                            }
                        }
                        if (bc != null) css.append("border-").append(side).append(':').append(bc).append(';')
                    }
                    css.append("padding:").append(cqwE(tcPr?.num("marT") ?: 45720.0)).append(' ')
                        .append(cqwE(tcPr?.num("marR") ?: 91440.0)).append(' ')
                        .append(cqwE(tcPr?.num("marB") ?: 45720.0)).append(' ')
                        .append(cqwE(tcPr?.num("marL") ?: 91440.0)).append(';')
                    css.append("vertical-align:").append(
                        when (tcPr?.at("anchor")) { "ctr" -> "middle"; "b" -> "bottom"; else -> "top" }
                    ).append(';')

                    var txColor: String? = null
                    var txBold = false
                    for (pt in parts) {
                        val tx = pt.kid("tcTxStyle") ?: continue
                        colorOf(tx)?.let { txColor = it }
                        tx.at("b")?.let { txBold = it == "on" }
                    }
                    if (tStyle == null && !noStyle && !grid0 && isHead) { txColor = "#FFFFFF"; txBold = true }

                    o.append("<td").append(attrs).append(st(css)).append('>')
                    tc.kid("txBody")?.let { tb ->
                        paragraphs(o, tb, TextCtx(listOfNotNull(tb.kid("lstStyle")), glob, txColor, txBold, 1.0, 0.0))
                    }
                    o.append("</td>")
                }
                o.append("</tr>")
            }
            o.append("</table></div>")
            out.append(o)
        }

        // ---------- Biểu đồ (vẽ SVG từ số liệu lưu sẵn trong file) ----------

        private class Ser(val el: Element, val name: String, val cats: List<String?>, val vals: List<Double?>, val color: String)

        private fun cacheValues(el: Element?): List<String?> {
            if (el == null) return emptyList()
            val c = listOf("strCache", "numCache", "strLit", "numLit", "multiLvlStrCache")
                .firstNotNullOfOrNull { el.find(it) } ?: return emptyList()
            val holder = if (lnm(c) == "multiLvlStrCache") (c.kid("lvl") ?: c) else c
            val pts = holder.kids("pt")
            val count = c.kid("ptCount")?.num("val")?.toInt() ?: 0
            val n = max(count, (pts.maxOfOrNull { it.num("idx")?.toInt() ?: 0 } ?: -1) + 1).coerceIn(0, 2000)
            val out = arrayOfNulls<String>(n)
            for (pt in pts) {
                val i = pt.num("idx")?.toInt() ?: continue
                if (i in out.indices) out[i] = pt.kid("v")?.textContent
            }
            return out.toList()
        }

        private fun accent(i: Int): String {
            val hex = schemeHex("accent${i % 6 + 1}") ?: listOf("4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47")[i % 6]
            if (i < 6) return "#$hex"
            val v = hex.toLongOrNull(16) ?: return "#$hex"
            fun c(x: Long) = (x * 0.68).toInt()
            return String.format(Locale.US, "#%02X%02X%02X", c((v shr 16) and 255), c((v shr 8) and 255), c(v and 255))
        }

        private fun chart(out: StringBuilder, p: Part, gd: Element, box: Box) {
            val cp = p.target(gd.kid("chart")?.rid("id"))?.let { part(it) }
            val chart = cp?.root?.kid("chart")
            val plot = chart?.kid("plotArea")
            val svg = if (cp != null && chart != null && plot != null) {
                try {
                    chartSvg(cp.root, chart, plot, box)
                } catch (e: Exception) {
                    null
                }
            } else null
            if (svg == null) {
                out.append("<div class=\"sp chart\"").append(st(pos(box))).append(">Biểu đồ</div>")
            } else {
                out.append("<div class=\"sp\"").append(st(pos(box))).append('>').append(svg).append("</div>")
            }
        }

        private fun niceStep(raw: Double): Double {
            if (raw <= 0 || raw.isNaN()) return 1.0
            val p = 10.0.pow(floor(log10(raw)))
            val f = raw / p
            return p * when {
                f <= 1.0 -> 1.0
                f <= 2.0 -> 2.0
                f <= 2.5 -> 2.5
                f <= 5.0 -> 5.0
                else -> 10.0
            }
        }

        private fun fmtNum(v: Double, pct: Boolean = false): String {
            val nf = java.text.NumberFormat.getNumberInstance()
            nf.maximumFractionDigits = if (abs(v) >= 100) 0 else 2
            return nf.format(v) + if (pct) "%" else ""
        }

        private fun svgText(sb: StringBuilder, x: Double, y: Double, t: String, size: Double, anchor: String, color: String, bold: Boolean = false) {
            sb.append("<text x=\"").append(f3(x)).append("\" y=\"").append(f3(y)).append("\" font-size=\"").append(f3(size))
                .append("\" text-anchor=\"").append(anchor).append("\" fill=\"").append(color).append('"')
            if (bold) sb.append(" font-weight=\"bold\"")
            sb.append('>').append(esc(t)).append("</text>")
        }

        private fun svgRect(sb: StringBuilder, x: Double, y: Double, w: Double, h: Double, fill: String) {
            sb.append("<rect x=\"").append(f3(x)).append("\" y=\"").append(f3(y)).append("\" width=\"").append(f3(max(w, 0.0)))
                .append("\" height=\"").append(f3(max(h, 0.0))).append("\" fill=\"").append(fill).append("\"/>")
        }

        private fun clip(t: String, maxChars: Int): String =
            if (t.length <= maxChars || maxChars < 2) t else t.take(max(1, maxChars - 1)) + "…"

        private fun chartSvg(space: Element, chart: Element, plot: Element, box: Box): String? {
            val types = plot.elems().filter { lnm(it).endsWith("Chart") }
            val main = types.firstOrNull() ?: return null
            val kind = lnm(main).removeSuffix("Chart").removeSuffix("3D")
            if (kind !in setOf("bar", "line", "area", "pie", "doughnut", "scatter", "ofPie")) return null
            val series = ArrayList<Ser>()
            for (t in types) {
                if (lnm(t).removeSuffix("Chart").removeSuffix("3D") != kind) continue
                for (ser in t.kids("ser")) {
                    val i = series.size
                    val name = cacheValues(ser.kid("tx")).firstOrNull() ?: ser.dig("tx", "v")?.textContent ?: "Chuỗi ${i + 1}"
                    val cats = cacheValues(ser.kid("cat") ?: ser.kid("xVal"))
                    val vals = cacheValues(ser.kid("val") ?: ser.kid("yVal")).map { it?.trim()?.toDoubleOrNull() }
                    val col = colorOf(ser.dig("spPr", "solidFill")) ?: colorOf(ser.dig("spPr", "ln", "solidFill")) ?: accent(i)
                    series.add(Ser(ser, name, cats, vals, col))
                }
            }
            if (series.isEmpty()) return null
            val isPie = kind == "pie" || kind == "doughnut" || kind == "ofPie"

            val w = 1000.0
            val h = w * box.h / box.w
            if (h <= 10) return null
            val upt = 12700.0 * w / box.w
            val baseSz = (space.kid("txPr")?.find("defRPr")?.num("sz") ?: 1200.0) / 100.0
            val fs = (baseSz * upt).coerceIn(h * 0.03, h * 0.085)
            val ink = "#404040"
            val sb = StringBuilder()
            sb.append("<svg class=\"cg\" viewBox=\"0 0 ").append(f3(w)).append(' ').append(f3(h))
                .append("\" font-family=\"sans-serif\">")
            colorOf(space.dig("spPr", "solidFill"))?.let { svgRect(sb, 0.0, 0.0, w, h, it) }

            var top = fs * 0.5
            var bottom = h - fs * 0.5
            var left = fs * 0.5
            var right = w - fs * 0.5

            // Tiêu đề
            val titleEl = chart.kid("title")
            val autoDel = chart.kid("autoTitleDeleted")?.num("val") == 1.0
            val rich = titleEl?.find("rich")?.kids("p")?.joinToString(" ") { p ->
                p.elems().filter { lnm(it) == "r" || lnm(it) == "fld" }.joinToString("") { it.kid("t")?.textContent ?: "" }
            }?.trim()
            val title = rich?.ifEmpty { null } ?: if ((titleEl != null || !autoDel) && series.size == 1) series[0].name else null
            if (title != null) {
                svgText(sb, w / 2, top + fs * 1.2, clip(title, (w / (fs * 0.62)).toInt()), fs * 1.25, "middle", ink, true)
                top += fs * 2.0
            }

            // Chú thích
            val cats0 = series.maxByOrNull { it.cats.size }?.cats ?: emptyList()
            val nCat = max(1, series.maxOf { max(it.vals.size, it.cats.size) })
            val vary = main.kid("varyColors")?.num("val") == 1.0
            fun pointColor(s: Ser, i: Int): String {
                for (d in s.el.kids("dPt")) {
                    if (d.kid("idx")?.num("val")?.toInt() == i) colorOf(d.dig("spPr", "solidFill"))?.let { return it }
                }
                return if (isPie || (vary && series.size == 1)) accent(i) else s.color
            }
            val legend = chart.kid("legend")
            val legendPos = legend?.kid("legendPos")?.at("val") ?: "r"
            val entries: List<Pair<String, String>> = when {
                legend == null -> emptyList()
                isPie -> (0 until nCat).map { i -> Pair(cats0.getOrNull(i) ?: "${i + 1}", pointColor(series[0], i)) }
                else -> series.map { Pair(it.name, it.color) }
            }
            if (entries.isNotEmpty()) {
                if (legendPos == "r" || legendPos == "l") {
                    val lw = min(w * 0.32, entries.maxOf { it.first.length } * fs * 0.56 + fs * 2.0)
                    val x0 = if (legendPos == "r") w - lw else left
                    var y = (top + bottom) / 2 - entries.size * fs * 1.5 / 2 + fs
                    for ((name, col) in entries) {
                        svgRect(sb, x0 + fs * 0.3, y - fs * 0.8, fs * 0.8, fs * 0.8, col)
                        svgText(sb, x0 + fs * 1.4, y, clip(name, ((lw - fs * 1.6) / (fs * 0.56)).toInt()), fs, "start", ink)
                        y += fs * 1.5
                    }
                    if (legendPos == "r") right = x0 - fs * 0.4 else left = x0 + lw
                } else {
                    val rowsL = ArrayList<ArrayList<Pair<String, String>>>()
                    var cur = ArrayList<Pair<String, String>>()
                    var curW = 0.0
                    for (e in entries) {
                        val iw = e.first.length * fs * 0.56 + fs * 2.2
                        if (curW + iw > w - fs && cur.isNotEmpty()) { rowsL.add(cur); cur = ArrayList(); curW = 0.0 }
                        cur.add(e); curW += iw
                    }
                    if (cur.isNotEmpty()) rowsL.add(cur)
                    val lh = rowsL.size * fs * 1.5
                    var y = if (legendPos == "t") top + fs * 1.1 else bottom - lh + fs * 1.1
                    for (r in rowsL) {
                        val rw = r.sumOf { it.first.length * fs * 0.56 + fs * 2.2 }
                        var x = (w - rw) / 2
                        for ((name, col) in r) {
                            svgRect(sb, x, y - fs * 0.8, fs * 0.8, fs * 0.8, col)
                            svgText(sb, x + fs * 1.1, y, name, fs, "start", ink)
                            x += name.length * fs * 0.56 + fs * 2.2
                        }
                        y += fs * 1.5
                    }
                    if (legendPos == "t") top += lh else bottom -= lh
                }
            }

            fun showLbl(s: Ser, n: String): Boolean =
                (s.el.kid("dLbls") ?: main.kid("dLbls"))?.kid(n)?.num("val") == 1.0

            if (isPie) {
                val s = series[0]
                val vals = (0 until nCat).map { max(s.vals.getOrNull(it) ?: 0.0, 0.0) }
                val tot = vals.sum()
                if (tot <= 0) { sb.append("</svg>"); return sb.toString() }
                val cxp = (left + right) / 2
                val cyp = (top + bottom) / 2
                val r = min(right - left, bottom - top) / 2 * 0.94
                val hole = if (kind == "doughnut") (main.kid("holeSize")?.num("val") ?: 50.0) / 100.0 * r else 0.0
                var a = ((main.kid("firstSliceAng")?.num("val") ?: 0.0) - 90.0) * PI / 180
                for (i in vals.indices) {
                    val sw = vals[i] / tot * 2 * PI
                    if (sw <= 0) continue
                    val col = pointColor(s, i)
                    if (sw >= 2 * PI - 1e-6) {
                        sb.append("<circle cx=\"").append(f3(cxp)).append("\" cy=\"").append(f3(cyp)).append("\" r=\"")
                            .append(f3(r)).append("\" fill=\"").append(col).append("\"/>")
                    } else {
                        val a2 = a + sw
                        val large = if (sw > PI) 1 else 0
                        val d = StringBuilder()
                        d.append("M").append(f3(cxp + r * cos(a))).append(' ').append(f3(cyp + r * sin(a)))
                            .append(" A").append(f3(r)).append(' ').append(f3(r)).append(" 0 ").append(large).append(" 1 ")
                            .append(f3(cxp + r * cos(a2))).append(' ').append(f3(cyp + r * sin(a2)))
                        if (hole > 0) {
                            d.append(" L").append(f3(cxp + hole * cos(a2))).append(' ').append(f3(cyp + hole * sin(a2)))
                                .append(" A").append(f3(hole)).append(' ').append(f3(hole)).append(" 0 ").append(large).append(" 0 ")
                                .append(f3(cxp + hole * cos(a))).append(' ').append(f3(cyp + hole * sin(a))).append(" Z")
                        } else {
                            d.append(" L").append(f3(cxp)).append(' ').append(f3(cyp)).append(" Z")
                        }
                        sb.append("<path d=\"").append(d).append("\" fill=\"").append(col)
                            .append("\" stroke=\"#FFFFFF\" stroke-width=\"").append(f3(fs * 0.08)).append("\"/>")
                    }
                    val pct = showLbl(s, "showPercent")
                    val sv = showLbl(s, "showVal")
                    if (pct || sv) {
                        val mid = a + sw / 2
                        val rr = if (hole > 0) (r + hole) / 2 else r * 0.64
                        val label = if (pct) fmtNum(vals[i] / tot * 100, true) else fmtNum(vals[i])
                        svgText(sb, cxp + rr * cos(mid), cyp + rr * sin(mid) + fs * 0.35, label, fs * 0.95, "middle", "#FFFFFF", true)
                    }
                    a += sw
                }
                sb.append("</svg>")
                return sb.toString()
            }

            // Biểu đồ có trục: cột/thanh, đường, vùng
            val horizontal = kind == "bar" && main.kid("barDir")?.at("val") == "bar"
            val grouping = main.kid("grouping")?.at("val") ?: "clustered"
            val stacked = (kind == "bar" || kind == "area") && (grouping == "stacked" || grouping == "percentStacked")
            val percent = grouping == "percentStacked" && stacked
            fun valueAt(s: Ser, c: Int): Double {
                val v = s.vals.getOrNull(c) ?: 0.0
                if (!percent) return v
                val tot = series.sumOf { abs(it.vals.getOrNull(c) ?: 0.0) }
                return if (tot > 0) v / tot * 100 else 0.0
            }
            var vmin = 0.0
            var vmax = 0.0
            for (c in 0 until nCat) {
                if (stacked) {
                    var pos = 0.0
                    var neg = 0.0
                    for (s in series) { val v = valueAt(s, c); if (v >= 0) pos += v else neg += v }
                    vmax = max(vmax, pos); vmin = min(vmin, neg)
                } else {
                    for (s in series) s.vals.getOrNull(c)?.let { vmax = max(vmax, it); vmin = min(vmin, it) }
                }
            }
            val valAx = plot.kid("valAx")
            if (vmax == vmin) vmax = vmin + 1
            val step = niceStep((vmax - vmin) / 5)
            vmax = ceil(vmax / step - 1e-9) * step
            vmin = floor(vmin / step + 1e-9) * step
            valAx?.dig("scaling", "max")?.num("val")?.let { vmax = it }
            valAx?.dig("scaling", "min")?.num("val")?.let { vmin = it }
            if (vmax <= vmin) vmax = vmin + step
            val ticks = ArrayList<Double>()
            var tv = vmin
            while (tv <= vmax + step * 1e-6 && ticks.size < 30) { ticks.add(tv); tv += step }
            val tickLabels = ticks.map { fmtNum(it, percent) }
            val valLabW = (tickLabels.maxOfOrNull { it.length } ?: 1) * fs * 0.56 + fs * 0.6
            val catLabels = (0 until nCat).map { cats0.getOrNull(it) ?: "${it + 1}" }

            val pl: Double
            val pr = right - fs * 0.3
            val pt = top + fs * 0.5
            val pb: Double
            if (!horizontal) {
                pl = left + valLabW
                pb = bottom - fs * 1.7
            } else {
                pl = left + min((catLabels.maxOfOrNull { it.length } ?: 1) * fs * 0.56 + fs * 0.6, (right - left) * 0.35)
                pb = bottom - fs * 1.7
            }
            if (pr - pl < 20 || pb - pt < 20) return null
            val catLen = if (horizontal) pb - pt else pr - pl
            val catW = catLen / nCat
            fun vpos(v: Double): Double {
                val k = (v - vmin) / (vmax - vmin)
                return if (horizontal) pl + k * (pr - pl) else pb - k * (pb - pt)
            }
            fun cpos(c: Double): Double = if (horizontal) pb - c * catW else pl + c * catW

            // Lưới + nhãn trục giá trị
            for ((i, v) in ticks.withIndex()) {
                val q = vpos(v)
                if (horizontal) {
                    sb.append("<line x1=\"").append(f3(q)).append("\" y1=\"").append(f3(pt)).append("\" x2=\"").append(f3(q))
                        .append("\" y2=\"").append(f3(pb)).append("\" stroke=\"#D9D9D9\" stroke-width=\"").append(f3(fs * 0.06)).append("\"/>")
                    svgText(sb, q, pb + fs * 1.3, tickLabels[i], fs * 0.9, "middle", ink)
                } else {
                    sb.append("<line x1=\"").append(f3(pl)).append("\" y1=\"").append(f3(q)).append("\" x2=\"").append(f3(pr))
                        .append("\" y2=\"").append(f3(q)).append("\" stroke=\"#D9D9D9\" stroke-width=\"").append(f3(fs * 0.06)).append("\"/>")
                    svgText(sb, pl - fs * 0.35, q + fs * 0.32, tickLabels[i], fs * 0.9, "end", ink)
                }
            }
            // Nhãn danh mục
            val maxChars = if (horizontal) ((pl - left - fs * 0.4) / (fs * 0.5)).toInt() else (catW / (fs * 0.5)).toInt()
            val catFs = if (!horizontal && catLabels.maxOf { it.length } * fs * 0.5 > catW) fs * 0.8 else fs * 0.9
            for (c in 0 until nCat) {
                val m = cpos(c + 0.5)
                val t = clip(catLabels[c], max(maxChars, 3))
                if (horizontal) svgText(sb, pl - fs * 0.35, m + fs * 0.32, t, catFs, "end", ink)
                else svgText(sb, m, pb + fs * 1.3, t, catFs, "middle", ink)
            }

            val zero = vpos(max(vmin, min(0.0, vmax)))
            if (kind == "bar") {
                val gap = main.kid("gapWidth")?.num("val") ?: 150.0
                val groupW = catW * 100.0 / (100.0 + gap)
                val off = (catW - groupW) / 2
                val bw = if (stacked) groupW else groupW / series.size
                for (c in 0 until nCat) {
                    var posAcc = 0.0
                    var negAcc = 0.0
                    for ((si, s) in series.withIndex()) {
                        val raw = s.vals.getOrNull(c) ?: continue
                        val v = valueAt(s, c)
                        val v0: Double
                        val v1: Double
                        if (stacked) {
                            if (v >= 0) { v0 = posAcc; posAcc += v; v1 = posAcc } else { v0 = negAcc; negAcc += v; v1 = negAcc }
                        } else { v0 = 0.0; v1 = v }
                        val a0 = c * catW + off + (if (stacked) 0.0 else si * bw)
                        val col = pointColor(s, c)
                        val q0 = vpos(max(vmin, min(vmax, v0)))
                        val q1 = vpos(max(vmin, min(vmax, v1)))
                        if (horizontal) {
                            val yTop = cpos((a0 + bw) / catW)
                            svgRect(sb, min(q0, q1), yTop, abs(q1 - q0), bw, col)
                            if (showLbl(s, "showVal")) svgText(sb, max(q0, q1) + fs * 0.3, yTop + bw / 2 + fs * 0.3, fmtNum(raw), fs * 0.8, "start", ink)
                        } else {
                            val x = pl + a0
                            svgRect(sb, x, min(q0, q1), bw, abs(q1 - q0), col)
                            if (showLbl(s, "showVal")) svgText(sb, x + bw / 2, min(q0, q1) - fs * 0.3, fmtNum(raw), fs * 0.8, "middle", ink)
                        }
                    }
                }
            } else {
                val acc = DoubleArray(nCat)
                for (s in series) {
                    val ptsL = ArrayList<Pair<Double, Double>>()
                    for (c in 0 until nCat) {
                        val v = s.vals.getOrNull(c) ?: continue
                        val y = if (stacked) { acc[c] += valueAt(s, c); acc[c] } else v
                        ptsL.add(Pair(cpos(c + 0.5), vpos(y)))
                    }
                    if (ptsL.isEmpty()) continue
                    val line = ptsL.joinToString(" ") { f3(it.first) + "," + f3(it.second) }
                    if (kind == "area") {
                        sb.append("<polygon points=\"").append(f3(ptsL.first().first)).append(',').append(f3(zero)).append(' ')
                            .append(line).append(' ').append(f3(ptsL.last().first)).append(',').append(f3(zero))
                            .append("\" fill=\"").append(s.color).append("\" fill-opacity=\"0.85\"/>")
                    } else {
                        val lineCol = colorOf(s.el.dig("spPr", "ln", "solidFill")) ?: s.color
                        val noLine = s.el.dig("spPr", "ln")?.kid("noFill") != null
                        if (!noLine) {
                            sb.append("<polyline points=\"").append(line).append("\" fill=\"none\" stroke=\"").append(lineCol)
                                .append("\" stroke-width=\"").append(f3(fs * 0.2)).append("\" stroke-linejoin=\"round\"/>")
                        }
                        val sym = s.el.dig("marker", "symbol")?.at("val") ?: main.dig("marker")?.at("val")
                        if (sym != "none" && (sym != null || kind == "scatter" || noLine)) {
                            for ((x, y) in ptsL) {
                                sb.append("<circle cx=\"").append(f3(x)).append("\" cy=\"").append(f3(y)).append("\" r=\"")
                                    .append(f3(fs * 0.26)).append("\" fill=\"").append(lineCol).append("\"/>")
                            }
                        }
                        if (showLbl(s, "showVal")) {
                            var k = 0
                            for (c in 0 until nCat) {
                                val v = s.vals.getOrNull(c) ?: continue
                                val (x, y) = ptsL[k++]
                                svgText(sb, x, y - fs * 0.5, fmtNum(v), fs * 0.8, "middle", ink)
                            }
                        }
                    }
                }
            }
            // Trục gốc
            if (horizontal) {
                sb.append("<line x1=\"").append(f3(zero)).append("\" y1=\"").append(f3(pt)).append("\" x2=\"").append(f3(zero))
                    .append("\" y2=\"").append(f3(pb)).append("\" stroke=\"#A6A6A6\" stroke-width=\"").append(f3(fs * 0.08)).append("\"/>")
            } else {
                sb.append("<line x1=\"").append(f3(pl)).append("\" y1=\"").append(f3(zero)).append("\" x2=\"").append(f3(pr))
                    .append("\" y2=\"").append(f3(zero)).append("\" stroke=\"#A6A6A6\" stroke-width=\"").append(f3(fs * 0.08)).append("\"/>")
            }
            sb.append("</svg>")
            return sb.toString()
        }
    }
}
