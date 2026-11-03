package com.candersondev.movytv

import android.app.Activity
import android.graphics.Color
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
                    view.evaluateJavascript(focusScript("init"), null)
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
                    browser.visibility = View.GONE
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    view.requestFocus()
                }
                override fun onHideCustomView() { exitFullscreen() }
            }
        }
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
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
        browser.requestFocus()
    }

    private fun focusScript(direction: String): String {
        // Focus only visible controls; never infer playback or episode URLs.
        return """
        (function() {
          if (document.fullscreenElement || document.querySelector('video:not([paused])') &&
              Array.from(document.querySelectorAll('video')).some(v => !v.paused)) return;
          var old=document.getElementById('ca-tv-outline');
          if(old)old.remove();
          var elements=Array.from(document.querySelectorAll('a[href],button,input,select,textarea,[role="button"],[tabindex]'))
            .filter(function(e) {
              var r=e.getBoundingClientRect(),s=getComputedStyle(e);
              return r.width>5 && r.height>5 && s.visibility!=='hidden' && s.display!=='none' &&
                !e.disabled && e.getAttribute('tabindex')!=='-1';
            });
          if(!elements.length)return;
          var current=window.__caTvElement;
          if(!current || !elements.includes(current)) current=document.activeElement;
          if(!elements.includes(current))current=elements[0];
          if('$direction'==='click') { current.click(); return; }
          if('$direction'!=='init') {
            var a=current.getBoundingClientRect(),ax=a.left+a.width/2,ay=a.top+a.height/2;
            var best=null,score=Infinity;
            elements.forEach(function(e){
              if(e===current)return;
              var r=e.getBoundingClientRect(),dx=r.left+r.width/2-ax,dy=r.top+r.height/2-ay;
              if('$direction'==='left' && dx>=-4)return;
              if('$direction'==='right' && dx<=4)return;
              if('$direction'==='up' && dy>=-4)return;
              if('$direction'==='down' && dy<=4)return;
              var main=('$direction'==='left'||'$direction'==='right')?Math.abs(dx):Math.abs(dy);
              var cross=('$direction'==='left'||'$direction'==='right')?Math.abs(dy):Math.abs(dx);
              var value=main+cross*2.5;
              if(value<score){score=value;best=e;}
            });
            if(best)current=best;
          }
          window.__caTvElement=current;
          current.focus({preventScroll:true});
          current.scrollIntoView({block:'nearest',inline:'nearest'});
          var box=document.createElement('div');box.id='ca-tv-outline';
          var r=current.getBoundingClientRect();
          box.style.cssText='position:fixed;pointer-events:none;z-index:2147483647;border:4px solid #e50914;box-shadow:0 0 0 2px white;border-radius:5px;left:'+(r.left-4)+'px;top:'+(r.top-4)+'px;width:'+r.width+'px;height:'+r.height+'px';
          document.documentElement.appendChild(box);
        })();
        """.trimIndent()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullscreen == null && event.action == KeyEvent.ACTION_DOWN) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "click"
                else -> null
            }
            if (direction != null) {
                browser.evaluateJavascript(focusScript(direction), null)
                return true
            }
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
