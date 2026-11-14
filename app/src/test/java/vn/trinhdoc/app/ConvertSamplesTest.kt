package vn.trinhdoc.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Chuyển đổi tất cả file mẫu trong src/test/samples và ghi kết quả HTML + ảnh ra
 * build/converted/<tên file>/ để xem lại. Lỗi ngoài ReaderException = test thất bại.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertSamplesTest {

    @Test
    fun convertAllSamples() {
        val dir = File("src/test/samples")
        val out = File("build/converted").apply { deleteRecursively(); mkdirs() }
        val failures = ArrayList<String>()
        val report = StringBuilder()
        for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            val kind = f.extension.lowercase()
            val t0 = System.currentTimeMillis()
            try {
                val result: DocxResult = when (kind) {
                    "pptx" -> PptxConverter.convert(f)
                    "docx" -> DocxConverter.convert(f)
                    "xlsx" -> DocxResult(XlsxConverter.convert(f), emptyMap())
                    else -> continue
                }
                val d = File(out, f.nameWithoutExtension).apply { mkdirs() }
                File(d, "kind.txt").writeText(kind)
                File(d, "body.html").writeText(result.bodyHtml)
                for ((path, bytes) in result.images) {
                    File(d, "img/$path").apply { parentFile?.mkdirs() }.writeBytes(bytes)
                }
                report.append("OK   ").append(f.name).append("  ").append(result.bodyHtml.length)
                    .append(" chars, ").append(result.images.size).append(" images, ")
                    .append(System.currentTimeMillis() - t0).append(" ms\n")
            } catch (e: ReaderException) {
                report.append("MSG  ").append(f.name).append("  ").append(e.message).append('\n')
            } catch (e: Throwable) {
                report.append("FAIL ").append(f.name).append("  ").append(e).append('\n')
                failures.add(f.name + ": " + e.stackTraceToString().take(1500))
            }
        }
        File(out, "report.txt").writeText(report.toString() + "\n" + failures.joinToString("\n\n"))
        println(report)
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }
}
