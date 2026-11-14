package vn.trinhdoc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/** Nội dung đã nạp, hiển thị qua WebView. */
interface ReaderContent : Closeable {
    val kind: String
    val bodyClass: String get() = kind
    fun body(): String
    fun resource(path: String, screenW: Int): WebResourceResponse?
    override fun close() {}
}

class PdfContent(file: File) : ReaderContent {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: SecurityException) {
        pfd.close()
        throw ReaderException("File PDF có mật khẩu, ứng dụng chưa mở được.")
    } catch (e: IOException) {
        pfd.close()
        throw ReaderException("File PDF bị hỏng, không mở được.")
    }
    private val sizes: List<IntArray> = (0 until renderer.pageCount).map { i ->
        val page = renderer.openPage(i)
        try {
            intArrayOf(page.width, page.height)
        } finally {
            page.close()
        }
    }
    @Volatile
    private var closed = false

    override val kind = "pdf"

    override fun body(): String {
        if (sizes.isEmpty()) return "<p class=\"empty\">File PDF không có trang nào.</p>"
        val sb = StringBuilder("<div class=\"pages\">")
        sizes.forEachIndexed { i, s ->
            sb.append("<img class=\"pg\" loading=\"lazy\" width=\"").append(s[0])
                .append("\" height=\"").append(s[1])
                .append("\" src=\"/pdf/").append(i).append(".jpg\" alt=\"\">")
        }
        return sb.append("</div>").toString()
    }

    @Synchronized
    private fun render(index: Int, targetW: Int): ByteArray? {
        if (closed) return null
        val page = renderer.openPage(index)
        try {
            val scale = min(targetW.toFloat() / page.width, 4096f / page.height)
            val w = max(1, (page.width * scale).toInt())
            val h = max(1, (page.height * scale).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
            return out.toByteArray()
        } finally {
            page.close()
        }
    }

    override fun resource(path: String, screenW: Int): WebResourceResponse? {
        if (!path.startsWith("/pdf/")) return null
        val i = path.removePrefix("/pdf/").removeSuffix(".jpg").toIntOrNull() ?: return null
        if (i !in sizes.indices) return null
        val bytes = render(i, screenW) ?: return null
        return cached(WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes)))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
        pfd.close()
    }
}

/** Word và PowerPoint: HTML đã chuyển đổi + ảnh nhúng phục vụ qua /img/. */
class OfficeContent(override val kind: String, private val result: DocxResult, val file: File) : ReaderContent {
    override fun body() =
        if (kind == "docx") "<div class=\"book\"><div class=\"doc\">${result.bodyHtml}</div></div>" else result.bodyHtml

    override fun resource(path: String, screenW: Int): WebResourceResponse? {
        if (!path.startsWith("/img/")) return null
        val name = path.removePrefix("/img/")
        val bytes = result.images[name] ?: return null
        val mime = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        return cached(WebResourceResponse(mime, null, ByteArrayInputStream(bytes)))
    }
}

/** Cho phép WebView giữ ảnh trong bộ nhớ đệm, lật trang không phải tải lại. */
internal fun cached(r: WebResourceResponse): WebResourceResponse {
    r.responseHeaders = mapOf("Cache-Control" to "max-age=86400")
    return r
}

class XlsxContent(private val html: String) : ReaderContent {
    override val kind = "xlsx"
    override val bodyClass = if (html.contains("class=\"tabs\"")) "xlsx multi" else "xlsx"
    override fun body() = html
    override fun resource(path: String, screenW: Int): WebResourceResponse? = null
}

/** Nguồn file: từ app khác (content://) hoặc file đã lưu trong "Gần đây". */
sealed class Input {
    class Remote(val uri: Uri) : Input()
    class Local(val file: File) : Input()
}

object DocLoader {
    private enum class Kind { PDF, DOCX, XLSX, PPTX, OLD_OFFICE, UNKNOWN }

    fun load(ctx: Context, input: Input, name: String): ReaderContent {
        val file = when (input) {
            is Input.Local -> {
                if (!input.file.exists()) throw ReaderException("File không còn tồn tại.")
                input.file.setLastModified(System.currentTimeMillis())
                input.file
            }
            is Input.Remote -> {
                val tmp = File.createTempFile("in_", ".tmp", ctx.cacheDir)
                val ins = ctx.contentResolver.openInputStream(input.uri)
                    ?: throw ReaderException("Không đọc được file này.")
                ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp
            }
        }

        val kind = sniff(file)
        val problem = when (kind) {
            Kind.OLD_OFFICE -> "Đây là file Office định dạng cũ (.doc/.xls/.ppt) hoặc có mật khẩu. " +
                "Ứng dụng đọc được .docx, .xlsx, .pptx và .pdf. Anh có thể mở file trên máy tính và lưu lại dạng mới."
            Kind.UNKNOWN -> "Không nhận dạng được loại file này."
            else -> null
        }
        if (problem != null) {
            if (input is Input.Remote) file.delete()
            throw ReaderException(problem)
        }

        val stored = if (input is Input.Remote) Recents.add(ctx, file, name) else file
        try {
            return when (kind) {
                Kind.PDF -> PdfContent(stored)
                Kind.DOCX -> OfficeContent("docx", DocxConverter.convert(stored), stored)
                Kind.PPTX -> OfficeContent("pptx", PptxConverter.convert(stored), stored)
                Kind.XLSX -> XlsxContent(XlsxConverter.convert(stored))
                else -> throw ReaderException("Không nhận dạng được loại file này.")
            }
        } catch (e: Throwable) {
            if (input is Input.Remote) stored.delete()
            throw e
        }
    }

    private fun sniff(file: File): Kind {
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }
        if (n <= 0) return Kind.UNKNOWN
        val s = String(head, 0, n, Charsets.ISO_8859_1)
        if (s.contains("%PDF")) return Kind.PDF
        if (s.startsWith("PK")) {
            return try {
                ZipFile(file).use { z ->
                    val names = z.entries().asSequence().map { it.name }.toList()
                    when {
                        names.any { it.startsWith("word/") } -> Kind.DOCX
                        names.any { it.startsWith("xl/") } -> Kind.XLSX
                        names.any { it.startsWith("ppt/") } -> Kind.PPTX
                        else -> Kind.UNKNOWN
                    }
                }
            } catch (e: ZipException) {
                Kind.UNKNOWN
            }
        }
        if (n >= 4 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() &&
            head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()
        ) return Kind.OLD_OFFICE
        return Kind.UNKNOWN
    }
}

/** Lưu bản sao các file đã mở gần đây trong bộ nhớ riêng của app. */
object Recents {
    private const val MAX = 30

    fun dir(ctx: Context) = File(ctx.filesDir, "recent").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun displayName(f: File) = f.name.substringAfter('_')

    fun add(ctx: Context, src: File, name: String): File {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(150).ifBlank { "tai-lieu" }
        list(ctx).filter { displayName(it) == safe && it.length() == src.length() }.forEach { it.delete() }
        val dest = File(dir(ctx), "${System.currentTimeMillis()}_$safe")
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
        dest.setLastModified(System.currentTimeMillis())
        list(ctx).drop(MAX).forEach { it.delete() }
        return dest
    }
}
