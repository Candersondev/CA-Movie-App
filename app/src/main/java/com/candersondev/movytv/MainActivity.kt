package com.candersondev.movytv

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Native, remote-focused Android TV launcher with an embedded website browser.
 * A native title catalog requires a permitted, stable catalog feed from the site.
 */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var browser: WebView
    private lateinit var loading: ProgressBar
    private var fullScreen: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var browsing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(13, 16, 29)) }
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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme != "https"
                override fun onPageFinished(view: WebView, url: String) {
                    loading.visibility = View.GONE
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
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(72, 40, 72, 40)
        }
        val heading = TextView(this).apply {
            text = "MOVY TV"
            textSize = 42f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val subtitle = TextView(this).apply {
            text = "Watch on your TV"
            textSize = 20f
            setTextColor(Color.LTGRAY)
            setPadding(0, 12, 0, 36)
        }
        home.addView(heading)
        home.addView(subtitle)
        home.addView(tvButton("Browse Movy") { openWebsite() })
        home.addView(tvButton("Continue browsing") { openWebsite(false) })
        home.addView(tvButton("About") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Movy TV")
                .setMessage("TV launcher for movy.sx. Content and playback depend on the website. Use your remote to navigate the page; Back returns here.")
                .setPositiveButton("OK", null).show()
        })
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        loading = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(loading, FrameLayout.LayoutParams(80, 80, Gravity.CENTER))
        setContentView(root)
        home.getChildAt(2).requestFocus()
    }

    private fun tvButton(title: String, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 21f
            isAllCaps = false
            isFocusable = true
            setOnClickListener { action() }
            val params = LinearLayout.LayoutParams(420, 76)
            params.bottomMargin = 18
            layoutParams = params
        }

    private fun openWebsite(reset: Boolean = true) {
        browsing = true
        home.visibility = View.GONE
        browser.visibility = View.VISIBLE
        loading.visibility = View.VISIBLE
        if (reset || browser.url == null) browser.loadUrl("https://movy.sx/")
        else loading.visibility = View.GONE
        browser.requestFocus()
    }

    private fun showHome() {
        browsing = false
        browser.visibility = View.GONE
        home.visibility = View.VISIBLE
        loading.visibility = View.GONE
        home.getChildAt(2).requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullScreen != null -> browser.webChromeClient?.onHideCustomView()
            browsing && browser.canGoBack() -> browser.goBack()
            browsing -> showHome()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }
}
