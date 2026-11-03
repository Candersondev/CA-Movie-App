package com.candersondev.movytv

import android.app.Activity
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar

/** Dedicated Android TV browser. Movy owns its catalog, episode selection and playback. */
class MainActivity : Activity() {
    private val home = "https://www.movy.sx/"
    private lateinit var root: FrameLayout
    private lateinit var browser: WebView
    private lateinit var loading: ProgressBar
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private lateinit var cursor: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        browser = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    return scheme != "https" && scheme != "http"
                }
                override fun onPageFinished(view: WebView, url: String) {
                    loading.visibility = View.GONE
                    cursor.visibility = View.VISIBLE
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loading.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullscreen != null) { callback.onCustomViewHidden(); return }
                    fullscreen = view
                    fullscreenCallback = callback
                    cursor.visibility = View.GONE
                    browser.visibility = View.GONE
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    view.requestFocus()
                }
                override fun onHideCustomView() { exitFullscreen() }
            }
        }
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        cursor = object : View(this) {
            private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f * resources.displayMetrics.density }
            private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 9, 20) }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (cursorX == 0f && cursorY == 0f) { cursorX = width / 2f; cursorY = height / 2f }
                val radius = 11f * resources.displayMetrics.density
                canvas.drawCircle(cursorX, cursorY, radius, center)
                canvas.drawCircle(cursorX, cursorY, radius, ring)
            }
        }
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        loading = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(loading, FrameLayout.LayoutParams(64, 64, android.view.Gravity.CENTER))
        if (savedInstanceState != null) browser.restoreState(savedInstanceState)
        else browser.loadUrl(home)
        browser.requestFocus()
    }

    private fun exitFullscreen() {
        fullscreen?.let { root.removeView(it) }
        fullscreen = null
        val callback = fullscreenCallback
        fullscreenCallback = null
        callback?.onCustomViewHidden()
        browser.visibility = View.VISIBLE
        cursor.visibility = View.VISIBLE
        browser.requestFocus()
    }

    private fun clickAtCursor() {
        val x = cursorX.coerceIn(1f, (browser.width - 2).coerceAtLeast(1).toFloat())
        val y = cursorY.coerceIn(1f, (browser.height - 2).coerceAtLeast(1).toFloat())
        val now = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
        browser.dispatchTouchEvent(down)
        browser.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullscreen == null && event.action == KeyEvent.ACTION_DOWN) {
            val step = 28f * resources.displayMetrics.density * (if (event.repeatCount > 4) 1.8f else 1f)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
                KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += step
                KeyEvent.KEYCODE_DPAD_UP -> cursorY -= step
                KeyEvent.KEYCODE_DPAD_DOWN -> cursorY += step
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { clickAtCursor(); return true }
                else -> return super.dispatchKeyEvent(event)
            }
            val edge = 18f * resources.displayMetrics.density
            cursorX = cursorX.coerceIn(edge, (browser.width.toFloat() - edge).coerceAtLeast(edge))
            cursorY = cursorY.coerceIn(edge, (browser.height.toFloat() - edge).coerceAtLeast(edge))
            if (cursorY <= edge && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) browser.scrollBy(0, -step.toInt() * 2)
            if (cursorY >= browser.height - edge && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) browser.scrollBy(0, step.toInt() * 2)
            cursor.invalidate()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreen != null -> exitFullscreen()
            browser.canGoBack() -> browser.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        browser.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        browser.stopLoading()
        browser.destroy()
        super.onDestroy()
    }
}
