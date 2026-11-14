package com.example.tvlauncher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class AppItem(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: Drawable?,
    val banner: Drawable?
)

class Pin(val pkgs: List<String>, val keys: List<String>)

class Holder(val cell: LinearLayout, val img: ImageView, val label: TextView) :
    RecyclerView.ViewHolder(cell)

class MainActivity : Activity() {

    companion object {
        // Used if IP-based location fails. Change to your city if you like.
        const val FALLBACK_LAT = 9.4533
        const val FALLBACK_LON = 77.7993
        const val FALLBACK_CITY = "Sivakasi"
        const val USE_IP_LOCATION = true
        const val GRID_COLUMNS = 8

        // Apps shown on the top row, in this order.
        val PINS = listOf(
            Pin(listOf("com.google.android.youtube.tv", "com.google.android.youtube"), listOf("youtube")),
            Pin(listOf("in.startv.hotstar", "in.startv.hotstar.dplus.tv"), listOf("hotstar")),
            Pin(listOf("com.netflix.ninja", "com.netflix.mediaclient"), listOf("netflix")),
            Pin(
                listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
                listOf("prime video")
            )
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var u = 10f

    private lateinit var root: FrameLayout
    private lateinit var bg: GradientDrawable
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var tempView: TextView
    private lateinit var descView: TextView
    private lateinit var pinRow: LinearLayout
    private lateinit var grid: RecyclerView

    private var curTop = 0
    private var curBottom = 0
    private var bgAnim: ValueAnimator? = null
    private var weatherCode = -1
    private var lastSig = ""

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            updateBackground()
            main.postDelayed(this, 20000)
        }
    }
    private val weatherTick = object : Runnable {
        override fun run() {
            fetchWeather()
            main.postDelayed(this, 15 * 60 * 1000L)
        }
    }

    private fun px(v: Float) = (v * u).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        u = resources.displayMetrics.widthPixels / 100f
        buildUi()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        main.removeCallbacks(tick)
        main.removeCallbacks(weatherTick)
        main.post(tick)
        main.post(weatherTick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
        main.removeCallbacks(weatherTick)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        grid.scrollToPosition(0)
        pinRow.getChildAt(0)?.requestFocus()
    }

    // Launcher should never close on Back.
    @Deprecated("Launcher ignores back")
    override fun onBackPressed() {}

    // ---------------------------------------------------------------- UI

    private fun label(size: Float, a: Float, light: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u)
        setTextColor(Color.WHITE)
        this.alpha = a
        typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun buildUi() {
        root = FrameLayout(this)
        val colors = computeColors()
        curTop = colors.first
        curBottom = colors.second
        bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(curTop, curBottom))
        root.background = bg

        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(110, 0, 0, 0))
        )
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.clipChildren = false
        col.clipToPadding = false
        col.setPadding(px(5f), px(4f), px(5f), px(1.5f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Header: clock + date on the left, weather on the right
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        clockView = label(6.5f, 1f, true)
        dateView = label(1.9f, 0.8f)
        left.addView(clockView)
        left.addView(dateView)
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        tempView = label(5f, 1f, true)
        descView = label(1.9f, 0.8f)
        tempView.text = ""
        descView.text = ""
        right.addView(tempView)
        right.addView(descView)
        header.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(right, LinearLayout.LayoutParams(-2, -2))
        col.addView(header, LinearLayout.LayoutParams(-1, -2))

        // Favorites row
        pinRow = LinearLayout(this)
        pinRow.orientation = LinearLayout.HORIZONTAL
        pinRow.clipChildren = false
        pinRow.clipToPadding = false
        pinRow.setPadding(px(0.8f), px(0.8f), px(0.8f), px(0.8f))
        val pinLp = LinearLayout.LayoutParams(-1, -2)
        pinLp.topMargin = px(2.5f)
        col.addView(pinRow, pinLp)

        // All other apps
        grid = RecyclerView(this)
        grid.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(px(0.5f), px(1f), px(0.5f), px(2f))
        val gridLp = LinearLayout.LayoutParams(-1, 0, 1f)
        gridLp.topMargin = px(1.5f)
        col.addView(grid, gridLp)

        updateClock()
    }

    private fun makeBox(w: Int, h: Int, radius: Float): Pair<FrameLayout, ImageView> {
        val box = FrameLayout(this)
        val img = ImageView(this)
        img.setBackgroundColor(Color.argb(45, 255, 255, 255))
        img.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        img.clipToOutline = true
        box.addView(img, FrameLayout.LayoutParams(-1, -1))
        return Pair(box, img)
    }

    private fun focusFx(cell: View, box: FrameLayout, radius: Float, label: TextView?) {
        cell.isFocusable = true
        cell.isClickable = true
        label?.alpha = 0.7f
        cell.setOnFocusChangeListener { v, f ->
            val s = if (f) 1.1f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(160).start()
            v.translationZ = if (f) 20f else 0f
            label?.alpha = if (f) 1f else 0.7f
            if (f) {
                val d = GradientDrawable()
                d.cornerRadius = radius
                d.setColor(Color.TRANSPARENT)
                d.setStroke(maxOf(3, px(0.2f)), Color.WHITE)
                box.foreground = d
            } else {
                box.foreground = null
            }
        }
    }

    private fun buildPinned(item: AppItem): View {
        val w = px(17f)
        val h = px(9.6f)
        val r = px(0.9f).toFloat()
        val (box, img) = makeBox(w, h, r)
        val banner = item.banner
        if (banner != null) {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setImageDrawable(banner)
        } else {
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setPadding(px(2f), px(2f), px(2f), px(2f))
            img.setImageDrawable(item.icon)
        }
        val lp = LinearLayout.LayoutParams(w, h)
        lp.rightMargin = px(2f)
        box.layoutParams = lp
        focusFx(box, box, r, null)
        box.setOnClickListener { launch(item) }
        box.setOnLongClickListener { openInfo(item); true }
        return box
    }

    private inner class Adapter(private val items: List<AppItem>) : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = this@MainActivity
            val cell = LinearLayout(ctx)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.setPadding(px(0.5f), px(0.8f), px(0.5f), px(0.8f))
            cell.layoutParams = RecyclerView.LayoutParams(-1, -2)
            val s = px(8f)
            val r = px(1.2f).toFloat()
            val (box, img) = makeBox(s, s, r)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setPadding(px(0.6f), px(0.6f), px(0.6f), px(0.6f))
            cell.addView(box, LinearLayout.LayoutParams(s, s))
            val tv = label(1.35f, 0.7f)
            tv.gravity = Gravity.CENTER
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            val tlp = LinearLayout.LayoutParams(-1, -2)
            tlp.topMargin = px(0.7f)
            cell.addView(tv, tlp)
            focusFx(cell, box, r, tv)
            return Holder(cell, img, tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.img.setImageDrawable(item.icon)
            holder.label.text = item.label
            holder.cell.setOnClickListener { launch(item) }
            holder.cell.setOnLongClickListener { openInfo(item); true }
        }

        override fun getItemCount() = items.size
    }

    // ---------------------------------------------------------------- Apps

    private fun refreshApps() {
        io.execute {
            val all = loadApps()
            val sig = all.joinToString("|") { it.pkg }
            main.post {
                if (sig != lastSig) {
                    lastSig = sig
                    val split = splitPinned(all)
                    showApps(split.first, split.second)
                }
            }
        }
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val map = LinkedHashMap<String, AppItem>()
        val cats = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        for (cat in cats) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(i, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == packageName || map.containsKey(ai.packageName)) continue
                val label = ri.loadLabel(pm).toString()
                val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
                val banner = try { ai.loadBanner(pm) } catch (e: Exception) { null }
                map[ai.packageName] = AppItem(label, ai.packageName, ai.name, icon, banner)
            }
        }
        return map.values.toList()
    }

    private fun splitPinned(all: List<AppItem>): Pair<List<AppItem>, List<AppItem>> {
        val top = ArrayList<AppItem>()
        for (p in PINS) {
            val m = p.pkgs.firstNotNullOfOrNull { pk -> all.firstOrNull { it.pkg == pk } }
                ?: all.firstOrNull { a -> a !in top && p.keys.any { a.label.lowercase().contains(it) } }
            if (m != null && m !in top) top.add(m)
        }
        val rest = all.filter { it !in top }.sortedBy { it.label.lowercase() }
        return Pair(top, rest)
    }

    private fun showApps(top: List<AppItem>, rest: List<AppItem>) {
        pinRow.removeAllViews()
        for (t in top) pinRow.addView(buildPinned(t))
        pinRow.visibility = if (top.isEmpty()) View.GONE else View.VISIBLE
        grid.adapter = Adapter(rest)
        if (root.findFocus() == null) {
            (pinRow.getChildAt(0) ?: grid).requestFocus()
        }
    }

    private fun launch(item: AppItem) {
        try {
            val i = Intent(Intent.ACTION_MAIN).setClassName(item.pkg, item.cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInfo(item: AppItem) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${item.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- Clock

    private fun updateClock() {
        val now = Date()
        clockView.text = DateFormat.getTimeFormat(this).format(now)
        dateView.text = java.text.SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    // ---------------------------------------------------------------- Wallpaper

    private fun blend(a: Int, b: Int, t: Float): Int = ArgbEvaluator().evaluate(t, a, b) as Int

    private fun computeColors(): Pair<Int, Int> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val base: Pair<Int, Int> = when (h) {
            in 5..7 -> Pair(0xFF3B4A7A.toInt(), 0xFFE9A07A.toInt())    // dawn
            in 8..16 -> Pair(0xFF2D6CC0.toInt(), 0xFF7FB8E8.toInt())   // day
            in 17..19 -> Pair(0xFF3A2A5C.toInt(), 0xFFE0705A.toInt())  // dusk
            else -> Pair(0xFF050A1A.toInt(), 0xFF1B2A4A.toInt())       // night
        }
        val tint: Pair<Int, Float> = when (weatherCode) {
            2 -> Pair(0xFF6B7482.toInt(), 0.25f)
            3 -> Pair(0xFF5A6270.toInt(), 0.5f)
            45, 48 -> Pair(0xFF8A9099.toInt(), 0.55f)
            in 51..67, in 80..82 -> Pair(0xFF2F3744.toInt(), 0.6f)
            in 71..77, 85, 86 -> Pair(0xFFB8C4D6.toInt(), 0.4f)
            in 95..99 -> Pair(0xFF1C202A.toInt(), 0.7f)
            else -> Pair(0, 0f)
        }
        if (tint.second == 0f) return base
        return Pair(blend(base.first, tint.first, tint.second), blend(base.second, tint.first, tint.second))
    }

    private fun updateBackground() {
        val c = computeColors()
        if (c.first == curTop && c.second == curBottom) return
        val fromT = curTop
        val fromB = curBottom
        curTop = c.first
        curBottom = c.second
        bgAnim?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 2500
        anim.addUpdateListener {
            val f = it.animatedFraction
            bg.setColors(intArrayOf(blend(fromT, c.first, f), blend(fromB, c.second, f)))
        }
        anim.start()
        bgAnim = anim
    }

    // ---------------------------------------------------------------- Weather

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> ""
    }

    private fun fetchWeather() {
        io.execute {
            try {
                var lat = FALLBACK_LAT
                var lon = FALLBACK_LON
                var city = FALLBACK_CITY
                if (USE_IP_LOCATION) {
                    try {
                        val j = JSONObject(httpGet("https://ipwho.is/"))
                        if (j.optBoolean("success")) {
                            lat = j.getDouble("latitude")
                            lon = j.getDouble("longitude")
                            city = j.optString("city", FALLBACK_CITY)
                        }
                    } catch (e: Exception) {
                    }
                }
                val w = JSONObject(
                    httpGet(
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&current=temperature_2m,weather_code&timezone=auto"
                    )
                )
                val cur = w.getJSONObject("current")
                val temp = Math.round(cur.getDouble("temperature_2m"))
                val code = cur.getInt("weather_code")
                main.post {
                    weatherCode = code
                    tempView.text = "$temp°"
                    descView.text = describe(code) + " · " + city
                    updateBackground()
                }
            } catch (e: Exception) {
                // keep the last known weather
            }
        }
    }
}
