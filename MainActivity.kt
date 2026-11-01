package com.htmltoapk.studio

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Ayni sinif iki is yapar:
 *  - assets/www/index.html YOKSA: APK uretici arayuzu (bu uygulama)
 *  - VARSA: uretilmis uygulama; siteyi WebView'da acar.
 */
class MainActivity : Activity() {
    private var web: WebView? = null
    private var picked: Uri? = null
    private var builtUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var installBtn: Button
    private lateinit var fileTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isSite = try { assets.open("www/index.html").close(); true } catch (e: Exception) { false }
        if (isSite) showSite() else showBuilder()
    }

    private fun showSite() {
        val w = WebView(this)
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = WebViewClient()
        w.webChromeClient = WebChromeClient()
        w.fitsSystemWindows = true
        setContentView(w)
        w.loadUrl("file:///android_asset/www/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    private fun showBuilder() {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, pad / 2, 0, 0) }

        val title = TextView(this).apply { text = "HTML → APK"; textSize = 26f; gravity = Gravity.START }
        val nameIn = EditText(this).apply { hint = "Uygulama adı"; setSingleLine() }
        val pkgIn = EditText(this).apply {
            hint = "Paket adı (örn. com.benim.uygulama)"; setSingleLine(); setText("com.example.app")
        }
        val pickBtn = Button(this).apply { text = "HTML veya ZIP seç" }
        fileTv = TextView(this).apply { text = "Dosya seçilmedi" }
        val buildBtn = Button(this).apply { text = "APK oluştur" }
        status = TextView(this).apply { setTextColor(Color.DKGRAY); setPadding(0, pad / 2, 0, 0) }
        installBtn = Button(this).apply { text = "Yükle"; visibility = View.GONE }

        pickBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "application/zip", "application/x-zip-compressed"))
            }
            startActivityForResult(i, 1)
        }

        buildBtn.setOnClickListener {
            val uri = picked
            val name = nameIn.text.toString().trim()
            val pkg = pkgIn.text.toString().trim()
            when {
                uri == null -> status.text = "Önce bir HTML veya ZIP seçin."
                name.isEmpty() -> status.text = "Uygulama adı girin."
                !Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(pkg) ->
                    status.text = "Geçersiz paket adı. Küçük harf, rakam ve en az bir nokta kullanın."
                else -> {
                    status.text = "Oluşturuluyor…"
                    buildBtn.isEnabled = false
                    installBtn.visibility = View.GONE
                    thread {
                        try {
                            val input = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                            val apk = ApkBuilder.build(this, input, name, pkg)
                            val saved = saveToDownloads(apk, name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".apk")
                            apk.delete()
                            builtUri = saved
                            runOnUiThread {
                                status.text = "Hazır! İndirilenler klasörüne kaydedildi."
                                installBtn.visibility = View.VISIBLE
                                buildBtn.isEnabled = true
                            }
                        } catch (e: Throwable) {
                            runOnUiThread { status.text = "Hata: ${e.message ?: e.javaClass.simpleName}"; buildBtn.isEnabled = true }
                        }
                    }
                }
            }
        }

        installBtn.setOnClickListener {
            val u = builtUri ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(u, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                status.text = "Otomatik kurulamadı; APK'yı İndirilenler'den açın."
            }
        }

        listOf(title, label("Uygulama adı"), nameIn, label("Paket adı"), pkgIn, label("Site dosyası"),
            pickBtn, fileTv, buildBtn, status, installBtn).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) {
            picked = data?.data
            fileTv.text = picked?.lastPathSegment ?: "Dosya seçildi"
        }
    }

    private fun saveToDownloads(f: java.io.File, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        return uri
    }
}
