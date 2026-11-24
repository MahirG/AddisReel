package et.addisreel.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.math.roundToInt

/** Staff-only web workspace; the existing Cloudflare owner login is authoritative. */
class CreatorStudioActivity : ComponentActivity() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingPicker: ValueCallback<Array<Uri>>? = null
    private lateinit var web: WebView
    private lateinit var frame: FrameLayout
    private lateinit var progress: ProgressBar
    private var pdfPanel: View? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var pdfFile: File? = null
    private var pdfPage = 0
    private var pdfImage: ImageView? = null
    private var pdfPageLabel: TextView? = null
    private var pdfPrevious: Button? = null
    private var pdfNext: Button? = null
    private var renderedPage: Bitmap? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intent = if (result.resultCode == Activity.RESULT_OK) result.data else null
        val values = when {
            intent?.clipData != null -> Array(intent.clipData!!.itemCount) { i -> intent.clipData!!.getItemAt(i).uri }
            intent?.data != null -> arrayOf(intent.data!!)
            else -> null
        }
        pendingPicker?.onReceiveValue(values)
        pendingPicker = null
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density + .5f).toInt()
    private fun alert(text: Int) = Toast.makeText(this, getString(text), Toast.LENGTH_LONG).show()
    private fun control(label: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = getString(label)
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.rgb(255, 211, 133))
        minHeight = px(48)
        background = GradientDrawable().apply {
            setColor(Color.rgb(38, 34, 43))
            cornerRadius = px(12).toFloat()
            setStroke(px(1), Color.rgb(89, 68, 49))
        }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.rgb(17, 16, 20)
        window.navigationBarColor = Color.rgb(17, 16, 20)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 12, 17))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(9), px(6), px(9), px(6))
            setBackgroundColor(Color.rgb(23, 20, 27))
        }
        toolbar.addView(control(R.string.studio_back) { back() },
            LinearLayout.LayoutParams(px(62), px(48)))
        val title = TextView(this).apply {
            text = getString(R.string.studio_title)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 209, 122))
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(px(11), 0, 0, 0)
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, px(48), 1f))
        val menuButton = control(R.string.studio_menu) {}
        menuButton.setOnClickListener { openMenu(menuButton) }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(px(70), px(48)))
        layout.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60)))

        frame = FrameLayout(this)
        web = makeWebView()
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 209, 122))
        }
        frame.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3), android.view.Gravity.TOP))
        layout.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        web.loadUrl(StudioNavigationPolicy.ORIGIN + "/admin")
    }

    private fun openMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.studio_section_content))
        popup.menu.add(0, 2, 1, getString(R.string.studio_section_creators))
        popup.menu.add(0, 3, 2, getString(R.string.studio_section_rights))
        popup.menu.add(0, 4, 3, getString(R.string.studio_refresh))
        popup.setOnMenuItemClickListener {
            closePdf()
            when (it.itemId) {
                1 -> web.loadUrl(StudioNavigationPolicy.ORIGIN + "/admin")
                2 -> web.loadUrl(StudioNavigationPolicy.ORIGIN + "/admin/creators")
                3 -> web.loadUrl(StudioNavigationPolicy.ORIGIN + "/admin/rights")
                4 -> web.reload()
            }
            true
        }
        popup.show()
    }

    private fun back() {
        when {
            pdfPanel != null -> closePdf()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    private fun makeWebView(): WebView {
        CookieManager.getInstance().setAcceptCookie(true)
        return WebView(this).apply {
            setBackgroundColor(Color.rgb(13, 12, 17))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            // Required for explicitly user-selected Android Storage Access Framework upload URIs.
            // File paths, mixed content and arbitrary external origins remain blocked.
            settings.allowContentAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setGeolocationEnabled(false)
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (StudioNavigationPolicy.isTrusted(request?.url?.toString().orEmpty())) return false
                    alert(R.string.studio_blocked_link)
                    return true
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    this@CreatorStudioActivity.progress.visibility = View.VISIBLE
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    this@CreatorStudioActivity.progress.visibility = View.GONE
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        this@CreatorStudioActivity.progress.visibility = View.GONE
                        alert(R.string.studio_network_error)
                    }
                }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.cancel()
                    this@CreatorStudioActivity.progress.visibility = View.GONE
                    alert(R.string.studio_tls_error)
                }
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view?.destroy()
                    finish()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    pendingPicker?.onReceiveValue(null)
                    pendingPicker = callback
                    val accepted = params?.acceptTypes?.flatMap { it.split(",") }
                        ?.map { it.trim() }?.filter { it.contains("/") && it.isNotBlank() }
                        ?.toTypedArray() ?: emptyArray()
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (accepted.size == 1) accepted[0] else "*/*"
                        if (accepted.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accepted)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    }
                    return try {
                        picker.launch(intent)
                        true
                    } catch (_: Exception) {
                        pendingPicker?.onReceiveValue(null)
                        pendingPicker = null
                        alert(R.string.studio_picker_error)
                        true
                    }
                }
            }
            setDownloadListener { link, _, _, mime, _ ->
                if (StudioNavigationPolicy.isPrivateAgreement(link) && mime?.startsWith("application/pdf") == true) {
                    downloadPdf(link)
                } else alert(R.string.studio_download_blocked)
            }
        }
    }

    private fun downloadPdf(link: String) {
        this@CreatorStudioActivity.progress.visibility = View.VISIBLE
        work.launch {
            try {
                val file = withContext(Dispatchers.IO) { getPrivatePdf(link) }
                openPdf(file)
            } catch (_: Exception) {
                alert(R.string.studio_pdf_error)
            } finally {
                this@CreatorStudioActivity.progress.visibility = View.GONE
            }
        }
    }

    private fun getPrivatePdf(link: String): File {
        require(StudioNavigationPolicy.isPrivateAgreement(link))
        val conn = URL(link).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 25000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/pdf")
        CookieManager.getInstance().getCookie(StudioNavigationPolicy.ORIGIN)?.let {
            conn.setRequestProperty("Cookie", it)
        }
        var file: File? = null
        try {
            require(conn.responseCode == 200)
            require(conn.contentType?.startsWith("application/pdf") == true)
            require(conn.contentLengthLong < 0 || conn.contentLengthLong <= 5L * 1024 * 1024)
            val destination = File.createTempFile("addisreel-contract-", ".pdf", cacheDir)
            file = destination
            var count = 0L
            conn.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(16384)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= 5L * 1024 * 1024)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val magic = destination.inputStream().use { input ->
                ByteArray(5).also { input.read(it) }
            }
            require(magic.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)))
            return destination
        } catch (error: Exception) {
            file?.delete()
            throw error
        } finally {
            conn.disconnect()
        }
    }

    private fun openPdf(file: File) {
        closePdf()
        try {
            pdfDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pdfDescriptor!!)
            require(pdfRenderer!!.pageCount > 0)
            pdfFile = file
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(15, 14, 19))
                setPadding(px(8), px(8), px(8), px(8))
            }
            val header = TextView(this).apply {
                text = getString(R.string.studio_pdf_title)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(px(10), 0, 0, px(10))
            }
            panel.addView(header)
            val scroll = ScrollView(this)
            pdfImage = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.studio_pdf_page_description)
            }
            scroll.addView(pdfImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val actions = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            pdfPrevious = control(R.string.studio_previous) { showPdfPage(pdfPage - 1) }
            actions.addView(pdfPrevious, LinearLayout.LayoutParams(0, px(48), 1f))
            pdfPageLabel = TextView(this).apply {
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
            }
            actions.addView(pdfPageLabel, LinearLayout.LayoutParams(px(90), px(48)))
            pdfNext = control(R.string.studio_next) { showPdfPage(pdfPage + 1) }
            actions.addView(pdfNext, LinearLayout.LayoutParams(0, px(48), 1f))
            panel.addView(actions)
            pdfPanel = panel
            frame.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            showPdfPage(0)
        } catch (_: Exception) {
            closePdf()
            file.delete()
            alert(R.string.studio_pdf_error)
        }
    }

    private fun showPdfPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index !in 0 until renderer.pageCount) return
        renderer.openPage(index).use { page ->
            val scale = min(1100f / page.width.coerceAtLeast(1), 1800f / page.height.coerceAtLeast(1))
            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            pdfImage?.setImageBitmap(bitmap)
            renderedPage?.recycle()
            renderedPage = bitmap
            pdfPage = index
            pdfPageLabel?.text = (index + 1).toString() + " / " + renderer.pageCount
            pdfPrevious?.isEnabled = index > 0
            pdfNext?.isEnabled = index + 1 < renderer.pageCount
        }
    }

    private fun closePdf() {
        pdfPanel?.let { frame.removeView(it) }
        pdfPanel = null
        pdfImage?.setImageDrawable(null)
        pdfImage = null
        renderedPage?.recycle()
        renderedPage = null
        pdfRenderer?.close()
        pdfRenderer = null
        pdfDescriptor?.close()
        pdfDescriptor = null
        pdfFile?.delete()
        pdfFile = null
        pdfPage = 0
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }
    override fun onPause() {
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        pendingPicker?.onReceiveValue(null)
        pendingPicker = null
        work.cancel()
        if (::frame.isInitialized) closePdf()
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
