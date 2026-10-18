package com.downls10

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DownLS10MainActivity : Activity() {

    private var showSpeed = true

    private lateinit var adapter: DownloadAdapter
    private val refreshListener: () -> Unit = { adapter.notifyDataSetChanged() }

    private fun mainPrefs() = getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        showSpeed = mainPrefs().getBoolean("showSpeed", true)
        DownloadsRepository.ensureLoaded(this)

        val btnAddUrl = findViewById<Button>(R.id.btnAddUrl)
        val btnBrowser = findViewById<Button>(R.id.btnBrowser)
        val btnMenu = findViewById<Button>(R.id.btnMenu)
        val btnDeleteCompleted = findViewById<Button>(R.id.btnDeleteCompleted)
        val btnDeleteAllDownloads = findViewById<Button>(R.id.btnDeleteAllDownloads)
        val listView = findViewById<ListView>(R.id.listView)

        adapter = DownloadAdapter(
            this,
            DownloadsRepository.downloadList,
            showSpeed,
            onPauseResumeClick = { item -> handlePauseResume(item) },
            onDeleteClick = { item -> showDeleteDialog(item) }
        )
        listView.adapter = adapter

        btnAddUrl.setOnClickListener { showAddUrlDialog() }
        btnDeleteCompleted.setOnClickListener { confirmDeleteCompletedDownloads() }
        btnDeleteAllDownloads.setOnClickListener { confirmDeleteAllDownloads() }
        // منطقة لمس أكبر للكرة الرمادية مع الإبقاء على شكلها الحالي.
        btnBrowser.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> true
                android.view.MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }
        btnBrowser.setOnClickListener {
            val browserIntent = Intent(this, BrowserActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(browserIntent)
        }
        btnMenu.setOnClickListener { showOptionsMenu() }
    }

    override fun onResume() {
        super.onResume()
        DownloadsRepository.addListener(refreshListener)
        adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        DownloadsRepository.removeListener(refreshListener)
    }


    private fun confirmDeleteCompletedDownloads() {
        val completed = DownloadsRepository.downloadList.filter { it.state == DownloadState.COMPLETED }
        if (completed.isEmpty()) {
            Toast.makeText(this, "لا توجد تنزيلات مكتملة للحذف", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف التنزيلات المكتملة")
            .setMessage("سيتم حذف ${completed.size} تنزيل مكتمل من القائمة والملفات المحفوظة. هل تريد المتابعة؟")
            .setPositiveButton("موافق") { _, _ ->
                completed.toList().forEach { DownloadsRepository.deleteDownload(this, it, deleteFileOnDisk = true) }
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmDeleteAllDownloads() {
        val all = DownloadsRepository.downloadList.toList()
        if (all.isEmpty()) {
            Toast.makeText(this, "قائمة التنزيلات فارغة", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف جميع التنزيلات")
            .setMessage("سيتم إيقاف التنزيلات وحذف ${all.size} عنصرًا من القائمة والملفات الجزئية أو المكتملة. هل تريد المتابعة؟")
            .setPositiveButton("موافق") { _, _ ->
                all.forEach { DownloadsRepository.deleteDownload(this, it, deleteFileOnDisk = true) }
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAddUrlDialog() {
        val input = EditText(this)
        input.hint = "إلصق رابط التنزيل هنا..."

        AlertDialog.Builder(this)
            .setTitle("إضافة تنزيل جديد")
            .setView(input)
            .setPositiveButton("تنزيل") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    DownloadsRepository.startNewDownload(this, url, showToast = false)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showOptionsMenu() {
        val options = arrayOf(
            if (showSpeed) "إخفاء سرعة التنزيل" else "إظهار سرعة التنزيل",
            "التنزيلات المتزامنة",
            "السجل (History)"
        )

        AlertDialog.Builder(this)
            .setTitle("الخيارات")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        showSpeed = !showSpeed
                        mainPrefs().edit().putBoolean("showSpeed", showSpeed).apply()
                        adapter.setShowSpeed(showSpeed)
                    }
                    1 -> showConcurrentDownloadsDialog()
                    2 -> showHistoryDialog()
                }
            }
            .show()
    }

    private fun showConcurrentDownloadsDialog() {
        val values = (1..10).toList()
        val current = DownloadsRepository.getMaxConcurrentDownloads(this)

        AlertDialog.Builder(this)
            .setTitle("التنزيلات المتزامنة")
            .setSingleChoiceItems(
                values.map { it.toString() }.toTypedArray(),
                current - 1
            ) { dialog, which ->
                DownloadsRepository.setMaxConcurrentDownloads(this, values[which])
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("رابط", text))
        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
    }

    private fun showHistoryDialog() {
        val history = DownloadHistory.get(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "سجل التنزيلات فارغ", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        history.forEach { entry ->
            val row = TextView(this).apply {
                text = entry.url
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 20, 0, 20)
                setOnClickListener {
                    val browserIntent = Intent(this@DownLS10MainActivity, BrowserActivity::class.java).apply {
                        putExtra(BrowserActivity.EXTRA_OPEN_URL, entry.url)
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    }
                    startActivity(browserIntent)
                }
                setOnLongClickListener {
                    copyToClipboard(entry.url)
                    true
                }
            }
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#333333"))
            })
        }

        AlertDialog.Builder(this)
            .setTitle("سجل التنزيلات")
            .setView(scroll)
            .setPositiveButton("مسح السجل") { _, _ ->
                DownloadHistory.clear(this)
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun handlePauseResume(item: DownloadItem) {
        if (item.state == DownloadState.DOWNLOADING) {
            DownloadsRepository.pauseDownload(this, item)
        } else if (item.state == DownloadState.PAUSED || item.state == DownloadState.ERROR) {
            DownloadsRepository.resumeDownload(this, item)
        }
    }

    private fun showDeleteDialog(item: DownloadItem) {
        val options = arrayOf("الحذف من القائمة فقط", "الحذف النهائي من الجهاز")

        AlertDialog.Builder(this)
            .setTitle("حذف التنزيل")
            .setItems(options) { _, which ->
                DownloadsRepository.deleteDownload(this, item, deleteFileOnDisk = (which == 1))
            }
            .show()
    }
}
