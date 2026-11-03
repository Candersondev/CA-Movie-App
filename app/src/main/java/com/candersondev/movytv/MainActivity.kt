package com.candersondev.movytv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Native Android TV discovery UI; external catalogs supply metadata, not playback streams. */
class MainActivity : Activity() {
    data class Title(val name: String, val type: String, val year: String, val description: String,
                     val image: String, val rating: String, val source: String)
    private val bg = Color.rgb(10, 14, 26)
    private val panel = Color.rgb(25, 33, 52)
    private val accent = Color.rgb(0, 202, 178)
    private val worker = Executors.newFixedThreadPool(4)
    private lateinit var root: FrameLayout
    private lateinit var catalog: LinearLayout
    private lateinit var browser: WebView
    private lateinit var spinner: ProgressBar
    private lateinit var results: LinearLayout
    private lateinit var search: EditText
    private var fullScreen: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var browsing = false
    private var detail: View? = null
    private var selected: Title? = null
    private val shows = mutableListOf<Title>()
    private val movies = mutableListOf<Title>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        browser = WebView(this).apply {
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.url.scheme != "https"
                override fun onPageFinished(view: WebView, url: String) {
                    spinner.visibility = View.GONE
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullScreen != null) { callback.onCustomViewHidden(); return }
                    fullScreen = view
                    fullScreenCallback = callback
                    browser.visibility = View.GONE
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                }
                override fun onHideCustomView() {
                    fullScreen?.let { root.removeView(it) }
                    fullScreen = null
                    fullScreenCallback?.onCustomViewHidden()
                    fullScreenCallback = null
                    browser.visibility = View.VISIBLE
                    browser.requestFocus()
                }
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        catalog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(48))
        }
        scroll.addView(catalog)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("MOVY", 34f, Color.WHITE, true))
        header.addView(text("  TV", 34f, accent, true))
        header.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(action("Open website") { openWebsite() })
        catalog.addView(header)
        catalog.addView(text("Discover movies and television", 18f, Color.LTGRAY).apply {
            setPadding(0, dp(8), 0, dp(22))
        })
        search = EditText(this).apply {
            hint = "Search movies or TV shows"
            setSingleLine(true)
            textSize = 19f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(panel, 12)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ ->
                performSearch(text.toString().trim()); true
            }
        }
        catalog.addView(search, LinearLayout.LayoutParams(-1, dp(58)))
        val quick = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        quick.addView(action("Search") { performSearch(search.text.toString().trim()) })
        quick.addView(action("TV Shows") { renderHome("shows") })
        quick.addView(action("Movies") { renderHome("movies") })
        quick.addView(action("Home") { renderHome("all") })
        catalog.addView(quick)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catalog.addView(results)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        spinner = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        setContentView(root)
        search.requestFocus()
        renderMessage("Loading movie and TV catalogs…")
        loadHome()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun action(value: String, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        isFocusable = true
        setOnClickListener { click() }
    }
    private fun renderMessage(message: String) {
        results.removeAllViews()
        results.addView(text(message, 19f, Color.LTGRAY))
    }
    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 9000
        connection.readTimeout = 9000
        connection.setRequestProperty("User-Agent", "CA-Movie-App/1.1")
        return try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private fun tvFromJson(item: JSONObject): Title {
        val show = item.optJSONObject("show") ?: item
        val img = show.optJSONObject("image")
        val rating = show.optJSONObject("rating")?.optDouble("average", 0.0) ?: 0.0
        return Title(show.optString("name", "Unknown"), "TV Show",
            show.optString("premiered").take(4), clean(show.optString("summary")),
            img?.optString("medium").orEmpty().replace("/medium/", "/original/"),
            if (rating > 0) String.format("%.1f", rating) else "—", "TVmaze")
    }
    private fun movieFromJson(item: JSONObject): Title = Title(
        item.optString("trackName", item.optString("collectionName", "Unknown")),
        "Movie", item.optString("releaseDate").take(4),
        item.optString("longDescription", item.optString("shortDescription", "")),
        item.optString("artworkUrl100").replace("100x100bb", "600x600bb"),
        "—", "Apple Search API")
    private fun clean(html: String) = android.text.Html.fromHtml(
        html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun loadHome() {
        worker.execute {
            val data = try {
                val arr = JSONArray(request("https://api.tvmaze.com/shows?page=0"))
                (0 until minOf(arr.length(), 80)).map { tvFromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                shows.clear(); shows.addAll(data)
                renderHome("all")
            }
        }
        worker.execute {
            val data = try {
                val arr = JSONObject(request("https://itunes.apple.com/search?term=movie&entity=movie&limit=70"))
                    .getJSONArray("results")
                (0 until arr.length()).map { movieFromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                movies.clear(); movies.addAll(data)
                renderHome("all")
            }
        }
    }
    private fun performSearch(term: String) {
        if (term.isBlank()) { renderHome("all"); return }
        renderMessage("Searching for “$term”…")
        worker.execute {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val tv = try {
                val arr = JSONArray(request("https://api.tvmaze.com/search/shows?q=$encoded"))
                (0 until arr.length()).map { tvFromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
            val film = try {
                val arr = JSONObject(request("https://itunes.apple.com/search?term=$encoded&entity=movie&limit=50"))
                    .getJSONArray("results")
                (0 until arr.length()).map { movieFromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                results.removeAllViews()
                if (tv.isEmpty() && film.isEmpty()) renderMessage("No results. Check your internet connection or try another search.")
                else {
                    if (film.isNotEmpty()) addRail("Movie results", film)
                    if (tv.isNotEmpty()) addRail("TV results", tv)
                }
            }
        }
    }
    private fun renderHome(filter: String) {
        results.removeAllViews()
        if (filter != "shows") {
            if (movies.isEmpty()) results.addView(text("Movies are loading or unavailable.", 17f, Color.LTGRAY))
            else addRail("Movies", movies)
        }
        if (filter != "movies") {
            if (shows.isEmpty()) results.addView(text("TV shows are loading or unavailable.", 17f, Color.LTGRAY))
            else addRail("TV Shows", shows.take(40))
        }
    }
    private fun addRail(heading: String, titles: List<Title>) {
        results.addView(text(heading, 25f, Color.WHITE, true).apply {
            setPadding(0, dp(22), 0, dp(12))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titles.take(45).forEach { title ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                background = rounded(panel, 12)
                setPadding(dp(7), dp(7), dp(7), dp(10))
                setOnClickListener { showDetail(title) }
                setOnFocusChangeListener { _, focused ->
                    alpha = if (focused) 1f else 0.84f
                    scaleX = if (focused) 1.06f else 1f
                    scaleY = if (focused) 1.06f else 1f
                    elevation = if (focused) dp(14).toFloat() else 0f
                }
            }
            val poster = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(40, 52, 70))
                contentDescription = title.name
            }
            card.addView(poster, LinearLayout.LayoutParams(dp(155), dp(225)))
            card.addView(text(title.name, 15f, Color.WHITE, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(dp(155), dp(44)))
            card.addView(text("${title.year}  ·  ${title.type}", 12f, Color.LTGRAY))
            val lp = LinearLayout.LayoutParams(dp(169), dp(314)).apply { rightMargin = dp(14) }
            row.addView(card, lp)
            loadPoster(title.image, poster)
        }
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        horizontal.addView(row)
        results.addView(horizontal)
    }
    private fun loadPoster(url: String, image: ImageView) {
        if (!url.startsWith("https://")) return
        worker.execute {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                val bitmap = try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally { connection.disconnect() }
                if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap) }
            } catch (_: Exception) { }
        }
    }
    private fun showDetail(title: Title) {
        selected = title
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(80), dp(50), dp(80), dp(50))
            setBackgroundColor(bg)
        }
        overlay.addView(text(title.name, 38f, Color.WHITE, true))
        overlay.addView(text("${title.type}  •  ${title.year}  •  Rating: ${title.rating}", 19f, accent).apply {
            setPadding(0, dp(16), 0, dp(20))
        })
        overlay.addView(text(title.description.ifBlank { "No description available." }.take(900), 19f, Color.LTGRAY).apply {
            setPadding(0, 0, 0, dp(24))
        })
        overlay.addView(text("Metadata: ${title.source}. Availability and playback are not provided by this catalog.", 15f, Color.LTGRAY))
        overlay.addView(action("Browse on Movy website") { hideDetail(); openWebsite() })
        overlay.addView(action("Back to catalog") { hideDetail() })
        detail = overlay
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.getChildAt(4).requestFocus()
    }
    private fun hideDetail() {
        detail?.let { root.removeView(it) }
        detail = null
        selected = null
    }
    private fun openWebsite() {
        hideDetail()
        browsing = true
        browser.visibility = View.VISIBLE
        browser.bringToFront()
        spinner.bringToFront()
        spinner.visibility = View.VISIBLE
        if (browser.url == null) browser.loadUrl("https://movy.sx/")
        else spinner.visibility = View.GONE
        browser.requestFocus()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullScreen != null -> browser.webChromeClient?.onHideCustomView()
            browsing && browser.canGoBack() -> browser.goBack()
            browsing -> {
                browsing = false
                browser.visibility = View.GONE
                spinner.visibility = View.GONE
                search.requestFocus()
            }
            detail != null -> hideDetail()
            else -> super.onBackPressed()
        }
    }
    override fun onDestroy() {
        worker.shutdownNow()
        browser.destroy()
        super.onDestroy()
    }
}
