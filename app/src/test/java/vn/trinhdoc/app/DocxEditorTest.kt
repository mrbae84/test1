package vn.trinhdoc.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Sửa – lưu – đọc lại một file Word mẫu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocxEditorTest {

    private val pRe = Regex("""data-p="(\d+)"[^>]*>(.*?)</(p|h\d)>""")

    private fun paragraphs(html: String): List<Pair<Int, String>> =
        pRe.findAll(html).map { Pair(it.groupValues[1].toInt(), it.groupValues[2].replace(Regex("<[^>]+>"), "")) }.toList()

    @Test
    fun editInsertDeleteRoundTrip() {
        val src = File("src/test/samples/vi-sotay.docx")
        val before = DocxConverter.convert(src).bodyHtml
        val ps = paragraphs(before)
        assertTrue("có đoạn văn", ps.size > 10)

        // Đổi đoạn 1, chèn đoạn mới sau đoạn 2, xoá đoạn 3, giữ nguyên phần còn lại
        val ops = JSONArray()
        for ((id, _) in ps) {
            when (id) {
                ps[1].first -> ops.put(JSONObject().put("id", id).put("text", "Tiêu đề đã sửa: Mera Office\tcó tab\ndòng 2"))
                ps[2].first -> {
                    ops.put(JSONObject().put("id", id))
                    ops.put(JSONObject().put("id", -1).put("base", id).put("text", "Đoạn mới chèn vào"))
                }
                ps[3].first -> {}
                else -> ops.put(JSONObject().put("id", id))
            }
        }
        val out = File("build/edited.docx")
        DocxEditor.apply(src, out, ops)
        val after = DocxConverter.convert(out).bodyHtml
        File("build/converted").mkdirs()
        File("build/converted/edit-report.txt").writeText(
            "before=${ps.size} after=${paragraphs(after).size}\n" + paragraphs(after).take(8).joinToString("\n")
        )
        assertTrue(after.contains("Tiêu đề đã sửa: Mera Office&emsp;có tab<br>dòng 2"))
        assertTrue(after.contains("Đoạn mới chèn vào"))
        assertEquals(ps.size, paragraphs(after).size)
        val deleted = ps[3].second
        if (deleted.isNotBlank() && before.split(deleted).size == 2) assertFalse(after.contains(deleted))
        // Ảnh và phần còn lại vẫn đọc được
        assertEquals(DocxConverter.convert(src).images.size, DocxConverter.convert(out).images.size)
    }
}
