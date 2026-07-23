package com.aggregatorx.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.aggregatorx.app.R
import com.aggregatorx.app.engine.ml.AnalysisHelper
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class WebViewActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var expectedUrl: String = ""
    private var providerId: String = "global"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(R.anim.slide_in_right, android.R.anim.fade_out)
        expectedUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty().ifBlank { "global" }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6, Gravity.TOP)
        }
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = EngineUtils.DEFAULT_USER_AGENT
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@WebViewActivity.progress.progress = newProgress
                    this@WebViewActivity.progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                override fun onPageFinished(view: WebView, url: String) {
                    this@WebViewActivity.progress.visibility = View.GONE
                    lifecycleScope.launch {
                        AnalysisHelper.repairUrls("""{"expected":"$expectedUrl","actual":"$url","title":"${view.title.orEmpty()}"}""", providerId)
                            .collect()
                    }
                }
            }
        }
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { finish() }
            layoutParams = FrameLayout.LayoutParams(96, 96, Gravity.TOP or Gravity.END).apply { topMargin = 24; rightMargin = 16 }
        }
        root.addView(webView)
        root.addView(progress)
        root.addView(close)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        if (expectedUrl.isNotBlank()) webView.loadUrl(expectedUrl)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, R.anim.slide_out_right)
    }

    override fun onDestroy() {
        runCatching {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_PROVIDER_ID = "provider_id"
        fun intent(context: Context, url: String, providerId: String): Intent =
            Intent(context, WebViewActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_PROVIDER_ID, providerId)
    }
}
