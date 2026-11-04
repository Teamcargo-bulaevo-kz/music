package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Все настройки в одном месте: основные сверху, продвинутые раскрываются. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var rhythmStatus: TextView
    private lateinit var foldersBox: LinearLayout
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        title = "Настройки"
        val scroll = ScrollView(this).apply { setBackgroundResource(R.drawable.harmonia_background) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(32))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.padForSystemBars()
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- Построение экрана ----------

    private fun build() {
        root.removeAllViews()
        header("Настройки", big = true)
        statusText = text("", 13f, R.color.harmonia_muted).also { root.addView(it) }
        rhythmStatus = TextView(this) // обновляется в refreshStatus

        section("Вид плеера")
        switchRow("Свечение за обложкой", "Цвет зависит от настроения песни, пульс идёт в такт", AppSettings.auraEnabled) { AppSettings.auraEnabled = it }
        sliderRow("Яркость свечения", 30, 100, 10, AppSettings.auraBrightness, "%") { AppSettings.auraBrightness = it }
        choiceRow("Размер обложки", listOf("Маленькая", "Средняя", "Большая"), { AppSettings.coverSize }) { AppSettings.coverSize = it }
        choiceRow("Размер названия", listOf("Мелкий", "Обычный", "Крупный"), { AppSettings.textSize }) { AppSettings.textSize = it }

        section("Кнопки плеера")
        choiceRow("Размер кнопок", listOf("Маленькие", "Обычные", "Крупные"), { AppSettings.buttonSize }) { AppSettings.buttonSize = it }
        switchRow("Полоса перемотки", "Показывать время и перемотку", AppSettings.showSeek) { AppSettings.showSeek = it }
        switchRow("Нравится и не нравится", "Две кнопки оценки, по ним строятся подборки", AppSettings.showRating) { AppSettings.showRating = it }

        section("Папки с музыкой")
        text("Выключенные папки не показываются в списке и не сканируются.", 13f, R.color.harmonia_muted).also { root.addView(it) }
        foldersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(foldersBox)
        loadFolders()

        section("Разметка музыки")
        text("Настроение и ритм определяются на телефоне, песни никуда не отправляются.", 13f, R.color.harmonia_muted).also { root.addView(it) }
        actionRow("Разметить музыку", "Проанализировать новые песни") { runInMain("tag") }

        // Продвинутые
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val toggle = actionRowView("Продвинутые настройки  ▾", "Ритм, отладка, обмен с другим ИИ, сброс") {}
        toggle.setOnClickListener {
            val open = advanced.visibility != View.VISIBLE
            advanced.visibility = if (open) View.VISIBLE else View.GONE
            (toggle.getChildAt(0) as TextView).text = if (open) "Продвинутые настройки  ▴" else "Продвинутые настройки  ▾"
        }
        root.addView(toggle, lp(top = 24))
        root.addView(advanced)
        val outer = root
        root = advanced

        section("Ритм")
        rhythmStatus = text("", 13f, R.color.harmonia_muted).also { root.addView(it) }
        actionRow("Доступ к ритму", "Нужен микрофон, чтобы свечение пульсировало в такт. Звук не записывается") { askRhythmAccess() }
        sliderRow("Чувствительность к ритму", 50, 150, 10, AppSettings.beatSensitivity, "%") { AppSettings.beatSensitivity = it }
        choiceRow("Частота кадров фона", listOf("20", "30", "60"), { listOf(20, 30, 60).indexOf(AppSettings.fps).coerceAtLeast(1) }) { AppSettings.fps = listOf(20, 30, 60)[it] }

        section("Отладка")
        switchRow("Отладочный вид плеера", "Показать спектр, карту настроения, строку характеристик, вперемешку и повтор", AppSettings.debugView) { AppSettings.debugView = it }
        if (BuildConfig.DEBUG) actionRow("Размер окна", "Для поиска проблем с версткой") { runInMain("window") }

        section("Разметка: дополнительно")
        actionRow("Разметить заново", "Автоматическая разметка сотрётся, ручные правки сохранятся") { confirmRedo() }
        actionRow("Повторить неопознанные", null) { runInMain("retry") }
        actionRow("Выбрать файл с разметкой", "Загрузить готовую таблицу") { runInMain("pick") }

        section("Обмен с другим ИИ")
        actionRow("Отправить песни другому ИИ", "Список песен для разметки в другом приложении") { runInMain("send") }
        actionRow("Вставить ответ другого ИИ", null) { runInMain("paste") }
        actionRow("Поделиться таблицей Музыка.xlsx", null) { runInMain("share") }

        section("Сброс")
        actionRow("Сбросить настройки вида", "Папки, размеры и свечение вернутся к исходным") {
            AlertDialog.Builder(this).setTitle("Сбросить настройки?")
                .setPositiveButton("Сбросить") { _, _ -> AppSettings.resetAll(); MusicLibrary.invalidate(); build(); refreshStatus() }
                .setNegativeButton("Отмена", null).show()
        }
        root = outer
        section("О приложении")
        text("Harmonia ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TAG}", 13f, R.color.harmonia_muted).also { root.addView(it) }
    }

    // ---------- Статус и папки ----------

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rhythm = if (granted) "доступ к ритму есть" else "доступа к ритму нет, фон пульсирует условно"
        rhythmStatus.text = "Сейчас: $rhythm"
        lifecycleScope.launch {
            val (total, tagged) = withContext(Dispatchers.IO) {
                val music = MusicLibrary.index(this@SettingsActivity).allMusic()
                music.size to music.count { t -> MusicTags.get(this@SettingsActivity, t)?.let { MusicTags.isTagged(it) } == true }
            }
            statusText.text = "Песен: $total · размечено: $tagged · $rhythm"
        }
    }

    private fun loadFolders() {
        foldersBox.removeAllViews()
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { MusicLibrary.folders(this@SettingsActivity) }
            if (folders.isEmpty()) {
                foldersBox.addView(text("Папки не найдены. Проверь доступ к музыке в настройках телефона.", 13f, R.color.harmonia_muted))
                return@launch
            }
            val primary = android.os.Environment.getExternalStorageDirectory().path
            folders.forEach { (dir, count) ->
                val shown = dir.removePrefix(primary).ifEmpty { "/" }.trimStart('/').ifEmpty { "Корень памяти" }
                val sw = switchView(shown, "$count песен", AppSettings.isFolderEnabled(dir)) { on ->
                    AppSettings.disabledFolders = if (on) AppSettings.disabledFolders - dir else AppSettings.disabledFolders + dir
                    MusicLibrary.invalidate()
                }
                foldersBox.addView(sw)
            }
        }
    }

    private fun askRhythmAccess() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Доступ уже есть", Toast.LENGTH_SHORT).show()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun confirmRedo() {
        AlertDialog.Builder(this).setTitle("Разметить заново?")
            .setMessage("Автоматическая разметка сотрётся и будет сделана снова. Ручные правки и оценки сохранятся.")
            .setPositiveButton("Разметить") { _, _ -> runInMain("redo") }
            .setNegativeButton("Отмена", null).show()
    }

    /** Действия живут в главном экране: открываем его с командой. */
    private fun runInMain(action: String) {
        startActivity(Intent(this, MusicActivity::class.java).putExtra(MusicActivity.EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    // ---------- Элементы ----------

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) }

    private fun text(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t; textSize = size; setTextColor(getColor(color)); setPadding(0, px(4), 0, px(4))
    }

    private fun header(t: String, big: Boolean = false) {
        root.addView(TextView(this).apply {
            text = t; textSize = if (big) 26f else 14f; setTextColor(getColor(R.color.harmonia_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp())
    }

    private fun section(t: String) {
        root.addView(TextView(this).apply {
            text = t.uppercase(); textSize = 12f; letterSpacing = 0.08f
            setTextColor(getColor(R.color.harmonia_violet)); setPadding(0, px(24), 0, px(4))
        }, lp())
    }

    private fun rowLayout(title: String, subtitle: String?): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10), 0, px(10)); minimumHeight = px(52)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(getColor(R.color.harmonia_text)) })
        if (subtitle != null) col.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(getColor(R.color.harmonia_muted)) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row to col
    }

    private fun switchView(title: String, subtitle: String?, on: Boolean, change: (Boolean) -> Unit): LinearLayout {
        val (row, _) = rowLayout(title, subtitle)
        val sw = MaterialSwitch(this).apply { isChecked = on; setOnCheckedChangeListener { _, v -> change(v) } }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun switchRow(title: String, subtitle: String?, on: Boolean, change: (Boolean) -> Unit) {
        root.addView(switchView(title, subtitle, on, change))
    }

    private fun actionRowView(title: String, subtitle: String?, click: () -> Unit): LinearLayout {
        val (row, _) = rowLayout(title, subtitle)
        row.setOnClickListener { click() }
        return row
    }

    private fun actionRow(title: String, subtitle: String?, click: () -> Unit) {
        root.addView(actionRowView(title, subtitle, click))
    }

    private fun choiceRow(title: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) {
        val (row, _) = rowLayout(title, null)
        val value = TextView(this).apply { textSize = 14f; setTextColor(getColor(R.color.harmonia_cyan)); text = options[get().coerceIn(0, options.lastIndex)] }
        row.addView(value)
        row.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), get()) { d, which -> set(which); value.text = options[which]; d.dismiss() }
                .show()
        }
        root.addView(row)
    }

    private fun sliderRow(title: String, min: Int, max: Int, step: Int, now: Int, unit: String, set: (Int) -> Unit) {
        val (row, col) = rowLayout(title, null)
        val value = TextView(this).apply { textSize = 14f; setTextColor(getColor(R.color.harmonia_cyan)); text = "$now$unit" }
        row.addView(value)
        val bar = SeekBar(this).apply {
            this.max = (max - min) / step; progress = (now - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) value.text = "${min + p * step}$unit" }
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) { set(min + b.progress * step) }
            })
        }
        col.addView(bar)
        root.addView(row)
    }
}
