package com.keramot.hisabarkhata

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var pageReady = false
    private var latest: Updates.Info? = null
    private var downloading = false
    private var waitingInstallPermission = false
    private var startUpdateWhenFound = false

    private val apkFile by lazy { File(File(cacheDir, "update").apply { mkdirs() }, "HisabKhata.apk") }
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = fileCb; fileCb = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0A5A4E"))
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            setSupportMultipleWindows(false)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return try { pickFile.launch(i); true } catch (_: Exception) { fileCb = null; false }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                openOutside(u)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (pageReady) return
                pageReady = true
                checkForUpdate(manual = false)
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)

        Updates.schedule(this)
        Reminders.schedule(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        js("window.hkPause && hkPause()")
    }

    override fun onResume() {
        super.onResume()
        if (pageReady) js("window.hkResume && hkResume()")
        if (waitingInstallPermission && canInstall()) {
            waitingInstallPermission = false
            installApk()
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(Updates.EXTRA_START_UPDATE, false) == true) {
            i.removeExtra(Updates.EXTRA_START_UPDATE)
            startUpdateWhenFound = true
            if (pageReady) checkForUpdate(manual = false)
        }
    }

    // ---------- ওয়েব পেজকে খবর পাঠানো ----------
    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }
    private fun q(s: String) = JSONObject.quote(s)
    private fun toast(msg: String) = js("window.hkToast && hkToast(${q(msg)})")

    // ---------- IMO / Messenger / অন্য অ্যাপে তাগাদা ----------
    private fun shareText(target: String, text: String) {
        val (label, pkgs) = when (target) {
            "imo" -> "IMO" to listOf("com.imo.android.imoim", "com.imo.android.imoimlite", "com.imo.android.imoimbeta")
            "messenger" -> "Messenger" to listOf("com.facebook.orca", "com.facebook.mlite")
            "whatsapp" -> "WhatsApp" to listOf("com.whatsapp", "com.whatsapp.w4b")
            else -> "" to emptyList()
        }
        val base = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (pkgs.isEmpty()) {
            try { startActivity(Intent.createChooser(base, "তাগাদা পাঠান")) } catch (_: Exception) { toast("পাঠানোর মতো অ্যাপ পাওয়া যায়নি") }
            return
        }
        for (p in pkgs) {
            try { startActivity(Intent(base).setPackage(p)); toast("$label খুলছে, যাকে পাঠাবেন তাকে বেছে নিন"); return } catch (_: ActivityNotFoundException) {}
        }
        toast("ফোনে $label পাওয়া যায়নি")
    }

    // ---------- হিসাবের ছবি শেয়ার ----------
    private fun shareImage(target: String, b64: String, text: String, phone: String) {
        val f: File
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            f = File(dir, "hisab-${System.currentTimeMillis()}.png")
            f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
        } catch (e: Exception) { toast("ছবি বানানো যায়নি"); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val base = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (target == "whatsapp") {
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                try {
                    val i = Intent(base).setPackage(pkg)
                    if (phone.isNotBlank()) i.putExtra("jid", "$phone@s.whatsapp.net")
                    startActivity(i); return
                } catch (_: ActivityNotFoundException) {}
            }
            toast("ফোনে WhatsApp পাওয়া যায়নি"); return
        }
        try { startActivity(Intent.createChooser(base, "হিসাবের কপি পাঠান")) } catch (_: Exception) { toast("পাঠানোর মতো অ্যাপ পাওয়া যায়নি") }
    }

    private fun openOutside(u: Uri) {
        try {
            val i = if (u.scheme == "tel") Intent(Intent.ACTION_DIAL, u) else Intent(Intent.ACTION_VIEW, u)
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
            toast(if (u.host == "wa.me") "ফোনে WhatsApp পাওয়া যায়নি" else "এটা খোলার মতো অ্যাপ পাওয়া যায়নি")
        }
    }

    // ---------- Google লগইন ----------
    private fun googleLogin() {
        val clientId = getString(R.string.web_client_id)
        if (clientId.isBlank() || clientId.startsWith("PASTE")) {
            js("hkLoginError(${q("Google লগইনের সেটিং এখনো বসানো হয়নি।")})")
            return
        }
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(clientId).build()
                val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val cred = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, req).credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    js("hkIdToken(${q(token)})")
                } else {
                    js("hkLoginError(${q("Google থেকে ঠিকমতো উত্তর আসেনি। আবার চেষ্টা করুন।")})")
                }
            } catch (e: GetCredentialCancellationException) {
                js("hkLoginError(${q("লগইন বাতিল হয়েছে। আবার চাপুন।")})")
            } catch (e: NoCredentialException) {
                js("hkLoginError(${q("ফোনে কোনো Google অ্যাকাউন্ট পাওয়া যায়নি। ফোনের Settings থেকে Google অ্যাকাউন্ট যোগ করুন।")})")
            } catch (e: Exception) {
                js("hkLoginError(${q("লগইন হয়নি: " + (e.message ?: e.javaClass.simpleName))})")
            }
        }
    }

    private fun googleLogout() {
        lifecycleScope.launch {
            try { CredentialManager.create(this@MainActivity).clearCredentialState(ClearCredentialStateRequest()) } catch (_: Exception) {}
        }
    }

    // ---------- Excel/CSV ফাইল সেভ ----------
    private fun saveFile(name: String, content: String) {
        try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith(".json")) "application/json" else "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HisabKhata")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw Exception("no uri")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                toast("ফোনের Download/HisabKhata ফোল্ডারে সেভ হয়েছে")
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                File(dir, name).writeBytes(bytes)
                toast("সেভ হয়েছে: " + dir?.absolutePath)
            }
        } catch (e: Exception) {
            toast("ফাইল সেভ হয়নি")
        }
    }

    // ---------- আপডেট ----------
    private fun checkForUpdate(manual: Boolean) {
        Thread {
            val info = try { Updates.fetchLatest() } catch (_: Exception) { null }
            if (info == null) {
                if (manual) toast("নেট সংযোগ পাওয়া যাচ্ছে না, পরে চেষ্টা করুন")
                return@Thread
            }
            if (Updates.isNewer(info.version)) {
                latest = info
                js("hkShowUpdate(${q(info.version)}, ${q(info.notes)})")
                if (startUpdateWhenFound) { startUpdateWhenFound = false; runOnUiThread { startDownload() } }
            } else {
                startUpdateWhenFound = false
                if (manual) js("hkNoUpdate()")
            }
        }.start()
    }

    private fun startDownload() {
        val info = latest
        if (info == null) { checkForUpdate(manual = true); startUpdateWhenFound = true; return }
        if (downloading) return
        downloading = true
        Thread {
            try {
                var url = URL(info.apkUrl)
                var c: HttpURLConnection
                var hops = 0
                while (true) {
                    c = url.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 20000
                    c.readTimeout = 30000
                    c.setRequestProperty("User-Agent", "HisabKhata-Android")
                    val code = c.responseCode
                    if (code in 300..399 && hops < 6) {
                        url = URL(url, c.getHeaderField("Location")); c.disconnect(); hops++; continue
                    }
                    if (code != 200) throw Exception("HTTP $code")
                    break
                }
                val total = c.contentLengthLong
                var done = 0L
                var lastPct = -1
                apkFile.delete()
                c.inputStream.use { input ->
                    apkFile.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct && pct % 5 == 0) { lastPct = pct; js("hkUpdateProgress($pct)") }
                            }
                        }
                    }
                }
                c.disconnect()
                downloading = false
                js("hkUpdateReady()")
                runOnUiThread { installApk() }
            } catch (e: Exception) {
                downloading = false
                js("hkUpdateError(${q("আপডেট নামানো যায়নি। নেট দেখে আবার চেষ্টা করুন।")})")
            }
        }.start()
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun installApk() {
        if (!apkFile.exists()) { startDownload(); return }
        if (!canInstall()) {
            waitingInstallPermission = true
            toast("\"এই উৎস থেকে অনুমতি দিন\" চালু করে ফিরে আসুন")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apkFile)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) { toast("ইনস্টল শুরু করা যায়নি") }
    }

    // ---------- ওয়েব পেজ থেকে ডাকা যায় এমন কাজ ----------
    inner class Bridge {
        @JavascriptInterface fun googleLogin() = runOnUiThread { this@MainActivity.googleLogin() }
        @JavascriptInterface fun googleLogout() = runOnUiThread { this@MainActivity.googleLogout() }
        @JavascriptInterface fun saveFile(name: String, content: String) = this@MainActivity.saveFile(name, content)
        @JavascriptInterface fun shareText(target: String, text: String) = runOnUiThread { this@MainActivity.shareText(target, text) }
        @JavascriptInterface fun shareImage(target: String, b64: String, text: String, phone: String) = runOnUiThread { this@MainActivity.shareImage(target, b64, text, phone) }
        @JavascriptInterface fun setDues(json: String) { Reminders.save(this@MainActivity, json) }
        @JavascriptInterface fun appVersion(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun checkUpdate(manual: Boolean) = checkForUpdate(manual)
        @JavascriptInterface fun startUpdate() = runOnUiThread { startDownload() }
        @JavascriptInterface fun installUpdate() = runOnUiThread { installApk() }
    }
}
