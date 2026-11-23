package dev.jkcarino.extension.reddit.frontpage

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern

object MediaDownloadHelper {

    private const val TAG = "MediaDownloadHelper"
    private const val PREFS_NAME = "morphe_media_downloader"
    private const val KEY_DEFAULT_ALL = "default_download_all"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile)"

    private val CLEAN_REDDIT_FILE_PATTERN = Pattern.compile("^[a-zA-Z0-9]+\\.(jpg|jpeg|png|gif|webp)$", Pattern.CASE_INSENSITIVE)
    private val REDGIFS_PATTERN = Pattern.compile("redgifs\\.com/(?:watch|ifr|i)/([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE)
    private val REDGIFS_CDN_PATTERN = Pattern.compile("redgifs\\.com/([a-zA-Z0-9]+)(?:-(?:mobile|silent))?\\.mp4", Pattern.CASE_INSENSITIVE)
    private val INVALID_FILENAME_CHARS = Pattern.compile("[^a-zA-Z0-9._-]")
    private val MULTI_UNDERSCORE = Pattern.compile("_+")

    private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    private val REDGIFS_EXECUTOR = Executors.newSingleThreadExecutor()
    private val FILE_COUNTER = AtomicInteger(0)
    private val LAST_DOWNLOAD_ELAPSED_MS = AtomicLong(0L)
    private val TOKEN_LOCK = ReentrantLock()
    private val IN_FLIGHT_LOCK = ReentrantLock()
    private val IN_FLIGHT_REDGIFS = HashSet<String>()

    private class TokenEntry(val value: String, val expiryElapsedMs: Long)
    @Volatile private var sRedGifsTokenEntry: TokenEntry? = null

    private fun isBlank(s: String?): Boolean = ExtensionStrUtils.isBlank(s)
    private fun lower(s: String): String = ExtensionStrUtils.lower(s)
    private fun containsIgnoreCase(s: String?, needle: String): Boolean = ExtensionStrUtils.containsIgnoreCase(s, needle)
    private fun replaceAllLiteral(s: String, target: String, replacement: String): String = ExtensionStrUtils.replace(s, target, replacement)

    // Uses Java Runnable (void run()) so zero kotlin.Unit.INSTANCE references are emitted under R8
    private fun runOnMain(block: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block.run()
        } else {
            MAIN_HANDLER.post(block)
        }
    }

    private fun showToast(context: Context, message: String) {
        val appCtx = context.applicationContext ?: context
        runOnMain(Runnable {
            try {
                Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        })
    }

    private fun jsonStringOrNull(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        val v = obj.optString(key, "")
        return if (isBlank(v) || v == "null") null else v
    }

    private fun isHttpUrl(url: String?): Boolean {
        if (isBlank(url)) return false
        val l = lower(url!!)
        return ExtensionStrUtils.startsWith(l, "http://") || ExtensionStrUtils.startsWith(l, "https://")
    }

    private fun extractHostLower(url: String): String {
        return try {
            lower(Uri.parse(url).host ?: "")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun extractPathLower(url: String): String {
        return try {
            lower(Uri.parse(url).path ?: "")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isStreamingManifest(url: String): Boolean {
        val path = extractPathLower(url)
        return ExtensionStrUtils.endsWith(path, ".mpd") || ExtensionStrUtils.endsWith(path, ".m3u8")
    }

    private fun isBareVRedditUrl(url: String): Boolean {
        val host = extractHostLower(url)
        if (host != "v.redd.it") return false
        val path = extractPathLower(url)
        return !ExtensionStrUtils.endsWith(path, ".mp4") &&
            !ExtensionStrUtils.endsWith(path, ".gif") &&
            !ExtensionStrUtils.endsWith(path, ".jpg") &&
            !ExtensionStrUtils.endsWith(path, ".jpeg") &&
            !ExtensionStrUtils.endsWith(path, ".png") &&
            !ExtensionStrUtils.endsWith(path, ".webp")
    }

    private fun readStreamText(conn: HttpURLConnection): String {
        val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
        try {
            val sb = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                sb.append(line)
                line = reader.readLine()
            }
            return sb.toString()
        } finally {
            try { reader.close() } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun isDefaultDownloadAll(context: Context): Boolean {
        val appCtx = context.applicationContext ?: context
        return appCtx
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEFAULT_ALL, false)
    }

    @JvmStatic
    fun setDefaultDownloadAll(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext ?: context
        appCtx
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEFAULT_ALL, enabled)
            .apply()
    }

    @JvmStatic
    fun isRedGifsUrl(url: String): Boolean {
        val host = extractHostLower(url)
        return host == "redgifs.com" || ExtensionStrUtils.endsWith(host, ".redgifs.com")
    }

    private fun isRedGifsMp4Path(url: String): Boolean {
        if (!isRedGifsUrl(url)) return false
        return ExtensionStrUtils.endsWith(extractPathLower(url), ".mp4")
    }

    private fun isDirectRedGifsCdnMp4(url: String): Boolean {
        if (!isRedGifsMp4Path(url)) return false
        val path = extractPathLower(url)
        if (ExtensionStrUtils.contains(path, "-mobile.mp4") || ExtensionStrUtils.contains(path, "-silent.mp4")) return false
        if (containsIgnoreCase(url, "expires=")) return false
        return true
    }

    @JvmStatic
    fun extractRedGifsId(url: String): String? {
        val matcher = REDGIFS_PATTERN.matcher(url)
        if (matcher.find()) {
            val group = matcher.group(1)
            if (group != null) return lower(group)
        }
        val cdnMatcher = REDGIFS_CDN_PATTERN.matcher(url)
        if (cdnMatcher.find()) {
            val group = cdnMatcher.group(1)
            if (group != null) return lower(group)
        }
        return null
    }

    private fun getOrFetchRedGifsToken(forceRefresh: Boolean): String? {
        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh) {
            val cached = sRedGifsTokenEntry
            if (cached != null && now < cached.expiryElapsedMs) {
                return cached.value
            }
        }
        TOKEN_LOCK.lock()
        try {
            val againNow = SystemClock.elapsedRealtime()
            if (!forceRefresh) {
                val cached = sRedGifsTokenEntry
                if (cached != null && againNow < cached.expiryElapsedMs) {
                    return cached.value
                }
            }
            var tokenConn: HttpURLConnection? = null
            return try {
                val tokenUrl = URL("https://api.redgifs.com/v2/auth/temporary")
                tokenConn = tokenUrl.openConnection() as HttpURLConnection
                tokenConn.connectTimeout = 10000
                tokenConn.readTimeout = 10000
                tokenConn.setRequestProperty("User-Agent", USER_AGENT)
                val code = tokenConn.responseCode
                if (code !in 200..299) return null
                val tokenJson = readStreamText(tokenConn)
                val token = jsonStringOrNull(JSONObject(tokenJson), "token") ?: return null
                sRedGifsTokenEntry = TokenEntry(token, againNow + 10 * 60 * 1000L)
                token
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to fetch RedGifs token: ${t.message}")
                null
            } finally {
                try { tokenConn?.disconnect() } catch (_: Throwable) {}
            }
        } finally {
            TOKEN_LOCK.unlock()
        }
    }

    @JvmStatic
    fun resolveRedGifsDirectUrl(redgifsUrl: String): String? {
        if (isDirectRedGifsCdnMp4(redgifsUrl)) return redgifsUrl
        val id = extractRedGifsId(redgifsUrl) ?: return if (isRedGifsMp4Path(redgifsUrl)) redgifsUrl else null
        for (attempt in 0..1) {
            var gifConn: HttpURLConnection? = null
            try {
                val token = getOrFetchRedGifsToken(forceRefresh = attempt == 1) ?: break
                val gifUrl = URL("https://api.redgifs.com/v2/gifs/$id")
                gifConn = gifUrl.openConnection() as HttpURLConnection
                gifConn.connectTimeout = 10000
                gifConn.readTimeout = 10000
                gifConn.setRequestProperty("Authorization", "Bearer $token")
                gifConn.setRequestProperty("User-Agent", USER_AGENT)
                val code = gifConn.responseCode
                if (code == 401 && attempt == 0) {
                    sRedGifsTokenEntry = null
                    continue
                }
                if (code !in 200..299) break
                val gifJson = readStreamText(gifConn)
                val gifObj = JSONObject(gifJson).optJSONObject("gif") ?: break
                val urlsObj = gifObj.optJSONObject("urls") ?: break
                val hd = jsonStringOrNull(urlsObj, "hd")
                if (isHttpUrl(hd)) return hd
                val sd = jsonStringOrNull(urlsObj, "sd")
                if (isHttpUrl(sd)) return sd
                break
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to resolve RedGifs direct URL for $redgifsUrl: ${t.message}")
                break
            } finally {
                try { gifConn?.disconnect() } catch (_: Throwable) {}
            }
        }
        // Fallback to direct CDN .mp4 if API resolution failed on an already-direct MP4 URL
        return if (isRedGifsMp4Path(redgifsUrl)) redgifsUrl else null
    }

    @JvmStatic
    fun normalizeUrl(rawUrl: String): String {
        var trimmed = ExtensionStrUtils.trim(rawUrl)
        var prev: String
        do {
            prev = trimmed
            trimmed = replaceAllLiteral(trimmed, "&amp;", "&")
        } while (trimmed != prev)

        if (trimmed.isEmpty()) return ""
        return try {
            val uri = Uri.parse(trimmed)
            val hostRaw = uri.host ?: return trimmed
            val host = lower(hostRaw)
            val segments = uri.pathSegments
            val segment = uri.lastPathSegment
            if (host == "preview.redd.it" &&
                segments != null &&
                segments.size == 1 &&
                segment != null &&
                uri.getQueryParameter("format") == null &&
                CLEAN_REDDIT_FILE_PATTERN.matcher(segment).matches()
            ) {
                "https://i.redd.it/$segment"
            } else {
                trimmed
            }
        } catch (_: Throwable) {
            trimmed
        }
    }

    @JvmStatic
    fun sanitizeUrls(urls: List<String>?): List<String> {
        if (urls == null || urls.isEmpty()) return ArrayList()
        val seen = LinkedHashSet<String>()
        for (u in urls) {
            val norm = normalizeUrl(u)
            if (isHttpUrl(norm) && !isStreamingManifest(norm) && !isBareVRedditUrl(norm)) {
                seen.add(norm)
            }
        }
        return ArrayList(seen)
    }

    private fun mergeCurrentIntoAll(cleanCurrent: String?, cleanAll: List<String>): List<String> {
        if (cleanCurrent == null) return cleanAll
        if (cleanAll.contains(cleanCurrent)) return cleanAll
        val merged = ArrayList<String>(cleanAll.size + 1)
        merged.add(cleanCurrent)
        merged.addAll(cleanAll)
        return merged
    }

    @JvmStatic
    fun onTap(
        context: Context,
        currentUrl: String?,
        allUrls: List<String>?,
        postTitle: String?
    ) {
        runOnMain(Runnable {
            val cleanCurrent = if (currentUrl != null) {
                val n = normalizeUrl(currentUrl)
                if (isHttpUrl(n) && !isStreamingManifest(n) && !isBareVRedditUrl(n)) n else null
            } else null
            val cleanAll = mergeCurrentIntoAll(cleanCurrent, sanitizeUrls(allUrls))

            if (isDefaultDownloadAll(context) && cleanAll.isNotEmpty()) {
                downloadUrls(context, cleanAll, postTitle)
                return@Runnable
            }

            if (cleanAll.size > 1) {
                showOptionsDialog(context, cleanCurrent, cleanAll, postTitle, preferSingleFallback = true)
                return@Runnable
            }

            val single = cleanCurrent ?: if (cleanAll.isNotEmpty()) cleanAll[0] else null
            if (single == null) {
                showToast(context, "No downloadable media found")
                return@Runnable
            }
            val singleList = ArrayList<String>(1)
            singleList.add(single)
            downloadUrls(context, singleList, postTitle)
        })
    }

    @JvmStatic
    fun onLongPress(
        context: Context,
        currentUrl: String?,
        allUrls: List<String>?,
        postTitle: String?
    ) {
        runOnMain(Runnable {
            val cleanCurrent = if (currentUrl != null) {
                val n = normalizeUrl(currentUrl)
                if (isHttpUrl(n) && !isStreamingManifest(n) && !isBareVRedditUrl(n)) n else null
            } else null
            val cleanAll = mergeCurrentIntoAll(cleanCurrent, sanitizeUrls(allUrls))
            showOptionsDialog(context, cleanCurrent, cleanAll, postTitle, preferSingleFallback = false)
        })
    }

    private fun showOptionsDialog(
        context: Context,
        cleanCurrent: String?,
        cleanAll: List<String>,
        postTitle: String?,
        preferSingleFallback: Boolean
    ) {
        if (cleanAll.isEmpty()) {
            showToast(context, "No downloadable media found")
            return
        }

        val effectiveCurrent = cleanCurrent ?: cleanAll[0]
        val activity = findActivity(context)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            if (preferSingleFallback) {
                val singleList = ArrayList<String>(1)
                singleList.add(effectiveCurrent)
                downloadUrls(context, singleList, postTitle)
            } else {
                downloadUrls(context, cleanAll, postTitle)
            }
            return
        }

        try {
            val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val dialogTheme = if (isDark) {
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            } else {
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            }
            val primaryTextColor = if (isDark) Color.parseColor("#F2F4F5") else Color.parseColor("#181C1F")
            val uncheckedBoxColor = if (isDark) Color.parseColor("#D7DADC") else Color.parseColor("#576F76")
            val checkedBoxColor = Color.parseColor("#FF4500")

            val padH = dp(activity, 22)
            val padV = dp(activity, 12)

            val container = LinearLayout(activity)
            container.orientation = LinearLayout.VERTICAL
            container.setPadding(padH, padV, padH, dp(activity, 4))

            val countText = TextView(activity)
            countText.text = if (cleanAll.size == 1) {
                "1 media item found in this post."
            } else {
                "${cleanAll.size} media items found in this post."
            }
            countText.setTextColor(primaryTextColor)
            countText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            countText.setPadding(0, 0, 0, dp(activity, 12))

            val defaultCheckBox = CheckBox(activity)
            defaultCheckBox.text = "Make it the default for future\n(always download all media on tap)"
            defaultCheckBox.isChecked = isDefaultDownloadAll(activity)
            defaultCheckBox.setTextColor(primaryTextColor)
            defaultCheckBox.buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(checkedBoxColor, uncheckedBoxColor)
            )
            defaultCheckBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            defaultCheckBox.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 4), dp(activity, 8))

            container.addView(countText)
            container.addView(defaultCheckBox)

            val builder = AlertDialog.Builder(activity, dialogTheme)
                .setTitle("Download Media")
                .setView(container)
                .setPositiveButton("Download all (${cleanAll.size})") { _, _ ->
                    setDefaultDownloadAll(activity, defaultCheckBox.isChecked)
                    downloadUrls(activity, cleanAll, postTitle)
                }

            if (cleanAll.size > 1) {
                builder.setNeutralButton("Download single") { _, _ ->
                    if (!defaultCheckBox.isChecked) {
                        setDefaultDownloadAll(activity, false)
                    }
                    val singleList = ArrayList<String>(1)
                    singleList.add(effectiveCurrent)
                    downloadUrls(activity, singleList, postTitle)
                }
            } else {
                builder.setNeutralButton("Save default") { _, _ ->
                    setDefaultDownloadAll(activity, defaultCheckBox.isChecked)
                    val mode = if (defaultCheckBox.isChecked) "all media" else "single item"
                    showToast(activity, "Default set to $mode")
                }
            }

            builder.setNegativeButton("Cancel", null)
                .show()
        } catch (t: Throwable) {
            Log.e(TAG, "Dialog error, falling back to direct download", t)
            if (preferSingleFallback) {
                val singleList = ArrayList<String>(1)
                singleList.add(effectiveCurrent)
                downloadUrls(context, singleList, postTitle)
            } else {
                downloadUrls(context, cleanAll, postTitle)
            }
        }
    }

    @JvmStatic
    fun downloadUrls(context: Context, urls: List<String>, postTitle: String?) {
        val appContext = context.applicationContext ?: context
        val cleanUrls = sanitizeUrls(urls)
        if (cleanUrls.isEmpty()) {
            showToast(appContext, "No downloadable media found")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val prev = LAST_DOWNLOAD_ELAPSED_MS.get()
        if (prev != 0L && now - prev < 500L) {
            return
        }
        if (!LAST_DOWNLOAD_ELAPSED_MS.compareAndSet(prev, now)) {
            return
        }

        val redGifsToResolve = ArrayList<String>()
        for (u in cleanUrls) {
            if (isRedGifsUrl(u) && !isDirectRedGifsCdnMp4(u)) {
                redGifsToResolve.add(u)
            }
        }

        if (redGifsToResolve.isNotEmpty()) {
            val acquired = ArrayList<String>()
            IN_FLIGHT_LOCK.lock()
            try {
                for (u in redGifsToResolve) {
                    if (IN_FLIGHT_REDGIFS.add(u)) {
                        acquired.add(u)
                    }
                }
            } finally {
                IN_FLIGHT_LOCK.unlock()
            }
            if (acquired.isEmpty()) {
                return
            }

            showToast(appContext, "Resolving RedGifs video...")
            REDGIFS_EXECUTOR.execute {
                try {
                    val resolvedSet = LinkedHashSet<String>(cleanUrls.size)
                    var failedCount = 0
                    for (u in cleanUrls) {
                        if (isRedGifsUrl(u) && !isDirectRedGifsCdnMp4(u)) {
                            val direct = resolveRedGifsDirectUrl(u)
                            if (direct != null && isHttpUrl(direct)) {
                                resolvedSet.add(direct)
                            } else {
                                failedCount++
                            }
                        } else {
                            resolvedSet.add(u)
                        }
                    }
                    val resolvedList = ArrayList(resolvedSet)
                    runOnMain(Runnable {
                        if (resolvedList.isEmpty()) {
                            showToast(appContext, "Failed to resolve RedGifs video")
                        } else {
                            enqueueDownload(appContext, resolvedList, postTitle, failedCount)
                        }
                    })
                } finally {
                    IN_FLIGHT_LOCK.lock()
                    try {
                        for (u in acquired) {
                            IN_FLIGHT_REDGIFS.remove(u)
                        }
                    } finally {
                        IN_FLIGHT_LOCK.unlock()
                    }
                }
            }
        } else {
            runOnMain(Runnable {
                enqueueDownload(appContext, cleanUrls, postTitle, 0)
            })
        }
    }

    private fun extractExtension(uri: Uri, rawUrl: String): String {
        val formatParam = uri.getQueryParameter("format")
        if (!isBlank(formatParam)) {
            when (lower(formatParam!!)) {
                "mp4" -> return "mp4"
                "pjpg", "jpg", "jpeg" -> return "jpg"
                "png8", "png" -> return "png"
                "gif" -> return "gif"
                "webp" -> return "webp"
            }
        }
        val seg = uri.lastPathSegment
        if (seg != null) {
            val dot = ExtensionStrUtils.lastIndexOf(seg, '.')
            if (dot >= 0 && dot < seg.length - 1) {
                val ext = lower(seg.substring(dot + 1))
                if (ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "gif" || ext == "webp" || ext == "mp4") {
                    return ext
                }
            }
        }
        val host = extractHostLower(rawUrl)
        val path = extractPathLower(rawUrl)
        return if (isRedGifsUrl(rawUrl) ||
            ExtensionStrUtils.endsWith(path, ".mp4") ||
            host == "packaged-media.redd.it"
        ) {
            "mp4"
        } else {
            "jpg"
        }
    }

    private fun trimEdgePunctuation(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && (s[start] == '_' || s[start] == '.' || s[start] == '-')) start++
        while (end > start && (s[end - 1] == '_' || s[end - 1] == '.' || s[end - 1] == '-')) end--
        return s.substring(start, end)
    }

    private fun sanitizeFileName(raw: String?): String {
        val input = if (isBlank(raw)) "reddit_media" else raw!!
        var cleaned = INVALID_FILENAME_CHARS.matcher(input).replaceAll("_")
        cleaned = MULTI_UNDERSCORE.matcher(cleaned).replaceAll("_")
        cleaned = trimEdgePunctuation(cleaned)
        if (cleaned.length > 40) {
            cleaned = trimEdgePunctuation(cleaned.substring(0, 40))
        }
        if (cleaned.isEmpty()) cleaned = "reddit_media"
        if (!cleaned[0].isLetterOrDigit()) cleaned = "r_$cleaned"
        return cleaned
    }

    private fun enqueueDownload(context: Context, cleanUrls: List<String>, postTitle: String?, priorFailures: Int) {
        if (cleanUrls.isEmpty()) {
            showToast(context, "No downloadable media found")
            return
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            showToast(context, "DownloadManager unavailable")
            return
        }

        val baseName = sanitizeFileName(postTitle)
        val stamp = "${System.currentTimeMillis()}_${FILE_COUNTER.incrementAndGet() and 0xFFFF}"

        var enqueued = 0
        var failed = priorFailures
        var firstExt = "jpg"

        for (index in 0 until cleanUrls.size) {
            val rawUrl = cleanUrls[index]
            try {
                if (!isHttpUrl(rawUrl) || isStreamingManifest(rawUrl) || isBareVRedditUrl(rawUrl)) {
                    failed++
                    continue
                }
                val encoded = Uri.encode(rawUrl, "@#&=*+-_.,:!?()/~'%;\$")
                val uri = Uri.parse(encoded)
                val scheme = lower(uri.scheme ?: "")
                if (scheme != "http" && scheme != "https") {
                    failed++
                    continue
                }
                val ext = extractExtension(uri, rawUrl)
                if (enqueued == 0) firstExt = ext

                val suffix = if (cleanUrls.size > 1) "_${index + 1}" else ""
                val fileName = "${baseName}_${stamp}$suffix.$ext"
                val destinationDirectory = if (ext == "mp4") {
                    Environment.DIRECTORY_MOVIES
                } else {
                    Environment.DIRECTORY_PICTURES
                }

                val request = DownloadManager.Request(uri)
                request.addRequestHeader("User-Agent", USER_AGENT)
                if (isRedGifsUrl(rawUrl)) {
                    request.addRequestHeader("Referer", "https://www.redgifs.com/")
                }
                request.setTitle(fileName)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    destinationDirectory,
                    "Reddit/$fileName"
                )
                dm.enqueue(request)
                enqueued++
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to enqueue $rawUrl: ${t.message}")
                failed++
            }
        }

        val msg = when {
            enqueued == 0 -> "Download failed"
            enqueued > 1 && failed > 0 -> "Downloading $enqueued items ($failed skipped)..."
            enqueued > 1 -> "Downloading $enqueued items..."
            failed > 0 -> "Downloading 1 item ($failed skipped)..."
            firstExt == "mp4" -> "Downloading video..."
            else -> "Downloading image..."
        }
        showToast(context, msg)
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        var depth = 0
        while (current is ContextWrapper && depth++ < 20) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return DownloadActionButton.getCurrentActivity()
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
