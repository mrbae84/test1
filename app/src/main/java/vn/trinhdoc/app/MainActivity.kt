package vn.trinhdoc.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Màn hình chính: nút "Mở file" và danh sách file đã mở gần đây. */
class MainActivity : Activity() {

    companion object {
        private const val PICK = 1
        private val MIMES = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/msword",
            "application/vnd.ms-excel"
        )
    }

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private val adapter = RecentAdapter()
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Word · Excel · PDF — không quảng cáo, chỉ để đọc"
            textSize = 14f
            alpha = 0.7f
            setPadding(0, dp(4), 0, 0)
        })

        val openBtn = Button(this).apply {
            text = "Mở file"
            textSize = 18f
            setOnClickListener { pickFile() }
        }
        root.addView(openBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(58)).apply { topMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = "Gần đây"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(6))
        })

        emptyView = TextView(this).apply {
            text = "Chưa có file nào.\n\nBấm “Mở file”, hoặc mở file Word, Excel, PDF từ Zalo, Gmail, " +
                "trình quản lý file… rồi chọn “Trình đọc”."
            textSize = 15f
            alpha = 0.7f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(emptyView)

        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ -> openLocal(this@MainActivity.adapter.getItem(pos)) }
            setOnItemLongClickListener { _, _, pos, _ ->
                confirmRemove(this@MainActivity.adapter.getItem(pos))
                true
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(dp(20) + b.left, dp(20) + b.top, dp(20) + b.right, b.bottom)
            insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.items = Recents.list(this)
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, MIMES)
        startActivityForResult(i, PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK && resultCode == RESULT_OK && uri != null) {
            startActivity(
                Intent(this, ReaderActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    private fun openLocal(f: File) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun confirmRemove(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Xoá khỏi danh sách?")
            .setMessage(Recents.displayName(f))
            .setPositiveButton("Xoá") { _, _ -> f.delete(); refresh() }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun badgeFor(name: String): Pair<String, Int> = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> Pair("PDF", 0xFFD93025.toInt())
        "docx", "doc" -> Pair("W", 0xFF1A56C4.toInt())
        "xlsx", "xls" -> Pair("X", 0xFF188038.toInt())
        else -> Pair("•", 0xFF6B6B70.toInt())
    }

    private inner class RecentAdapter : BaseAdapter() {
        var items: List<File> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int): File = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val f = items[position]
            val name = Recents.displayName(f)
            val (label, color) = badgeFor(name)

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val badge = TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(color)
                }
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })

            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@MainActivity).apply {
                text = name
                textSize = 16f
                maxLines = 2
            })
            texts.addView(TextView(this@MainActivity).apply {
                text = dateFmt.format(Date(f.lastModified())) + " · " + Formatter.formatShortFileSize(this@MainActivity, f.length())
                textSize = 13f
                alpha = 0.65f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            return row
        }
    }
}
