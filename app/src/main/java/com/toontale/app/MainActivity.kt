package com.toontale.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.Locale

class MainActivity : Activity() {
    private var web: WebView? = null
    private var tts: TextToSpeech? = null
    private var ttsState = "init"
    private var saveOut: OutputStream? = null
    private var saveUri: Uri? = null
    private var saveMime: String = "video/mp4"
    private var lastUri: Uri? = null
    private var lastMime: String = "video/mp4"

    // ---------------------------------------------------------------- bridge used by the page
    inner class Bridge {
        @JavascriptInterface
        fun ttsStatus(): String = ttsState

        @JavascriptInterface
        fun ttsVoices(): String {
            val arr = JSONArray()
            try {
                val list = tts?.voices
                if (list != null) {
                    for (v in list) {
                        val o = JSONObject()
                        o.put("name", v.name)
                        o.put("lang", v.locale.toLanguageTag())
                        o.put("quality", v.quality)
                        o.put("network", v.isNetworkConnectionRequired)
                        arr.put(o)
                    }
                }
            } catch (e: Exception) {
                // no voices available
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun ttsSpeak(id: String, text: String, voice: String, lang: String, pitch: Float, rate: Float) {
            val engine = tts
            if (engine == null || ttsState != "ready") {
                reply(id, false, "")
                return
            }
            try {
                var chosen = false
                if (voice.isNotEmpty()) {
                    val vs = engine.voices
                    if (vs != null) {
                        for (v in vs) {
                            if (v.name == voice) {
                                engine.setVoice(v)
                                chosen = true
                                break
                            }
                        }
                    }
                }
                if (!chosen) {
                    engine.setLanguage(Locale.forLanguageTag(lang))
                }
                engine.setPitch(pitch)
                engine.setSpeechRate(rate)
                val f = File(cacheDir, "tts_" + id + ".wav")
                val r = engine.synthesizeToFile(text, null, f, id)
                if (r != TextToSpeech.SUCCESS) reply(id, false, "")
            } catch (e: Exception) {
                reply(id, false, "")
            }
        }

        @JavascriptInterface
        fun awake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun saveBegin(name: String, mime: String): Boolean {
            return try {
                val values = ContentValues()
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                values.put(MediaStore.Video.Media.MIME_TYPE, mime)
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ToonTale")
                values.put(MediaStore.Video.Media.IS_PENDING, 1)
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    false
                } else {
                    saveUri = uri
                    saveMime = mime
                    saveOut = contentResolver.openOutputStream(uri)
                    saveOut != null
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun saveChunk(b64: String) {
            try {
                saveOut?.write(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Exception) {
                // ignored, the final size is checked by the user when playing
            }
        }

        @JavascriptInterface
        fun saveEnd(): String {
            try {
                saveOut?.flush()
                saveOut?.close()
                val uri = saveUri
                if (uri != null) {
                    val values = ContentValues()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    lastUri = uri
                    lastMime = saveMime
                }
            } catch (e: Exception) {
                // ignored
            }
            saveOut = null
            return lastUri?.toString() ?: ""
        }

        @JavascriptInterface
        fun shareLast() {
            val u = lastUri ?: return
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND)
                i.type = lastMime
                i.putExtra(Intent.EXTRA_STREAM, u)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, "Share your video"))
            }
        }

        @JavascriptInterface
        fun openLast() {
            val u = lastUri ?: return
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_VIEW)
                    i.setDataAndType(u, lastMime)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(i)
                } catch (e: Exception) {
                    // no video player installed
                }
            }
        }
    }

    private fun reply(id: String, ok: Boolean, b64: String) {
        runOnUiThread {
            web?.evaluateJavascript("window.onTts('" + id + "'," + ok + ",'" + b64 + "')", null)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsState = "ready"
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        val id = utteranceId ?: return
                        try {
                            val f = File(cacheDir, "tts_" + id + ".wav")
                            val bytes = f.readBytes()
                            f.delete()
                            reply(id, true, Base64.encodeToString(bytes, Base64.NO_WRAP))
                        } catch (e: Exception) {
                            reply(id, false, "")
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        reply(utteranceId ?: "", false, "")
                    }
                })
            } else {
                ttsState = "fail"
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(Color.parseColor("#0f0b1e"))
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.addJavascriptInterface(Bridge(), "Android")
        w.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                runOnUiThread { rebuild() }
                return true
            }
        }
        w.loadUrl("file:///android_asset/index.html")
        return w
    }

    private fun rebuild() {
        web?.destroy()
        val w = makeWeb()
        web = w
        setContentView(w)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initTts()
        val w = makeWeb()
        web = w
        setContentView(w)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w == null) {
            finish()
            return
        }
        w.evaluateJavascript("(window.onBack && window.onBack()) ? '1' : '0'") { r ->
            if (r != "\"1\"") finish()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        web?.destroy()
        super.onDestroy()
    }
}
