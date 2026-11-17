package com.example.startomv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.startomv.ui.theme.StartOMVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

        setContent {
            StartOMVTheme {
                MainAppScreen()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Encrypted Preferences Management (AES-256 GCM)
// ---------------------------------------------------------------------------
object OmvPreferences {
    private const val PREF_NAME = "omv_encrypted_prefs"
    private const val KEY_URL = "omv_url"
    private const val KEY_USER = "omv_user"
    private const val KEY_PASS = "omv_pass"

    private fun getPreferences(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getUrl(context: Context): String =
        getPreferences(context).getString(KEY_URL, "") ?: ""

    fun getUser(context: Context): String =
        getPreferences(context).getString(KEY_USER, "") ?: ""

    fun getPass(context: Context): String =
        getPreferences(context).getString(KEY_PASS, "") ?: ""

    fun save(context: Context, url: String, user: String, pass: String) {
        getPreferences(context).edit {
            putString(KEY_URL, url)
            putString(KEY_USER, user)
            putString(KEY_PASS, pass)
        }
    }

    fun hasConfig(context: Context): Boolean {
        return getUrl(context).isNotBlank() &&
               getUser(context).isNotBlank() &&
               getPass(context).isNotBlank()
    }
}

object OmvUtils {
    fun formatUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim()
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        return trimmed
    }
}

// ---------------------------------------------------------------------------
// Network Discovery / Scanner for OpenMediaVault
// ---------------------------------------------------------------------------
object OmvScanner {
    suspend fun discoverOmvServers(context: Context): List<String> = withContext(Dispatchers.IO) {
        val foundList = Collections.synchronizedList(mutableListOf<String>())

        // 1. Check openmediavault.local
        try {
            val testUrl = URL("http://openmediavault.local/")
            val conn = testUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            if (code in 200..404) {
                foundList.add("http://openmediavault.local")
            }
            conn.disconnect()
        } catch (_: Exception) {}

        // 2. Scan local subnet IPs
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (ipInt != 0) {
            val ipString = String.format(
                Locale.US, "%d.%d.%d.%d",
                ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff
            )
            val subnetPrefix = ipString.substringBeforeLast(".") + "."

            coroutineScope {
                (1..254).map { i ->
                    async {
                        val candidateIp = "$subnetPrefix$i"
                        try {
                            val url = URL("http://$candidateIp/")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 700
                            conn.readTimeout = 700
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                            val code = conn.responseCode
                            if (code in 200..404) {
                                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                                if (body.contains("openmediavault", ignoreCase = true) ||
                                    body.contains("omv", ignoreCase = true) ||
                                    body.contains("rpc.php", ignoreCase = true)) {
                                    foundList.add("http://$candidateIp")
                                }
                            }
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        }
        return@withContext foundList.distinct()
    }
}

// ---------------------------------------------------------------------------
// Password Manager & Autofill Helper Extension for Google Password Manager
// ---------------------------------------------------------------------------
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofill(
    autofillTypes: List<AutofillType>,
    onFill: (String) -> Unit,
): Modifier = composed {
    val autofill = LocalAutofill.current
    val autofillNode = remember {
        AutofillNode(onFill = onFill, autofillTypes = autofillTypes)
    }
    LocalAutofillTree.current += autofillNode

    this
        .onGloballyPositioned { coordinates ->
            autofillNode.boundingBox = coordinates.boundsInWindow()
        }
        .onFocusChanged { focusState ->
            autofill?.run {
                if (focusState.isFocused) {
                    requestAutofillForNode(autofillNode)
                } else {
                    cancelAutofillForNode(autofillNode)
                }
            }
        }
}

// ---------------------------------------------------------------------------
// App Screen State
// ---------------------------------------------------------------------------
sealed class AppScreenState {
    data class Config(val errorMessage: String? = null) : AppScreenState()
    data class Web(val url: String, val user: String, val pass: String) : AppScreenState()
}

// ---------------------------------------------------------------------------
// Main App Screen Composable
// ---------------------------------------------------------------------------
@Composable
fun MainAppScreen() {
    val context = LocalContext.current

    var screenState by remember {
        mutableStateOf<AppScreenState>(
            if (OmvPreferences.hasConfig(context)) {
                AppScreenState.Web(
                    url = OmvUtils.formatUrl(OmvPreferences.getUrl(context)),
                    user = OmvPreferences.getUser(context),
                    pass = OmvPreferences.getPass(context)
                )
            } else {
                AppScreenState.Config()
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (val state = screenState) {
            is AppScreenState.Config -> {
                ConfigScreen(
                    initialErrorMessage = state.errorMessage,
                    onSaveAndLogin = { url, user, pass ->
                        OmvPreferences.save(context, url, user, pass)
                        screenState = AppScreenState.Web(
                            url = OmvUtils.formatUrl(url),
                            user = user,
                            pass = pass
                        )
                    }
                )
            }
            is AppScreenState.Web -> {
                OmvWebViewScreen(
                    url = state.url,
                    username = state.user,
                    password = state.pass,
                    onOpenConfig = {
                        screenState = AppScreenState.Config()
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Configuration Screen
// ---------------------------------------------------------------------------
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ConfigScreen(
    initialErrorMessage: String?,
    onSaveAndLogin: (url: String, user: String, pass: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var url by remember { mutableStateOf(OmvPreferences.getUrl(context)) }
    var username by remember { mutableStateOf(OmvPreferences.getUser(context)) }
    var password by remember { mutableStateOf(OmvPreferences.getPass(context)) }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf(initialErrorMessage) }

    var isScanning by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf<List<String>?>(null) }
    var showScanDialog by remember { mutableStateOf(false) }

    val headerColor = Color(0xFF5DACDF)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenMediaVault Configuration", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = headerColor,
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "OMV Credentials",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            Text(
                text = "Enter the IP address or URL of your OpenMediaVault server and your credentials.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(top = 4.dp, bottom = 16.dp)
            )

            AnimatedVisibility(visible = !errorMessage.isNullOrEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Notice",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // OMV Auto-Discovery Scanner Button (Material Icons only)
            OutlinedButton(
                onClick = {
                    showScanDialog = true
                    isScanning = true
                    discoveredServers = null
                    coroutineScope.launch {
                        val servers = OmvScanner.discoverOmvServers(context)
                        discoveredServers = servers
                        isScanning = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Search OMV on Wi-Fi", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    errorMessage = null
                },
                label = { Text("OMV URL / IP Address") },
                placeholder = { Text("http://192.168.1.100") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Username input with explicit AutofillNode & semantics for Google Password Manager
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    errorMessage = null
                },
                label = { Text("Username") },
                placeholder = { Text("admin") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .autofill(listOf(AutofillType.Username)) { username = it }
                    .semantics { contentType = ContentType.Username }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Password input with explicit AutofillNode & semantics for Google Password Manager
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .autofill(listOf(AutofillType.Password)) { password = it }
                    .semantics { contentType = ContentType.Password }
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    if (url.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                        onSaveAndLogin(url, username, password)
                    } else {
                        errorMessage = "Please fill in all fields."
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = headerColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save & Log in", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // OMV Scanner Dialog
    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = {
                showScanDialog = false
                isScanning = false
            },
            title = { Text("OMV Wi-Fi Search", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        Text("Searching for OpenMediaVault servers on current Wi-Fi...", fontSize = 14.sp)
                    } else {
                        val servers = discoveredServers ?: emptyList()
                        if (servers.isEmpty()) {
                            Text("No OMV server found on current Wi-Fi network. Please enter the IP address manually.")
                        } else {
                            Text("Found OMV Servers (Tap to select):", fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(12.dp))
                            servers.forEach { serverUrl ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            url = serverUrl
                                            showScanDialog = false
                                        },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Dns, contentDescription = null, tint = headerColor)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(serverUrl, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScanDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// OpenMediaVault WebView Screen (Slim Header + Tab in App)
// ---------------------------------------------------------------------------
@Composable
fun OmvWebViewScreen(
    url: String,
    username: String,
    password: String,
    onOpenConfig: () -> Unit
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoadingPage by remember { mutableStateOf(true) }

    val headerColor = Color(0xFF5DACDF)

    val performFullRelogin: () -> Unit = {
        val targetUrl = OmvUtils.formatUrl(url)
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.flush()
            webViewInstance?.let { webView ->
                webView.clearHistory()
                webView.evaluateJavascript(
                    "try { window.sessionStorage.clear(); window.localStorage.clear(); } catch(e) {}",
                    null
                )
                webView.loadUrl(targetUrl)
            }
        }
    }

    var isAppInBackground by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    isAppInBackground = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (isAppInBackground) {
                        isAppInBackground = false
                        performFullRelogin()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Intercept back button to navigate within WebView history if possible
    BackHandler(enabled = webViewInstance?.canGoBack() == true) {
        webViewInstance?.goBack()
    }

    Scaffold(
        topBar = {
            Surface(
                color = headerColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left 50%: IP address centered between left edge (0%) and center (50%)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = url,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Right 50%: 3 Action Icons centered between center (50%) and right edge (100%)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { performFullRelogin() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Re-login & Refresh",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                                context.startActivity(intent)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Open in external browser",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onOpenConfig,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewInstance = this
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        @Suppress("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoadingPage = true
                            }

                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                super.onPageFinished(view, loadedUrl)
                                isLoadingPage = false

                                // JS Auto-Login for OMV 5/6/7 (Vue 3 / Angular / ExtJS)
                                val escapeUser = username.replace("'", "\\'")
                                val escapePass = password.replace("'", "\\'")
                                val jsAutoLogin = """
                                    (function() {
                                        function tryFillAndSubmit() {
                                            var u = document.querySelector('input[name="username"], input[autocomplete="username"], input[type="text"]');
                                            var p = document.querySelector('input[name="password"], input[autocomplete="current-password"], input[type="password"]');
                                            if (u && p && (!u.value || !p.value)) {
                                                try {
                                                    var uSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                                    var pSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                                    if (uSetter) uSetter.call(u, '$escapeUser'); else u.value = '$escapeUser';
                                                    if (pSetter) pSetter.call(p, '$escapePass'); else p.value = '$escapePass';
                                                } catch(e) {
                                                    u.value = '$escapeUser';
                                                    p.value = '$escapePass';
                                                }
                                                u.dispatchEvent(new Event('input', { bubbles: true }));
                                                u.dispatchEvent(new Event('change', { bubbles: true }));
                                                p.dispatchEvent(new Event('input', { bubbles: true }));
                                                p.dispatchEvent(new Event('change', { bubbles: true }));

                                                setTimeout(function() {
                                                    var btn = document.querySelector('button[type="submit"], input[type="submit"], button.v-btn, .v-btn');
                                                    if (btn) btn.click();
                                                }, 300);
                                            }
                                        }
                                        tryFillAndSubmit();
                                        setTimeout(tryFillAndSubmit, 500);
                                        setTimeout(tryFillAndSubmit, 1500);
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(jsAutoLogin, null)
                            }
                        }

                        loadUrl(OmvUtils.formatUrl(url))
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoadingPage) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = headerColor
                )
            }
        }
    }
}
