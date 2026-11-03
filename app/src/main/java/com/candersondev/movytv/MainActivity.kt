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
                     val image: String, val rating: String, val source: String, val id: Int = 0)
    private val bg = Color.rgb(12, 12, 14)
    private val panel = Color.rgb(31, 31, 35)
    private val accent = Color.rgb(229, 9, 20)
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
    private var playerOverlay: View? = null
    private var nativeVideo: VideoView? = null
    private var selected: Title? = null
    private var activeSection = "all"
    private var requestGeneration = 0
    private val favorites by lazy { getSharedPreferences("ca_movie_tv", MODE_PRIVATE) }
    private fun titleKey(title: Title) = title.type + "|" + title.name + "|" + title.year
    private fun isFavorite(title: Title) = favorites.getBoolean(titleKey(title), false)
    private fun toggleFavorite(title: Title) {
        favorites.edit().putBoolean(titleKey(title), !isFavorite(title)).apply()
        hideDetail()
        showDetail(title)
    }
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
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.url.scheme != "https"
                override fun onPageFinished(view: WebView, url: String) {
                    spinner.visibility = View.GONE
                    if (url.startsWith("https://www.movy.sx/") || url.startsWith("https://movy.sx/")) {
                        view.evaluateJavascript("""(function(){
                          if(!document.getElementById('ca-tv-focus')){
                            var s=document.createElement('style');s.id='ca-tv-focus';
                            s.textContent='a:focus,button:focus,input:focus,[tabindex]:focus,[role="button"]:focus{outline:5px solid #00e5c0 !important;outline-offset:3px !important;box-shadow:0 0 0 5px #000,0 0 24px #00e5c0 !important;z-index:999999 !important;position:relative !important}';
                            document.head.appendChild(s);
                          }
                          if(document.activeElement===document.body){
                            var first=document.querySelector('a[href],button,[tabindex="0"]');
                            if(first)first.focus();
                          }
                        })();""", null)
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullScreen != null) { callback.onCustomViewHidden(); return }
                    fullScreen = view
                    fullScreenCallback = callback
                    browser.evaluateJavascript("document.getElementById('ca-tv-selection-box')?.remove();", null)
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
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFillViewport = true
            clipToPadding = false
        }
        catalog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(48))
        }
        scroll.addView(catalog)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("CA", 34f, accent, true))
        header.addView(text("  MOVIE TV", 30f, Color.WHITE, true))
        header.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))

        header.addView(action("Movy Home") { openWebsite("https://www.movy.sx/") })
        catalog.addView(header)
        catalog.addView(text("Your next movie night starts here", 18f, Color.LTGRAY).apply {
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
        quick.addView(action("Movy Movies") { openWebsite("https://www.movy.sx/browse/movie") })
        quick.addView(action("Movy TV") { openWebsite("https://www.movy.sx/browse/tv") })
        quick.addView(action("Search") { performSearch(search.text.toString().trim()) })
        quick.addView(action("TV Shows") { renderHome("shows") })
        quick.addView(action("Movies") { renderHome("movies") })
        quick.addView(action("My List") { renderHome("favorites") })
        quick.addView(action("Home") { renderHome("all") })
        catalog.addView(quick)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catalog.addView(results)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        spinner = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        setContentView(root)
        quick.getChildAt(0).requestFocus()
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
        backgroundTintList = android.content.res.ColorStateList.valueOf(panel)
        setTextColor(Color.WHITE)
        setOnFocusChangeListener { _, focused ->
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (focused) accent else panel)
            setTextColor(if (focused) Color.BLACK else Color.WHITE)
            scaleX = if (focused) 1.04f else 1f
            scaleY = if (focused) 1.04f else 1f
        }
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
            if (rating > 0) String.format("%.1f", rating) else "—", "TVmaze", show.optInt("id"))
    }
    private fun movieFromFeed(item: JSONObject): Title = Title(
        item.optString("name", "Unknown"), "Movie",
        item.optString("releaseDate").take(4),
        item.optString("artistName", ""),
        item.optString("artworkUrl100").replace("100x100bb", "600x600bb"),
        "—", "Apple Charts")

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
                renderHome(activeSection)
            }
        }
        worker.execute {
            val chart = try {
                val feed = JSONObject(request("https://rss.applemarketingtools.com/api/v2/us/movies/top-movies/100/movies.json"))
                    .getJSONObject("feed").getJSONArray("results")
                (0 until feed.length()).map { movieFromFeed(feed.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
            val data = chart + listOf("action", "comedy", "drama", "thriller", "animation", "science fiction").flatMap { genre ->
                try {
                    val term = URLEncoder.encode(genre, "UTF-8")
                    val arr = JSONObject(request("https://itunes.apple.com/search?term=$term&media=movie&entity=movie&country=us&limit=45"))
                        .getJSONArray("results")
                    (0 until arr.length()).map { movieFromJson(arr.getJSONObject(it)) }
                } catch (_: Exception) { emptyList() }
            }.distinctBy { it.name.lowercase() + it.year }
            runOnUiThread {
                movies.clear(); movies.addAll(data.distinctBy { it.name.lowercase() + it.year })
                renderHome(activeSection)
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
                val arr = JSONObject(request("https://itunes.apple.com/search?term=$encoded&media=movie&entity=movie&country=us&limit=50"))
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
        activeSection = filter
        results.removeAllViews()
        if (filter == "favorites") {
            val saved = (movies + shows).filter { isFavorite(it) }
            if (saved.isEmpty()) renderMessage("Your List is empty. Open a title and select + My List.")
            else addRail("My List", saved)
            return
        }
        val featured = (movies.firstOrNull() ?: shows.firstOrNull())
        if (filter == "all" && featured != null) {
            val hero = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(26), dp(28), dp(26))
                background = rounded(panel, 16)
            }
            hero.addView(text("FEATURED TONIGHT", 14f, accent, true))
            hero.addView(text(featured.name, 36f, Color.WHITE, true))
            hero.addView(text(featured.description.take(230).ifBlank { featured.type + " · " + featured.year },
                17f, Color.LTGRAY).apply { setPadding(0, dp(10), 0, dp(14)); maxLines = 3 })
            hero.addView(action("View Details") { showDetail(featured) })
            results.addView(hero)
        }
        if (filter != "shows") {
            if (movies.isEmpty()) results.addView(text("Movies are loading or unavailable.", 17f, Color.LTGRAY))
            else {
                addRail("Movies For You", movies.take(45))
                if (movies.size > 45) addRail("More Movies", movies.drop(45).take(45))
            }
        }
        if (filter != "movies") {
            if (shows.isEmpty()) results.addView(text("TV shows are loading or unavailable.", 17f, Color.LTGRAY))
            else {
                addRail("TV Series", shows.take(40))
                if (shows.size > 40) addRail("More Series", shows.drop(40).take(40))
            }
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
                setOnFocusChangeListener { view, focused ->
                    if (focused) view.post { view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), false) }
                    background = rounded(if (focused) accent else panel, 12)
                    alpha = if (focused) 1f else 0.9f
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
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
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
        if (title.type == "TV Show" && title.id > 0) {
            overlay.addView(action("Browse seasons & episodes") { showEpisodes(title) })
        }
        overlay.addView(action(if (isFavorite(title)) "✓ Remove from My List" else "+ My List") { toggleFavorite(title) })
        overlay.addView(action("Browse playback on Movy") { hideDetail(); openWebsite(if (title.type == "Movie") "https://www.movy.sx/browse/movie" else "https://www.movy.sx/browse/tv") })
        overlay.addView(action("Back to catalog") { hideDetail() })
        detail = overlay
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.getChildAt(4).requestFocus()
    }
    private fun showEpisodes(title: Title) {
        hideDetail()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(65), dp(30), dp(65), dp(40))
            setBackgroundColor(bg)
        }
        scroll.addView(column)
        column.addView(text(title.name + " — Episodes", 30f, Color.WHITE, true))
        val back = action("Back to show") { hideDetail(); showDetail(title) }
        column.addView(back)
        val status = text("Loading seasons and episodes…", 19f, Color.LTGRAY)
        column.addView(status)
        detail = scroll
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        back.requestFocus()
        worker.execute {
            val episodes = try {
                val arr = JSONArray(request("https://api.tvmaze.com/shows/${title.id}/episodes?specials=1"))
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (detail !== scroll) return@runOnUiThread
                column.removeView(status)
                if (episodes.isEmpty()) {
                    column.addView(text("Episode information unavailable. Please try again later.", 18f, Color.LTGRAY))
                } else {
                    episodes.groupBy { it.optInt("season") }.toSortedMap().forEach { (season, items) ->
                        column.addView(text("Season $season", 25f, accent, true).apply {
                            setPadding(0, dp(18), 0, dp(8))
                        })
                        items.forEach { episode ->
                            val number = episode.optInt("number")
                            val name = episode.optString("name", "Episode")
                            val label = if (number > 0) "E$number — $name" else "Special — $name"
                            column.addView(action(label) {
                                val summary = clean(episode.optString("summary"))
                                AlertDialog.Builder(this)
                                    .setTitle("Season $season · $label")
                                    .setMessage((summary.ifBlank { "No episode description available." }) +
                                        "\\n\\nEpisode metadata does not include a playable video URL.")
                                    .setPositiveButton("OK", null)
                                    .setNeutralButton("Browse Movy TV") { _, _ -> openWebsite("https://www.movy.sx/browse/tv") }
                                    .show()
                            }.apply {
                                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                                maxLines = 2
                            }, LinearLayout.LayoutParams(-1, dp(64)))
                        }
                    }
                }
            }
        }
    }

    private fun hideDetail() {
        detail?.let { root.removeView(it) }
        detail = null
        selected = null
    }
    private fun openWebsite(url: String = "https://www.movy.sx/") {
        hideDetail()
        browsing = true
        browser.visibility = View.VISIBLE
        browser.bringToFront()
        spinner.bringToFront()
        spinner.visibility = View.VISIBLE
        if (browser.url != url) browser.loadUrl(url)
        else spinner.visibility = View.GONE
        browser.requestFocus()
    }
    private fun promptVideoUrl() {
        val field = EditText(this).apply {
            hint = "https://example.com/video.mp4"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("Play your video")
            .setMessage("Enter a direct HTTPS video URL that you are authorized to stream. A website page URL will not work.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Play") { _, _ ->
                val url = field.text.toString().trim()
                val uri = android.net.Uri.parse(url)
                if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
                    Toast.makeText(this, "Please enter a valid HTTPS video URL", Toast.LENGTH_LONG).show()
                } else {
                    playVideo(url)
                }
            }.show()
    }

    private fun playVideo(url: String) {
        stopVideo()
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = VideoView(this).apply {
            setVideoURI(android.net.Uri.parse(url))
            setOnPreparedListener { media ->
                media.isLooping = false
                start()
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, "Unable to play this video. Check the direct URL or codec.", Toast.LENGTH_LONG).show()
                stopVideo()
                true
            }
            setOnCompletionListener { stopVideo() }
        }
        nativeVideo = video
        frame.addView(video, FrameLayout.LayoutParams(-1, -1))
        val controller = MediaController(this).apply {
            setAnchorView(video)
            setMediaPlayer(video)
        }
        video.setMediaController(controller)
        playerOverlay = frame
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        frame.bringToFront()
        video.requestFocus()
        video.start()
    }

    private fun stopVideo() {
        nativeVideo?.stopPlayback()
        nativeVideo = null
        playerOverlay?.let { root.removeView(it) }
        playerOverlay = null
    }

    private fun browserNavigationScript(direction: String): String = """
        (function(){
          var dir='$direction', win=window;
          var playing=Array.from(document.querySelectorAll('video')).some(function(v){
            var r=v.getBoundingClientRect();
            return !v.paused && r.width>200 && r.height>100;
          });
          if(playing){
            var existing=document.getElementById('ca-tv-selection-box');
            if(existing)existing.style.display='none';
            win.__caTvSelected=null;
            return;
          }
          var selector='a[href],button,input,select,textarea,[role="button"],[role="link"],[tabindex]:not([tabindex="-1"]),video,[onclick],article,[class*="poster"],[class*="Poster"],[class*="card"],[class*="Card"]';
          var nodes=Array.from(document.querySelectorAll(selector)).filter(function(e){
            var r=e.getBoundingClientRect(),s=getComputedStyle(e);
            if(r.width<22||r.height<15||s.visibility==='hidden'||s.display==='none'||e.disabled)return false;
            if(e.tagName==='ARTICLE' || /poster|card/i.test(e.className||'')){
              return !Array.from(e.querySelectorAll('a[href],button,[onclick]')).some(function(child){
                var cr=child.getBoundingClientRect();return cr.width>15&&cr.height>15;
              });
            }
            return true;
          });
          if(!nodes.length)return;
          if(dir==='click'){
            var oldBox=document.getElementById('ca-tv-selection-box');
            if(oldBox)oldBox.style.display='none';
          }
          var old=win.__caTvSelected;
          var active=nodes.indexOf(old)>=0?old:document.activeElement;
          var marker=document.getElementById('ca-tv-selection-box');
          if(!marker){
            marker=document.createElement('div');marker.id='ca-tv-selection-box';
            marker.style.cssText='position:fixed;pointer-events:none;z-index:2147483647;border:5px solid #ff3045;border-radius:9px;box-shadow:0 0 0 3px white,0 0 22px 8px rgba(229,9,20,.8);box-sizing:border-box;transition:top .1s,left .1s;display:none';
            document.documentElement.appendChild(marker);
          }
          function show(el){
            if(!el)return;
            win.__caTvSelected=el;
            if(!el.hasAttribute('tabindex')&&!/^(A|BUTTON|INPUT|SELECT|TEXTAREA|VIDEO)$/.test(el.tagName))el.setAttribute('tabindex','-1');
            el.focus({preventScroll:true});
            el.scrollIntoView({block:'center',inline:'center'});
            function draw(){
              var r=el.getBoundingClientRect();
              marker.style.left=Math.max(0,r.left-4)+'px';
              marker.style.top=Math.max(0,r.top-4)+'px';
              marker.style.width=Math.min(innerWidth-Math.max(0,r.left-4),r.width+8)+'px';
              marker.style.height=Math.min(innerHeight-Math.max(0,r.top-4),r.height+8)+'px';
              marker.style.display='block';
            }
            requestAnimationFrame(draw);
            win.__caTvDraw=draw;
          }
          if(!win.__caTvTracking){
            win.__caTvTracking=true;
            window.addEventListener('scroll',function(){if(win.__caTvDraw)win.__caTvDraw();},true);
            window.addEventListener('resize',function(){if(win.__caTvDraw)win.__caTvDraw();});
          }
          if(dir==='click'){
            if(active&&active!==document.body){
              if(active.tagName==='VIDEO'){if(active.paused)active.play();else active.pause();}
              else active.click();
              show(active);
            }else show(nodes[0]);
            return;
          }
          if(nodes.indexOf(active)<0){show(nodes[0]);return;}
          var a=active.getBoundingClientRect(),ax=a.left+a.width/2,ay=a.top+a.height/2;
          var best=null,score=Infinity;
          nodes.forEach(function(el){
            if(el===active)return;
            var r=el.getBoundingClientRect(),x=r.left+r.width/2-ax,y=r.top+r.height/2-ay;
            var forward=dir==='left'?-x:dir==='right'?x:dir==='up'?-y:y;
            if(forward<=5)return;
            var lateral=(dir==='left'||dir==='right')?Math.abs(y):Math.abs(x);
            var cost=forward+lateral*3;
            if(cost<score){score=cost;best=el;}
          });
          if(best)show(best);
          else window.scrollBy(0,dir==='down'?280:dir==='up'?-280:0);
        })();
    """.trimIndent()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (browsing && fullScreen == null && playerOverlay == null && event.action == KeyEvent.ACTION_DOWN) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "click"
                else -> null
            }
            if (direction != null) {
                browser.evaluateJavascript(browserNavigationScript(direction), null)
                return true
            }
        }
        if (playerOverlay != null && event.action == KeyEvent.ACTION_DOWN) {
            val video = nativeVideo
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    video?.let { if (it.isPlaying) it.pause() else it.start() }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { video?.start(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { video?.pause(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    video?.seekTo(((video.currentPosition - 10000).coerceAtLeast(0)))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    video?.seekTo(video.currentPosition + 10000)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            playerOverlay != null -> stopVideo()
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
        stopVideo()
        worker.shutdownNow()
        browser.destroy()
        super.onDestroy()
    }
}
