package vn.trinhdoc.app

import org.json.JSONArray
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Ghi các đoạn văn đã sửa trở lại file Word (.docx).
 *
 * Danh sách thao tác (theo thứ tự trên trang) gồm các phần tử:
 *  - {"id": n}                → đoạn gốc n giữ nguyên
 *  - {"id": n, "text": "..."} → đoạn gốc n đổi nội dung
 *  - {"id": -1, "base": n, "text": "..."} → đoạn mới, kiểu chữ lấy theo đoạn n
 * Đoạn gốc không có trong danh sách = đã bị xoá.
 * Chỉ các đoạn có thay đổi mới bị viết lại; định dạng các đoạn khác giữ nguyên.
 */
object DocxEditor {

    /** Phần tử chứa nội dung không phải chữ: giữ nguyên khi viết lại đoạn văn. */
    private val NON_TEXT = setOf("w:drawing", "w:pict", "w:object", "mc:AlternateContent", "w:fldChar", "w:instrText")

    fun apply(src: File, dest: File, ops: JSONArray) {
        ZipFile(src).use { zf ->
            val main = Zip(src).use { mainPart(it) } ?: "word/document.xml"
            val entry = zf.getEntry(main) ?: throw ReaderException("Không tìm thấy nội dung tài liệu.")
            val docXml = zf.getInputStream(entry).use { it.readBytes() }
            val newXml = edit(docXml, ops)
            ZipOutputStream(dest.outputStream().buffered()).use { out ->
                for (e in zf.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    if (e.name == main) out.write(newXml) else zf.getInputStream(e).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    fun edit(xml: ByteArray, ops: JSONArray): ByteArray {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val paras = ArrayList<Element>()
        collect(doc.documentElement, paras)

        val seen = BooleanArray(paras.size)
        var prev: Element? = null
        for (i in 0 until ops.length()) {
            val op = ops.getJSONObject(i)
            val id = op.optInt("id", -1)
            val text = if (op.has("text") && !op.isNull("text")) op.getString("text") else null
            if (id in paras.indices && !seen[id]) {
                seen[id] = true
                val p = paras[id]
                if (text != null) rewrite(p, text)
                prev = p
            } else {
                val baseId = if (id >= 0) id else op.optInt("base", -1)
                val base = paras.getOrNull(baseId) ?: prev ?: continue
                val after = prev ?: base
                val np = newParagraph(base)
                rewrite(np, text ?: "")
                after.parentNode.insertBefore(np, after.nextSibling)
                prev = np
            }
        }
        // Đoạn bị xoá
        for (i in paras.indices) {
            if (seen[i]) continue
            val p = paras[i]
            val parent = p.parentNode ?: continue
            val keep = hasNonText(p) || p.getElementsByTagName("w:sectPr").length > 0 ||
                (parent is Element && parent.nodeName == "w:tc" && countParas(parent) <= 1)
            if (keep) rewrite(p, "") else parent.removeChild(p)
        }

        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        t.setOutputProperty(OutputKeys.INDENT, "no")
        val bos = ByteArrayOutputStream()
        t.transform(DOMSource(doc), StreamResult(bos))
        return bos.toByteArray()
    }

    /** Đếm đoạn văn theo đúng thứ tự DocxConverter đã đánh số (data-p). */
    private fun collect(n: Node, out: MutableList<Element>) {
        var c = n.firstChild
        while (c != null) {
            if (c is Element && c.nodeName !in DocxConverter.SKIP) {
                if (c.nodeName == "w:p") out.add(c)
                collect(c, out)
            }
            c = c.nextSibling
        }
    }

    private fun countParas(parent: Element): Int {
        var n = 0
        var c = parent.firstChild
        while (c != null) {
            if (c is Element && c.nodeName == "w:p") n++
            c = c.nextSibling
        }
        return n
    }

    private fun hasNonText(p: Element): Boolean = NON_TEXT.any { p.getElementsByTagName(it).length > 0 }

    /** Các run chứa chữ của đoạn (không lấy đoạn lồng bên trong hộp chữ, hình...). */
    private fun textRuns(p: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(n: Element) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) {
                    val name = c.nodeName
                    when {
                        name == "w:r" -> if (isTextRun(c)) out.add(c)
                        name == "w:p" || name == "w:pPr" || name == "w:txbxContent" || name in NON_TEXT ||
                            name in DocxConverter.SKIP -> {}
                        else -> walk(c)
                    }
                }
                c = c.nextSibling
            }
        }
        walk(p)
        return out
    }

    private fun isTextRun(r: Element): Boolean {
        var hasText = false
        var c = r.firstChild
        while (c != null) {
            if (c is Element) {
                when (c.nodeName) {
                    "w:t", "w:tab", "w:br", "w:cr", "w:noBreakHyphen", "w:softHyphen", "w:sym" -> hasText = true
                    in NON_TEXT -> return false
                }
            }
            c = c.nextSibling
        }
        return hasText
    }

    private fun rewrite(p: Element, text: String) {
        val doc = p.ownerDocument
        val runs = textRuns(p)
        val target = runs.firstOrNull() ?: doc.createElement("w:r").also { p.appendChild(it) }
        for (r in runs.drop(1)) r.parentNode?.removeChild(r)
        // Xoá nội dung cũ, giữ định dạng (w:rPr)
        var c = target.firstChild
        while (c != null) {
            val next = c.nextSibling
            if (!(c is Element && c.nodeName == "w:rPr")) target.removeChild(c)
            c = next
        }
        val clean = text.replace(' ', ' ').replace("​", "").replace("\r", "")
        val buf = StringBuilder()
        fun flush() {
            if (buf.isEmpty()) return
            val t = doc.createElement("w:t")
            t.setAttribute("xml:space", "preserve")
            t.appendChild(doc.createTextNode(buf.toString()))
            target.appendChild(t)
            buf.setLength(0)
        }
        for (ch in clean) {
            when (ch) {
                '\n' -> { flush(); target.appendChild(doc.createElement("w:br")) }
                '\t', ' ' -> { flush(); target.appendChild(doc.createElement("w:tab")) }
                else -> buf.append(ch)
            }
        }
        flush()
    }

    /** Đoạn mới cùng kiểu đoạn (pPr) và kiểu chữ (rPr của run đầu) với đoạn gốc. */
    private fun newParagraph(base: Element): Element {
        val doc = base.ownerDocument
        val np = doc.createElement("w:p")
        var c = base.firstChild
        while (c != null) {
            if (c is Element && c.nodeName == "w:pPr") {
                val pPr = c.cloneNode(true) as Element
                // Không chép ngắt phần (sectPr) sang đoạn mới
                val sect = pPr.getElementsByTagName("w:sectPr")
                for (k in sect.length - 1 downTo 0) sect.item(k).parentNode.removeChild(sect.item(k))
                np.appendChild(pPr)
                break
            }
            c = c.nextSibling
        }
        val r = doc.createElement("w:r")
        textRuns(base).firstOrNull()?.let { fr ->
            var k = fr.firstChild
            while (k != null) {
                if (k is Element && k.nodeName == "w:rPr") { r.appendChild(k.cloneNode(true)); break }
                k = k.nextSibling
            }
        }
        np.appendChild(r)
        return np
    }
}
