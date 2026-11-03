package com.candersondev.movytv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.graphics.Color

/** TV-compatible website shell. Playback depends on website player compatibility and permissions. */
class MainActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var container: FrameLayout
    private lateinit var loading: ProgressBar
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        browser = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return request.url.scheme !in listOf("https", "http")
                }
                override fun onPageFinished(view: WebView, url: String) { loading.visibility = View.GONE }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullScreenView != null) { callback.onCustomViewHidden(); return }
                    fullScreenView = view; fullScreenCallback = callback
                    browser.visibility = View.GONE
                    container.addView(view, FrameLayout.LayoutParams(-1, -1))
                }
                override fun onHideCustomView() {
                    fullScreenView?.let { container.removeView(it) }
                    fullScreenView = null
                    fullScreenCallback?.onCustomViewHidden(); fullScreenCallback = null
                    browser.visibility = View.VISIBLE
                }
            }
        }
        loading = ProgressBar(this).apply { isIndeterminate = true }
        container.addView(browser, FrameLayout.LayoutParams(-1, -1))
        container.addView(loading, FrameLayout.LayoutParams(90, 90, android.view.Gravity.CENTER))
        setContentView(container)
        browser.loadUrl("https://movy.sx/")
        browser.requestFocus()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when { fullScreenView != null -> (browser.webChromeClient as? WebChromeClient)?.onHideCustomView()
            browser.canGoBack() -> browser.goBack()
            else -> super.onBackPressed() }
    }
    override fun onDestroy() { browser.destroy(); super.onDestroy() }
}
