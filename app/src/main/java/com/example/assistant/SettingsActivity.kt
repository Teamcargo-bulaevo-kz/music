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
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Настройки: главный экран с разделами. Каждый раздел открывается отдельным окном:
 * переключатели, дизайн, папки с музыкой, расширенные.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SECTION = "section"
        const val S_SWITCHES = "switches"
        const val S_DESIGN = "design"
        const val S_ANIMATION = "animation"
        const val S_PALETTE = "palette"
        const val S_SOUND = "sound"
        const val S_TABLE = "table"
        const val S_FOLDERS = "folders"
        const val S_ADVANCED = "advanced"
    }

    private lateinit var root: LinearLayout
    private var statusText: TextView? = null
    private var rhythmStatus: TextView? = null
    private val section: String? by lazy { intent.getStringExtra(EXTRA_SECTION) }
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundResource(R.drawable.harmonia_background) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(32))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.padForSystemBars()
        when (section) {
            S_SWITCHES -> buildSwitches()
            S_DESIGN -> buildDesign()
            S_ANIMATION -> buildAnimation()
            S_PALETTE -> buildPalette()
            S_SOUND -> buildSound()
            S_TABLE -> buildTable()
            S_FOLDERS -> buildFolders()
            S_ADVANCED -> buildAdvanced()
            else -> buildHome()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- Экраны ----------

    private fun buildHome() {
        header("Настройки", big = true)
        statusText = text("", 13f, R.color.harmonia_muted).also { root.addView(it) }
        navRow("Переключатели", "Свечение, перемотка, оценки") { open(S_SWITCHES) }
        navRow("Дизайн", "Яркость, размеры обложки, текста и кнопок") { open(S_DESIGN) }
        navRow("Анимация", "Размер, пульс, плавность, темп") { open(S_ANIMATION) }
        navRow("Палитра", "Цвета свечения: по настроению или свои") { open(S_PALETTE) }
        navRow("Звук", "Эквалайзер и усиление баса") { open(S_SOUND) }
        navRow("Таблица для ИИ", "Таблица песен с характеристиками в папке Документы/Harmonia") { open(S_TABLE) }
        navRow("Папки с музыкой", "Какие папки показывать и сканировать") { open(S_FOLDERS) }
        navRow("Расширенные", "Ритм, разметка, обмен с ИИ, отладка, сброс") { open(S_ADVANCED) }
        section("Музыка")
        actionRow("Разметить музыку", "Определить настроение и ритм новых песен на телефоне") { runInMain("tag") }
        section("О приложении")
        text("Harmonia ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TAG}", 13f, R.color.harmonia_muted).also { root.addView(it) }
    }

    private fun buildSwitches() {
        header("Переключатели", big = true)
        switchRow("Свечение в плеере", "Цвет зависит от настроения песни, пульс идёт в такт", AppSettings.auraEnabled) { AppSettings.auraEnabled = it }
        switchRow("Свечение в списке", "Мягкий цветной фон под списком песен", AppSettings.listAura) { AppSettings.listAura = it }
        switchRow("Пауза при отключении наушников", "Музыка встанет на паузу, если отключились наушники или Bluetooth", AppSettings.pauseOnUnplug) { AppSettings.pauseOnUnplug = it }
        switchRow("Полоса перемотки", "Показывать время и перемотку в плеере", AppSettings.showSeek) { AppSettings.showSeek = it }
        switchRow("Нравится и не нравится", "Кнопки оценки в плеере, по ним строятся подборки", AppSettings.showRating) { AppSettings.showRating = it }
    }

    private fun buildDesign() {
        header("Дизайн", big = true)
        section("Плеер")
        choiceRow("Размер обложки", listOf("Маленькая", "Средняя", "Большая"), { AppSettings.coverSize }) { AppSettings.coverSize = it }
        choiceRow("Размер названия", listOf("Мелкий", "Обычный", "Крупный"), { AppSettings.textSize }) { AppSettings.textSize = it }
        choiceRow("Размер кнопок", listOf("Маленькие", "Обычные", "Крупные"), { AppSettings.buttonSize }) { AppSettings.buttonSize = it }
        section("Обложки")
        choiceRow("Источник обложек", listOf("Как в системе", "Только из файла песни", "Не показывать"), { AppSettings.coverSource }) {
            AppSettings.coverSource = it; CoverLoader.clear()
        }
        text("У отдельной песни обложку можно сменить или убрать: меню песни, пункт «Обложка».", 12f, R.color.harmonia_muted).also { root.addView(it) }
    }

    /** Небольшой предпросмотр свечения: цвет текущей песни (или нейтральный) и текущие настройки. */
    private fun addPreview() {
        val box = android.widget.FrameLayout(this)
        val aura = AuraView(this).apply {
            intensity = 1f; showRim = true
            applyTrack(this@SettingsActivity, PlayerState.track)
            coverRadiusPx = 36 * dp
        }
        box.addView(aura, android.widget.FrameLayout.LayoutParams(-1, -1))
        aura.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> aura.centerYPx = v.height / 2f }
        val disc = View(this).apply { setBackgroundResource(R.drawable.cover_placeholder) }
        box.addView(disc, android.widget.FrameLayout.LayoutParams(px(72), px(72), Gravity.CENTER))
        root.addView(box, LinearLayout.LayoutParams(-1, px(200)).apply { bottomMargin = px(8) })
    }

    private fun buildAnimation() {
        header("Анимация", big = true)
        addPreview()
        sliderRow("Яркость свечения", 30, 100, 10, AppSettings.auraBrightness, "%") { AppSettings.auraBrightness = it }
        sliderRow("Размер свечения", 60, 150, 10, AppSettings.glowSize, "%") { AppSettings.glowSize = it }
        sliderRow("Размах пульса", 30, 150, 10, AppSettings.pulseAmplitude, "%") { AppSettings.pulseAmplitude = it }
        choiceRow("Чем пульсировать", listOf("Только удары бочки", "Весь бас"), { AppSettings.pulseSource }) { AppSettings.pulseSource = it }
        sliderRow("Пульсов в секунду не больше", 1, 4, 1, AppSettings.maxPulsesPerSec, "") { AppSettings.maxPulsesPerSec = it }
        sliderRow("Плавность пульса", 0, 100, 10, AppSettings.pulseSmooth, "%") { AppSettings.pulseSmooth = it }
        sliderRow("Чувствительность к ритму", 50, 150, 10, AppSettings.beatSensitivity, "%") { AppSettings.beatSensitivity = it }
        switchRow("Пульс по темпу песни", "Держит дыхание в такт ударам и BPM песни", AppSettings.tempoLock) { AppSettings.tempoLock = it }
        choiceRow("Частота кадров", listOf("20", "30", "60"), { listOf(20, 30, 60).indexOf(AppSettings.fps).coerceAtLeast(1) }) { AppSettings.fps = listOf(20, 30, 60)[it] }
        text("Пульс настраивается здесь, цвета в разделе «Палитра». Предпросмотр показывает цвет текущей песни.", 12f, R.color.harmonia_muted).also { root.addView(it) }
    }

    private fun buildPalette() {
        header("Палитра", big = true)
        addPreview()
        val names = listOf("По настроению песни") + MoodPalette.fixed.map { it.first } + "Свой цвет"
        choiceRow("Палитра", names, { AppSettings.paletteMode }) { AppSettings.paletteMode = it }
        sliderRow("Свой оттенок", 0, 350, 10, AppSettings.customHue, "°") { AppSettings.customHue = it }
        sliderRow("Ширина палитры", 0, 100, 10, AppSettings.paletteSpread, "%") { AppSettings.paletteSpread = it }
        sliderRow("Насыщенность", 30, 100, 10, AppSettings.paletteSaturation, "%") { AppSettings.paletteSaturation = it }
        sliderRow("Скорость смены цвета", 0, 100, 10, AppSettings.colorSpeed, "%") { AppSettings.colorSpeed = it }
        switchRow("Цвет по частям песни", "Куплет и припев могут иметь разный оттенок", AppSettings.segmentColors) { AppSettings.segmentColors = it }
        text("«Свой оттенок» работает при выборе «Свой цвет».", 12f, R.color.harmonia_muted).also { root.addView(it) }
    }

    private fun buildSound() {
        header("Звук", big = true)
        text("Работает с плеером приложения, звук других программ не меняется.", 13f, R.color.harmonia_muted).also { root.addView(it) }
        switchRow("Эквалайзер", "Включить настройки ниже", AppSettings.eqEnabled) { AppSettings.eqEnabled = it; EqualizerManager.apply() }
        val info = EqualizerManager.info
        choiceRow("Готовая настройка", EqualizerManager.presets.map { it.first }, { -1 }) { i ->
            AppSettings.eqLevels = EqualizerManager.presets[i].second.map { it * 100 }
            EqualizerManager.apply(); recreate()
        }
        val levels = EqualizerManager.resample(AppSettings.eqLevels, info.bands).toMutableList()
        for (i in 0 until info.bands) {
            val hz = info.centerHz.getOrElse(i) { 0 }
            val label = if (hz >= 1000) "${hz / 1000} кГц" else "$hz Гц"
            sliderRow(label, info.minMb / 100, info.maxMb / 100, 1, levels[i] / 100, " дБ") { db ->
                levels[i] = db * 100
                AppSettings.eqLevels = levels.toList()
                EqualizerManager.apply()
            }
        }
        sliderRow("Усиление баса", 0, 100, 10, AppSettings.bassBoost, "%") { AppSettings.bassBoost = it; EqualizerManager.apply() }
        text("Число полос и диапазон уточняются после первого запуска песни.", 12f, R.color.harmonia_muted).also { root.addView(it) }
    }

    private fun buildTable() {
        header("Таблица для ИИ", big = true)
        text("Таблица со всеми песнями и их характеристиками (готовыми или пустыми) лежит в папке ${PublicFolder.DISPLAY_PATH}, файл «Музыка.xlsx».", 13f, R.color.harmonia_muted).also { root.addView(it) }
        val status = text("", 13f, R.color.harmonia_cyan).also { root.addView(it) }
        fun refresh() {
            val t = PublicFolder.lastMirrorMs(this)
            status.text = when {
                !PublicFolder.supported() -> "Публичная папка работает на Android 10 и новее. Здесь доступна только отправка файла."
                t == 0L -> "Файл ещё не создан. Нажми «Обновить таблицу»."
                else -> "Песен в таблице: ${PublicFolder.lastRows(this)} · обновлено: " +
                    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
            }
        }
        refresh()
        section("Как пользоваться")
        text("1. Обнови таблицу.\n2. Отправь файл ИИ и попроси заполнить пустые характеристики, не меняя названия колонок.\n3. Сохрани исправленный файл на телефон.\n4. Нажми «Импортировать таблицу» и выбери его. Приложение применит данные и заменит файл в папке обновлённым.", 13f, R.color.harmonia_muted).also { root.addView(it) }
        section("Действия")
        actionRow("Обновить таблицу", "Записать свежую таблицу в папку ${PublicFolder.DISPLAY_PATH}") { runInMain("export") }
        actionRow("Отправить ИИ", "Поделиться файлом «Музыка.xlsx» в другое приложение") { runInMain("share") }
        actionRow("Отправить по частям", "Текстовые части, если ИИ не принимает файл целиком") { runInMain("send") }
        actionRow("Импортировать таблицу", "Выбрать исправленный файл (откроется папка ${PublicFolder.DISPLAY_PATH})") { runInMain("pick") }
        actionRow("Вставить ответ ИИ", "Если ИИ ответил текстом: скопируй его и нажми сюда") { runInMain("paste") }
    }

    private fun buildFolders() {
        header("Папки с музыкой", big = true)
        text("Выключенные папки не показываются в списке и не сканируются.", 13f, R.color.harmonia_muted).also { root.addView(it) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { MusicLibrary.folders(this@SettingsActivity) }
            if (folders.isEmpty()) {
                box.addView(text("Папки не найдены. Проверь доступ к музыке в настройках телефона.", 13f, R.color.harmonia_muted))
                return@launch
            }
            val primary = android.os.Environment.getExternalStorageDirectory().path
            val all = folders.map { it.first }
            val total = folders.sumOf { it.second }
            statusLine("Папок: ${folders.size} · песен: $total")
            box.addView(actionRowView("Включить все", null) {
                AppSettings.disabledFolders = emptySet(); MusicLibrary.invalidate(); recreate()
            })
            box.addView(actionRowView("Выключить все", null) {
                AppSettings.disabledFolders = all.toSet(); MusicLibrary.invalidate(); recreate()
            })
            folders.forEach { (dir, count) ->
                val shown = dir.removePrefix(primary).trimStart('/').ifEmpty { "Корень памяти" }
                box.addView(switchView(shown, "$count песен", AppSettings.isFolderEnabled(dir)) { on ->
                    AppSettings.disabledFolders = if (on) AppSettings.disabledFolders - dir else AppSettings.disabledFolders + dir
                    MusicLibrary.invalidate()
                })
            }
        }
        root.addView(box)
    }

    private fun statusLine(t: String) { root.addView(text(t, 13f, R.color.harmonia_cyan), 2) }

    private fun buildAdvanced() {
        header("Расширенные", big = true)

        section("Ритм")
        rhythmStatus = text("", 13f, R.color.harmonia_muted).also { root.addView(it) }
        actionRow("Доступ к ритму", "Нужен микрофон, чтобы свечение пульсировало в такт. Звук не записывается") { askRhythmAccess() }

        section("Обложки")
        actionRow("Сбросить свои обложки", "Вернуть все обложки, которые ты менял или убирал") {
            AlertDialog.Builder(this).setTitle("Сбросить все свои обложки?")
                .setPositiveButton("Сбросить") { _, _ ->
                    val n = CoverStore.resetAll(this); CoverLoader.clear()
                    Toast.makeText(this, if (n > 0) "Сброшено" else "Своих обложек не было", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Отмена", null).show()
        }

        section("Разметка")
        choiceRow("Потоков анализа", listOf("Авто", "1", "2", "3", "4"), { AppSettings.analysisWorkers }) { AppSettings.analysisWorkers = it }
        actionRow("Проверить сомнительные песни", "Песни, в которых разметка не уверена") { runInMain("review") }
        actionRow("Разметить заново", "Автоматическая разметка сотрётся, ручные правки сохранятся") { confirmRedo() }
        actionRow("Повторить неопознанные", null) { runInMain("retry") }

        section("Отладка")
        switchRow("Отладочный вид плеера", "Показать спектр, карту настроения, строку характеристик, вперемешку и повтор", AppSettings.debugView) { AppSettings.debugView = it }
        if (BuildConfig.DEBUG) actionRow("Размер окна", "Для поиска проблем с версткой") { runInMain("window") }

        section("Работа в фоне")
        actionRow("Не выгружать из фона", "На Xiaomi и других оболочках разреши автозапуск и убери ограничения батареи для приложения") {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }
        }
        actionRow("Журнал воспроизведения", "Что делал плеер: старты, остановки, команды от системы. Поможет найти причину") { showPlayLog() }

        section("Сброс")
        actionRow("Сбросить настройки вида", "Папки, размеры и свечение вернутся к исходным") {
            AlertDialog.Builder(this).setTitle("Сбросить настройки?")
                .setPositiveButton("Сбросить") { _, _ -> AppSettings.resetAll(); MusicLibrary.invalidate(); recreate() }
                .setNegativeButton("Отмена", null).show()
        }
    }

    // ---------- Статус ----------

    private fun refreshStatus() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rhythm = if (granted) "доступ к ритму есть" else "доступа к ритму нет, фон пульсирует условно"
        rhythmStatus?.text = "Сейчас: $rhythm"
        val st = statusText ?: return
        lifecycleScope.launch {
            val (total, tagged) = withContext(Dispatchers.IO) {
                val music = MusicLibrary.index(this@SettingsActivity).allMusic()
                music.size to music.count { t -> MusicTags.get(this@SettingsActivity, t)?.let { MusicTags.isTagged(it) } == true }
            }
            st.text = "Песен: $total · размечено: $tagged · $rhythm"
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

    private fun showPlayLog() {
        val log = PlayLog.read().ifBlank { "Пока пусто. Включи музыку, журнал начнёт заполняться." }
        AlertDialog.Builder(this).setTitle("Журнал воспроизведения")
            .setMessage(log.lines().takeLast(25).joinToString("\n"))
            .setPositiveButton("Поделиться") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "Отправить журнал"))
            }
            .setNeutralButton("Очистить") { _, _ -> PlayLog.clear() }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun confirmRedo() {
        AlertDialog.Builder(this).setTitle("Разметить заново?")
            .setMessage("Автоматическая разметка сотрётся и будет сделана снова. Ручные правки и оценки сохранятся.")
            .setPositiveButton("Разметить") { _, _ -> runInMain("redo") }
            .setNegativeButton("Отмена", null).show()
    }

    private fun open(sectionName: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_SECTION, sectionName))
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
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, px(8))
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
        val sw = SwitchCompat(this).apply { isChecked = on; setOnCheckedChangeListener { _, v -> change(v) } }
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

    /** Строка перехода в раздел со стрелкой справа. */
    private fun navRow(title: String, subtitle: String, click: () -> Unit) {
        val row = actionRowView(title, subtitle, click)
        row.addView(TextView(this).apply { text = "›"; textSize = 24f; setTextColor(getColor(R.color.harmonia_muted)) })
        row.setPadding(0, px(14), 0, px(14))
        root.addView(row)
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
