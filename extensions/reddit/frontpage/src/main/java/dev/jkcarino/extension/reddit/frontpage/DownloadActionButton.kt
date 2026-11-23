package dev.jkcarino.extension.reddit.frontpage

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

object DownloadActionButton {

    private const val TAG = "DownloadActionButton"
    private const val BUTTON_TAG = "morphe_feed_download_pill"

    private fun <K, V> createLruMap(maxEntries: Int): MutableMap<K, V> {
        return java.util.Collections.synchronizedMap(
            object : LinkedHashMap<K, V>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                    return size > maxEntries
                }
            }
        )
    }

    // Identity-keyed wrapper so Compose UI models with value equality never collide across posts
    private class IdentityKey(val ref: WeakReference<Any>, val hash: Int) {
        constructor(obj: Any) : this(WeakReference(obj), System.identityHashCode(obj))
        override fun hashCode(): Int = hash
        override fun toString(): String = "id_$hash"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityKey) return false
            val a = ref.get()
            val b = other.ref.get()
            return a != null && a === b
        }
    }

    // Bounded identity LRU with WeakReference values -> zero equals() collisions AND zero WeakHashMap memory leaks
    private val POST_CACHE: MutableMap<IdentityKey, WeakReference<Any>> = createLruMap(600)
    private val POST_BY_ID: MutableMap<String, Any> = createLruMap(500)
    private val FEED_ELEMENTS_BY_ID: MutableMap<String, java.util.concurrent.CopyOnWriteArrayList<Any>> = createLruMap(500)
    private val FEED_ELEMENT_VERSION: MutableMap<String, AtomicInteger> = createLruMap(500)
    private val MEDIA_CACHE_BY_ID: MutableMap<String, MediaInfo> = createLruMap(600)
    private val WRAPPED_WINDOWS: MutableMap<Window, Boolean> = java.util.Collections.synchronizedMap(WeakHashMap())

    // Explicit ReentrantLocks (never Kotlin synchronized{} which can emit R8-stripped kotlin.Unit.INSTANCE)
    private val TOUCH_LOCK = ReentrantLock()
    private val FEED_ELEMENT_LOCK = ReentrantLock()

    @Volatile private var sCurrentPost: WeakReference<Any>? = null
    @Volatile private var sLastTouchDurationMs: Long = 0L
    @Volatile private var sLastTouchUpElapsedMs: Long = 0L
    @Volatile private var sTouchDownTime: Long = 0L
    @Volatile private var sLastFbpHandledElapsedMs: Long = 0L
    @Volatile private var sLastFbpHandledKey: String? = null
    @Volatile private var sLifecycleRegistered: Boolean = false
    @Volatile private var sResumedActivity: WeakReference<Activity>? = null
    @Volatile private var sAppContext: Context? = null

    private val MAIN_HANDLER = Handler(Looper.getMainLooper())

    // One-time cached reflection handles & immutable Compose objects (zero per-frame reflection)
    @Volatile private var sTargetClass: Class<*>? = null
    @Volatile private var sRenderMethodB: Method? = null
    @Volatile private var sRenderMethodC: Method? = null
    @Volatile private var sKcqConstructor: Constructor<*>? = null
    @Volatile private var sM20Constructor: Constructor<*>? = null
    @Volatile private var sFunction0Class: Class<*>? = null
    @Volatile private var sCachedTag: Any? = null
    @Volatile private var sCachedKcq: Any? = null
    @Volatile private var sDownloadIconResId: Int = -1

    // Lightweight live handle for a feed post (never snapshots an empty element list)
    class FeedPostBundle(val postId: String, val initialLink: Any?)

    // --- Pure-Java String Helpers (immune to R8-shrunk kotlin-stdlib StringsKt) ---

    private fun isBlank(s: String?): Boolean = ExtensionStrUtils.isBlank(s)
    private fun lower(s: String): String = ExtensionStrUtils.lower(s)
    private fun strContains(s: String?, needle: String): Boolean = ExtensionStrUtils.contains(s, needle)
    private fun strContainsIgnoreCase(s: String?, needle: String): Boolean = ExtensionStrUtils.containsIgnoreCase(s, needle)
    private fun strEndsWith(s: String?, suffix: String): Boolean = ExtensionStrUtils.endsWith(s, suffix)
    private fun strStartsWith(s: String?, prefix: String): Boolean = ExtensionStrUtils.startsWith(s, prefix)

    private fun unescapeAmp(s: String): String {
        var cur = s
        var prev: String
        do {
            prev = cur
            cur = ExtensionStrUtils.replace(cur, "&amp;", "&")
        } while (cur != prev)
        return cur
    }

    // Only normalize bare alphanumeric post IDs or non-empty t3_ IDs; reject t1_ comments / t5_ subreddits
    private fun normalizePostId(raw: String?): String? {
        if (isBlank(raw)) return null
        val trimmed = ExtensionStrUtils.trim(raw)
        if (trimmed.isEmpty()) return null
        if (strStartsWith(trimmed, "t3_")) {
            return if (trimmed.length > 3) trimmed else null
        }
        if (strContains(trimmed, "_")) return null
        return "t3_$trimmed"
    }

    private fun putPostCache(key: Any?, value: Any?) {
        if (key == null || value == null) return
        POST_CACHE[IdentityKey(key)] = WeakReference(value)
    }

    private fun getPostCache(key: Any?): Any? {
        if (key == null) return null
        return POST_CACHE[IdentityKey(key)]?.get()
    }

    private fun isHttpUrl(url: String?): Boolean {
        if (isBlank(url)) return false
        val l = lower(url!!)
        return strStartsWith(l, "http://") || strStartsWith(l, "https://")
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

    private fun hasMediaExtensionInPath(pathLower: String): Boolean {
        return strEndsWith(pathLower, ".jpg") ||
            strEndsWith(pathLower, ".jpeg") ||
            strEndsWith(pathLower, ".png") ||
            strEndsWith(pathLower, ".gif") ||
            strEndsWith(pathLower, ".webp") ||
            strEndsWith(pathLower, ".mp4")
    }

    // Reject bare https://v.redd.it/<id> HTML post-redirect pages
    private fun isBareVRedditUrl(url: String): Boolean {
        val host = extractHostLower(url)
        if (host != "v.redd.it") return false
        return !hasMediaExtensionInPath(extractPathLower(url))
    }

    // Strictly check URI path (never query string, so packaged-media ...&m=DASHPlaylist.mpd is NOT killed)
    private fun isStreamingManifest(url: String): Boolean {
        val path = extractPathLower(url)
        return strEndsWith(path, ".mpd") || strEndsWith(path, ".m3u8")
    }

    private fun isAudioOnlyDashUrl(url: String): Boolean {
        val path = extractPathLower(url)
        return strContains(path, "dash_audio") ||
            strContains(path, "cmaf_audio") ||
            strContains(path, "/audio_")
    }

    private fun parsePositiveInt(s: String?): Int {
        if (isBlank(s)) return -1
        val trimmed = ExtensionStrUtils.trim(s)
        var acc = 0
        for (i in 0 until trimmed.length) {
            val ch = trimmed[i]
            if (ch < '0' || ch > '9') return -1
            acc = acc * 10 + (ch - '0')
            if (acc > 100000) return acc
        }
        return acc
    }

    private fun isLowResOrBlurredPreview(url: String): Boolean {
        if (extractHostLower(url) != "preview.redd.it") return false
        return try {
            val uri = Uri.parse(url)
            if (!isBlank(uri.getQueryParameter("blur"))) return true
            val width = parsePositiveInt(uri.getQueryParameter("width"))
            width in 1..320
        } catch (_: Throwable) {
            false
        }
    }

    private fun consumeIsLongPress(): Boolean {
        val dur = sLastTouchDurationMs
        val upAt = sLastTouchUpElapsedMs
        sLastTouchDurationMs = 0L
        sLastTouchUpElapsedMs = 0L
        if (dur < 400L || upAt == 0L) return false
        val age = SystemClock.elapsedRealtime() - upAt
        return age in 0L..350L
    }

    // --- Android View Pill Construction ---

    @JvmStatic
    fun createPillButton(context: Context): ImageButton {
        val isDark = isNightMode(context)
        val borderColor = if (isDark) Color.argb(41, 255, 255, 255) else Color.argb(36, 0, 0, 0)
        val rippleColor = if (isDark) Color.argb(38, 255, 255, 255) else Color.argb(24, 0, 0, 0)
        val iconTint = if (isDark) Color.parseColor("#F2F4F5") else Color.parseColor("#181C1F")

        val pillOutline = GradientDrawable()
        pillOutline.shape = GradientDrawable.RECTANGLE
        pillOutline.cornerRadius = dpF(context, 999f)
        pillOutline.setColor(Color.TRANSPARENT)
        pillOutline.setStroke(dp(context, 1), borderColor)

        val rippleMask = GradientDrawable()
        rippleMask.shape = GradientDrawable.RECTANGLE
        rippleMask.cornerRadius = dpF(context, 999f)
        rippleMask.setColor(Color.WHITE)

        val resId = getDownloadIconResId(context)

        val btn = ImageButton(context)
        btn.tag = BUTTON_TAG
        btn.contentDescription = "Download media"
        btn.background = RippleDrawable(ColorStateList.valueOf(rippleColor), pillOutline, rippleMask)
        btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
        btn.isHapticFeedbackEnabled = true
        val padH = dp(context, 12)
        val padV = dp(context, 6)
        btn.setPadding(padH, padV, padH, padV)
        if (resId != 0) {
            btn.setImageResource(resId)
            btn.imageTintList = ColorStateList.valueOf(iconTint)
        }
        return btn
    }

    @JvmStatic
    fun bindPillButton(
        button: ImageButton,
        currentUrl: String?,
        allUrls: List<String>?,
        postTitle: String?
    ) {
        val cleanAll = MediaDownloadHelper.sanitizeUrls(allUrls)
        val hasCurrent = !isBlank(currentUrl) && isHttpUrl(currentUrl) && !isStreamingManifest(currentUrl!!) && !isBareVRedditUrl(currentUrl)
        if (cleanAll.isEmpty() && !hasCurrent) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            button.setOnLongClickListener(null)
            return
        }

        button.visibility = View.VISIBLE
        button.setOnClickListener { v ->
            MediaDownloadHelper.onTap(v.context, currentUrl, cleanAll, postTitle)
        }
        button.setOnLongClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            MediaDownloadHelper.onLongPress(v.context, currentUrl, cleanAll, postTitle)
            true
        }
    }

    @JvmStatic
    fun attachBeforeShare(
        parent: ViewGroup,
        shareView: View,
        currentUrl: String?,
        allUrls: List<String>?,
        postTitle: String?
    ) {
        val existing = parent.findViewWithTag<ImageButton>(BUTTON_TAG)
        val button = existing ?: createPillButton(parent.context).also { btn ->
            val rawIndex = parent.indexOfChild(shareView)
            val shareIndex = if (rawIndex < 0) 0 else rawIndex
            val shareLp = shareView.layoutParams
            val lp = if (shareLp is ViewGroup.MarginLayoutParams) {
                val copied = try {
                    shareLp.javaClass.getConstructor(shareLp.javaClass).newInstance(shareLp) as ViewGroup.MarginLayoutParams
                } catch (_: Throwable) {
                    try {
                        shareLp.javaClass.getConstructor(ViewGroup.MarginLayoutParams::class.java).newInstance(shareLp) as ViewGroup.MarginLayoutParams
                    } catch (_: Throwable) {
                        ViewGroup.MarginLayoutParams(dp(parent.context, 44), dp(parent.context, 32))
                    }
                }
                copied.width = dp(parent.context, 44)
                copied.height = dp(parent.context, 32)
                copied.marginEnd = dp(parent.context, 8)
                copied
            } else {
                ViewGroup.MarginLayoutParams(dp(parent.context, 44), dp(parent.context, 32)).apply {
                    marginEnd = dp(parent.context, 8)
                }
            }
            parent.addView(btn, shareIndex, lp)
        }
        bindPillButton(button, currentUrl, allUrls, postTitle)
    }

    // --- Post Model Tracking & Registration ---

    @JvmStatic
    fun registerLinkWithId(link: Any?, kindWithId: String?) {
        if (link == null) return
        val norm = normalizePostId(kindWithId) ?: return
        POST_BY_ID[norm] = link
        MEDIA_CACHE_BY_ID.remove(norm)
    }

    @JvmStatic
    fun registerLink(link: Any?) {
        if (link == null) return
        try {
            val kindWithId = try { link.javaClass.getMethod("getKindWithId").invoke(link) as? String } catch (_: Throwable) { null }
            val normKind = normalizePostId(kindWithId)
            if (normKind != null) {
                POST_BY_ID[normKind] = link
                MEDIA_CACHE_BY_ID.remove(normKind)
            }
            val rawId = try { link.javaClass.getMethod("getId").invoke(link) as? String } catch (_: Throwable) { null }
            val normRaw = normalizePostId(rawId)
            if (normRaw != null && normRaw != normKind) {
                POST_BY_ID[normRaw] = link
                MEDIA_CACHE_BY_ID.remove(normRaw)
            }
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun registerFeedPost(to30: Any?, link: Any?) {
        if (link != null) {
            registerLink(link)
            // Do NOT overwrite sCurrentPost during offscreen feed model binding
            if (to30 != null) {
                putPostCache(to30, link)
                val postId = normalizePostId(getFieldValue(to30, "e") as? String)
                if (postId != null) {
                    POST_BY_ID[postId] = link
                    MEDIA_CACHE_BY_ID.remove(postId)
                }
            }
        }
    }

    @JvmStatic
    fun registerFeedElement(element: Any?, linkId: String?) {
        if (element == null) return
        val id = normalizePostId(linkId) ?: return
        try {
            val simpleName = element.javaClass.simpleName
            if (simpleName == "to30") return
            FEED_ELEMENT_LOCK.lock()
            try {
                var list = FEED_ELEMENTS_BY_ID[id]
                if (list == null) {
                    list = java.util.concurrent.CopyOnWriteArrayList()
                    FEED_ELEMENTS_BY_ID[id] = list
                }
                for (existing in list) {
                    if (existing === element) return
                }
                if (list.size < 24) {
                    list.add(element)
                    var ver = FEED_ELEMENT_VERSION[id]
                    if (ver == null) {
                        ver = AtomicInteger(0)
                        FEED_ELEMENT_VERSION[id] = ver
                    }
                    ver.incrementAndGet()
                    MEDIA_CACHE_BY_ID.remove(id)
                }
            } finally {
                FEED_ELEMENT_LOCK.unlock()
            }
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun onFeedActionBar(to30: Any?, shareM20: Any?) {
        // Clear PDP sCurrentPost when entering a feed action bar so stale PDP posts never leak into feed items
        sCurrentPost = null
        if (to30 == null) return
        try {
            val postId = normalizePostId(getFieldValue(to30, "e") as? String)
            var link = getPostCache(to30)
            if (link == null && postId != null) {
                link = POST_BY_ID[postId]
            }
            val target: Any? = if (postId != null) {
                FeedPostBundle(postId, link)
            } else {
                link
            }
            if (target != null && shareM20 != null) {
                putPostCache(shareM20, target)
            }
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun registerPost(key: Any?, target: Any?) {
        if (key != null && target != null) {
            putPostCache(key, target)
            // Do NOT overwrite sCurrentPost during offscreen model mapping; onPostActionBar sets it when rendered
            try {
                val qo = getFieldValue(key, "a")
                if (qo != null) {
                    putPostCache(qo, target)
                }
            } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun onPostActionBar(qo30: Any?) {
        if (qo30 == null) {
            sCurrentPost = null
            return
        }
        val post = getPostCache(qo30)
        sCurrentPost = if (post != null) WeakReference(post) else null
    }

    // --- Full Bleed Player (FBP) 3-Dots -> Download Override ---

    @JvmStatic
    fun onFbpDownloadEvent(event: Any?): Boolean {
        if (event == null) return false
        ensureTouchTracker()
        try {
            val hasScreenField = try {
                event.javaClass.getDeclaredField("c")
                true
            } catch (_: Throwable) {
                false
            }

            val mediaPage = getFieldValue(event, "b")
            val dedupKey = if (mediaPage != null) {
                val isGalleryPage = getFieldValue(mediaPage, "l") is List<*>
                val rawPid = if (isGalleryPage) {
                    getFieldValue(mediaPage, "i") as? String
                } else {
                    getFieldValue(mediaPage, "k") as? String
                }
                normalizePostId(rawPid) ?: "obj_${System.identityHashCode(mediaPage)}"
            } else null

            val nowElapsed = SystemClock.elapsedRealtime()

            if (!hasScreenField) {
                // s8h (overflow menu Download click): handle immediately as a tap,
                // then return false so Reddit's handler dismisses the overflow sheet cleanly.
                if (mediaPage != null) {
                    sLastFbpHandledElapsedMs = nowElapsed
                    sLastFbpHandledKey = dedupKey
                    dispatchFbpMediaDownload(mediaPage, isLongPress = false)
                }
                return false
            }

            // t8h (Save event dispatched after overflow sheet closes):
            // ALWAYS return true so Reddit's broken FbpImageDownloadWorker never runs.
            val alreadyHandledSame = dedupKey != null &&
                dedupKey == sLastFbpHandledKey &&
                (nowElapsed - sLastFbpHandledElapsedMs) in 0L..1500L
            if (!alreadyHandledSame && mediaPage != null) {
                sLastFbpHandledElapsedMs = nowElapsed
                sLastFbpHandledKey = dedupKey
                dispatchFbpMediaDownload(mediaPage, isLongPress = false)
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "onFbpDownloadEvent failed", t)
            return false
        }
    }

    private fun dispatchFbpMediaDownload(mediaPage: Any, isLongPress: Boolean) {
        try {
            val allUrls = ArrayList<String>()
            var currentUrl: String? = null
            var postTitle: String? = null
            var postId: String? = null

            val lVal = getFieldValue(mediaPage, "l")
            if (lVal is List<*>) {
                // erv (gallery/image page)
                val mIdx = (getFieldValue(mediaPage, "m") as? Int) ?: 0
                for (i in 0 until lVal.size) {
                    val item = lVal[i] ?: continue
                    val u = getFieldValue(item, "a") as? String
                    if (!isBlank(u)) {
                        val clean = unescapeAmp(u!!)
                        if (!allUrls.contains(clean)) {
                            allUrls.add(clean)
                        }
                        if (i == mIdx) {
                            currentUrl = clean
                        }
                    }
                }
                postId = normalizePostId(getFieldValue(mediaPage, "i") as? String)
            } else {
                // hrv (video/gif page)
                val info = MediaInfo()
                info.isVideo = true
                info.isSingleVideoModel = true
                val rv = getFieldValue(mediaPage, "x")
                if (rv != null) {
                    extractFromRedditVideo(rv, info)
                }
                val vUrl = getFieldValue(mediaPage, "v") as? String
                if (!isBlank(vUrl) && isHttpUrl(vUrl) && !isStreamingManifest(vUrl!!) && !isBareVRedditUrl(vUrl)) {
                    val clean = unescapeAmp(vUrl)
                    if (!info.urls.contains(clean)) {
                        info.urls.add(clean)
                    }
                }
                val jMeta = getFieldValue(mediaPage, "j")
                if (jMeta != null) {
                    val visited = IdentityHashMap<Any, Boolean>()
                    scanFeedElementForMedia(jMeta, info, visited, 0)
                }
                normalizeVideoMediaInfo(info)
                allUrls.addAll(info.urls)
                currentUrl = info.currentUrl ?: if (allUrls.isNotEmpty()) allUrls[0] else null
                postId = normalizePostId(getFieldValue(mediaPage, "k") as? String)
            }

            if (postId != null) {
                val link = POST_BY_ID[postId]
                if (link != null) {
                    postTitle = try { link.javaClass.getMethod("getTitle").invoke(link) as? String } catch (_: Throwable) { null }
                    if (allUrls.isEmpty()) {
                        val fromLink = extractMedia(link, allowCurrentPostFallback = false)
                        allUrls.addAll(fromLink.urls)
                    }
                }
                val cached = MEDIA_CACHE_BY_ID[postId]
                if (cached != null) {
                    if (isBlank(postTitle) && !isBlank(cached.title) && cached.title != "reddit_media") {
                        postTitle = cached.title
                    }
                    if (allUrls.isEmpty()) {
                        allUrls.addAll(cached.urls)
                    }
                }
                if (allUrls.isEmpty()) {
                    val els = FEED_ELEMENTS_BY_ID[postId]
                    if (els != null && els.isNotEmpty()) {
                        val elInfo = MediaInfo()
                        for (el in els) {
                            scanFeedElementForMedia(el, elInfo, IdentityHashMap(), 0)
                        }
                        normalizeVideoMediaInfo(elInfo)
                        deduplicatePreviewResolutions(elInfo)
                        allUrls.addAll(elInfo.urls)
                    }
                }
            }

            if (currentUrl == null && allUrls.isNotEmpty()) {
                currentUrl = allUrls[0]
            }

            Log.i(TAG, "FBP download postId=$postId urls=${allUrls.size} current=$currentUrl long=$isLongPress")

            val finalCurrent = currentUrl
            val finalTitle = if (isBlank(postTitle)) "reddit_media" else postTitle
            MAIN_HANDLER.post {
                try {
                    val act = getCurrentActivity() ?: getAppContext() ?: return@post
                    if (isLongPress) {
                        MediaDownloadHelper.onLongPress(act, finalCurrent, allUrls, finalTitle)
                    } else {
                        MediaDownloadHelper.onTap(act, finalCurrent, allUrls, finalTitle)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error dispatching FBP download", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "dispatchFbpMediaDownload failed", t)
        }
    }

    // --- Media Availability Detection (STRICTLY non-recursive, O(1) on cached posts) ---

    fun isRedGifs(text: String?): Boolean {
        if (isBlank(text)) return false
        val trimmed = lower(ExtensionStrUtils.trim(text))
        if (trimmed == "redgifs.com" || strEndsWith(trimmed, ".redgifs.com")) return true
        if (isHttpUrl(trimmed)) {
            val host = extractHostLower(trimmed)
            return host == "redgifs.com" || strEndsWith(host, ".redgifs.com")
        }
        return false
    }

    fun isRedGifsPost(target: Any?): Boolean {
        if (target == null) return false
        try {
            val k2 = getFieldValue(target, "k2") as? String
            if (isRedGifs(k2)) return true
            val g2 = getFieldValue(target, "g2") as? String
            if (isRedGifs(g2)) return true
            try {
                val domain = target.javaClass.getMethod("getDomain").invoke(target) as? String
                if (isRedGifs(domain)) return true
            } catch (_: Throwable) {}
            try {
                val url = target.javaClass.getMethod("getUrl").invoke(target) as? String
                if (isRedGifs(url)) return true
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        return false
    }

    private fun hasDownloadableMediaDirect(post: Any?, depth: Int = 0): Boolean {
        if (post == null || depth > 2) return false

        // Fast path: FeedPostBundle -> only show pill when actual downloadable URLs exist!
        if (post is FeedPostBundle) {
            val cached = MEDIA_CACHE_BY_ID[post.postId]
            if (cached != null) {
                return cached.urls.isNotEmpty()
            }
            val media = extractFeedPostBundle(post)
            return media.urls.isNotEmpty()
        }

        val clsName = post.javaClass.name

        if (clsName == "zj60" || strEndsWith(clsName, ".zj60")) {
            val d = getFieldValue(post, "d")
            if (d != null) {
                val result = hasMediaInContentModel(d, 0)
                if (result != null) return result
            }
        }

        if (clsName == "com.reddit.domain.model.Link") {
            return hasMediaInLink(post, depth)
        }

        if (strContains(clsName, "LinkPresentationModel")) {
            return hasMediaInLinkPresentationModel(post, depth)
        }

        if (isRedGifsPost(post)) return true

        val media = extractMedia(post, allowCurrentPostFallback = false)
        return media.urls.isNotEmpty() || (!isBlank(media.currentUrl) && isHttpUrl(media.currentUrl))
    }

    private fun hasMediaInLink(post: Any, depth: Int): Boolean {
        try {
            try {
                if (post.javaClass.getMethod("isVideo").invoke(post) as? Boolean == true) return true
            } catch (_: Throwable) {}
            try {
                val gallery = post.javaClass.getMethod("getGallery").invoke(post)
                if (gallery != null) {
                    val items = gallery.javaClass.getMethod("getItems").invoke(gallery) as? List<*>
                    if (items != null && items.isNotEmpty()) return true
                }
            } catch (_: Throwable) {}
            try {
                val meta = post.javaClass.getMethod("getMediaMetadata").invoke(post) as? Map<*, *>
                if (meta != null && meta.isNotEmpty()) return true
            } catch (_: Throwable) {}
            try {
                val mediaObj = post.javaClass.getMethod("getMedia").invoke(post)
                if (mediaObj != null) {
                    if (getFieldValue(mediaObj, "redditVideo") != null || getFieldValue(mediaObj, "video") != null) return true
                }
            } catch (_: Throwable) {}
            try {
                val url = post.javaClass.getMethod("getUrl").invoke(post) as? String
                if (!isBlank(url) && (isMediaUrl(url!!) || isRedGifs(url))) return true
            } catch (_: Throwable) {}
            if (depth < 2) {
                try {
                    val parents = post.javaClass.getMethod("getCrosspostParentList").invoke(post) as? List<*>
                    if (parents != null && parents.isNotEmpty()) {
                        val parent = parents[0]
                        if (parent != null && hasMediaInLink(parent, depth + 1)) return true
                    }
                } catch (_: Throwable) {}
            }
            try {
                val isSelf = post.javaClass.getMethod("isSelf").invoke(post) as? Boolean
                val preview = post.javaClass.getMethod("getPreview").invoke(post)
                if (preview != null && isSelf != true) {
                    val vp = try { preview.javaClass.getMethod("getRedditVideoPreview").invoke(preview) } catch (_: Throwable) { null }
                    if (vp != null) return true
                    val imgs = try { preview.javaClass.getMethod("getImages").invoke(preview) as? List<*> } catch (_: Throwable) { null }
                    if (imgs != null && imgs.isNotEmpty()) {
                        val src = try { imgs[0]?.let { it.javaClass.getMethod("getSource").invoke(it) } } catch (_: Throwable) { null }
                        val pUrl = try { src?.javaClass?.getMethod("getUrl")?.invoke(src) as? String } catch (_: Throwable) { null }
                        if (!isBlank(pUrl) && !strContainsIgnoreCase(pUrl, "external-preview.redd.it")) return true
                    }
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        return false
    }

    private fun hasMediaInLinkPresentationModel(post: Any, depth: Int): Boolean {
        try {
            val a = getFieldValue(post, "a")?.toString() ?: ""
            if (a == "IMAGE" || a == "SELF_IMAGE" || a == "VIDEO" || a == "GIF" || a == "MEDIA_GALLERY") {
                return true
            }
            // Check gallery & embedded video models before SELF/WEBSITE early exits
            val c2 = getFieldValue(post, "C2") as? List<*>
            if (c2 != null && c2.isNotEmpty()) return true
            if (getFieldValue(post, "mediaGalleryUiModel") != null) return true
            val e2 = getFieldValue(post, "E2")
            if (e2 != null && (getFieldValue(e2, "redditVideo") != null || getFieldValue(e2, "video") != null)) {
                return true
            }
            val d2 = getFieldValue(post, "D2")
            if (d2 != null) {
                try {
                    if (d2.javaClass.getMethod("getRedditVideoPreview").invoke(d2) != null) return true
                } catch (_: Throwable) {}
            }
            if (a == "WEBSITE") {
                val k2 = getFieldValue(post, "k2") as? String
                val g2 = getFieldValue(post, "g2") as? String
                return isRedGifs(k2) || isRedGifs(g2) || (!isBlank(g2) && isMediaUrl(g2!!))
            }
            if (a == "SELF" || a == "POLL" || a == "DEV_PLATFORM") {
                val f2 = getFieldValue(post, "F2")
                return f2 != null && hasDownloadableMediaDirect(f2, depth + 1)
            }
            if (d2 != null) {
                try {
                    val imgs = d2.javaClass.getMethod("getImages").invoke(d2) as? List<*>
                    if (imgs != null && imgs.isNotEmpty()) {
                        val src = try { imgs[0]?.let { it.javaClass.getMethod("getSource").invoke(it) } } catch (_: Throwable) { null }
                        val pUrl = try { src?.javaClass?.getMethod("getUrl")?.invoke(src) as? String } catch (_: Throwable) { null }
                        if (!isBlank(pUrl) && !strContainsIgnoreCase(pUrl, "external-preview.redd.it")) return true
                    }
                } catch (_: Throwable) {}
            }
            val f2 = getFieldValue(post, "F2")
            if (f2 != null && hasDownloadableMediaDirect(f2, depth + 1)) return true
        } catch (_: Throwable) {}
        return false
    }

    private fun hasMediaInContentModel(content: Any, depth: Int): Boolean? {
        if (depth > 3) return null
        val simpleName = content.javaClass.simpleName
        return when (simpleName) {
            "wc60", "yc60", "vc60" -> true
            "tc60", "uc60", "bd60" -> false
            "rc60" -> {
                val nested = getFieldValue(content, "o")
                if (nested != null) hasMediaInContentModel(nested, depth + 1) else false
            }
            else -> null
        }
    }

    private fun getFieldValue(obj: Any, fieldName: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun findClass(cl: ClassLoader?, name: String): Class<*>? {
        val candidates = arrayOf(name, "defpackage.$name", "com.reddit.$name")
        for (cand in candidates) {
            try {
                return cl?.loadClass(cand) ?: Class.forName(cand)
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun resolveActivity(target: Any?): Activity? {
        if (target is Activity) return target
        if (target is View) {
            var ctx: Context? = target.context
            var depth = 0
            while (ctx is ContextWrapper && depth++ < 20) {
                if (ctx is Activity) return ctx
                val next = ctx.baseContext
                if (next === ctx) break
                ctx = next
            }
        }
        if (target != null) {
            try {
                val act = target.javaClass.getMethod("o3").invoke(target) as? Activity
                if (act != null) return act
            } catch (_: Throwable) {}
        }
        return getCurrentActivity()
    }

    // --- Jetpack Compose Action Bar Pill Renderer (Hot Path - Zero Per-Frame Overhead) ---

    @JvmStatic
    fun renderDownloadPill(
        style: Any?,
        shareM20: Any?,
        composer: Any?,
        kcqInstance: Any? = null,
        tagInstance: Any? = null,
        isRefresh: Boolean = false,
        modifierInstance: Any? = null,
        k30Instance: Any? = null
    ) {
        if (composer == null || shareM20 == null) return

        var downloadM20: Any? = null
        var icon: Any? = null
        var tag: Any? = null
        var renderMethod: Method? = null

        try {
            if (!sLifecycleRegistered) {
                ensureTouchTracker()
            }

            // Look up bound post for this shareM20; only fall back to sCurrentPost for PDP
            val boundPost = getPostCache(shareM20)
            val resolvedPost = boundPost ?: sCurrentPost?.get()
            if (resolvedPost == null || !hasDownloadableMediaDirect(resolvedPost)) {
                return
            }

            val targetClass = sTargetClass ?: run {
                val cl = (style ?: composer).javaClass.classLoader
                findClass(cl, "h30")?.also { sTargetClass = it }
            } ?: return

            tag = tagInstance ?: sCachedTag ?: run {
                val t = try {
                    targetClass.getDeclaredField("c").apply { isAccessible = true }.get(null)
                } catch (_: Throwable) { null } ?: try {
                    targetClass.getDeclaredField("b").apply { isAccessible = true }.get(null)
                } catch (_: Throwable) { null }
                if (t != null) sCachedTag = t
                t
            } ?: return

            icon = sCachedKcq ?: run {
                val iconResId = getDownloadIconResId(getAppContext())
                if (iconResId == 0) return
                val ctor = sKcqConstructor ?: run {
                    val kcqClass = kcqInstance?.javaClass ?: findClass(targetClass.classLoader, "kcq")
                    kcqClass?.let {
                        try {
                            it.getDeclaredConstructor(
                                java.lang.Long.TYPE,
                                java.lang.Integer.TYPE,
                                java.lang.Boolean.TYPE
                            ).apply { isAccessible = true }.also { c -> sKcqConstructor = c }
                        } catch (_: Throwable) { null }
                    }
                }
                try {
                    ctor?.newInstance(0L, iconResId, false)?.also { sCachedKcq = it }
                } catch (_: Throwable) { null }
            } ?: return

            val m20Ctor = sM20Constructor ?: run {
                var found: Constructor<*>? = null
                for (ctor in shareM20.javaClass.declaredConstructors) {
                    val pts = ctor.parameterTypes
                    if (pts.size == 7 &&
                        pts[0] == String::class.java &&
                        pts[3] == java.lang.Boolean.TYPE &&
                        pts[4].isInterface &&
                        pts[5] == java.lang.Long.TYPE &&
                        pts[6] == java.lang.Integer.TYPE
                    ) {
                        ctor.isAccessible = true
                        found = ctor
                        break
                    }
                }
                found?.also { sM20Constructor = it }
            } ?: return

            val postKey: String = if (resolvedPost is FeedPostBundle) {
                resolvedPost.postId
            } else {
                "obj_${System.identityHashCode(resolvedPost)}"
            }
            val callbackKey = "${postKey}_${System.identityHashCode(shareM20)}"
            val callback = createActionCallback(shareM20, resolvedPost, callbackKey, targetClass, m20Ctor)
                ?: DownloadActionCallback(callbackKey) {
                    handleDownloadClick(shareM20, resolvedPost)
                }

            downloadM20 = m20Ctor.newInstance("Download media", null, null, true, callback, 0L, 94) ?: return

            renderMethod = if (isRefresh) {
                sRenderMethodC ?: run {
                    var found: Method? = null
                    for (m in targetClass.declaredMethods) {
                        val pts = m.parameterTypes
                        if (Modifier.isStatic(m.modifiers) &&
                            m.name == "c" &&
                            pts.size == 10 &&
                            pts[0].isAssignableFrom(shareM20.javaClass) &&
                            pts[8] == java.lang.Integer.TYPE &&
                            pts[9] == java.lang.Integer.TYPE
                        ) {
                            m.isAccessible = true
                            found = m
                            break
                        }
                    }
                    found?.also { sRenderMethodC = it }
                }
            } else {
                sRenderMethodB ?: run {
                    var found: Method? = null
                    for (m in targetClass.declaredMethods) {
                        val pts = m.parameterTypes
                        if (Modifier.isStatic(m.modifiers) &&
                            m.name == "b" &&
                            pts.size == 8 &&
                            pts[0].isAssignableFrom(shareM20.javaClass) &&
                            pts[7] == java.lang.Integer.TYPE
                        ) {
                            m.isAccessible = true
                            found = m
                            break
                        }
                    }
                    found?.also { sRenderMethodB = it }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error preparing download pill", t)
            return
        }

        // Phase 2: Invoke Compose render method cleanly outside the swallow block
        val m = renderMethod ?: return
        try {
            if (isRefresh) {
                m.invoke(null, downloadM20, icon, style, tag, null, null, null, composer, 3072, 48)
            } else {
                // Forward modifierInstance and k30Instance so non-null Modifier in h30.b never throws NPE
                m.invoke(null, downloadM20, icon, style, tag, modifierInstance, k30Instance, composer, 3072)
            }
        } catch (ite: InvocationTargetException) {
            val cause = ite.targetException
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            Log.e(TAG, "Error rendering download pill", cause ?: ite)
        } catch (t: Throwable) {
            Log.e(TAG, "Error invoking download pill renderer", t)
        }
    }

    private fun handleDownloadClick(shareM20: Any?, target: Any?) {
        val isLongPress = consumeIsLongPress()
        val act = getCurrentActivity() ?: getAppContext() ?: return
        MAIN_HANDLER.post {
            try {
                val activeTarget = getPostCache(shareM20) ?: target
                if (activeTarget == null) return@post
                val allowFallback = activeTarget !is FeedPostBundle
                val media = extractMedia(activeTarget, allowCurrentPostFallback = allowFallback)
                val currentUrl = media.currentUrl ?: if (media.urls.isNotEmpty()) media.urls[0] else null
                if (isLongPress) {
                    MediaDownloadHelper.onLongPress(act, currentUrl, media.urls, media.title)
                } else {
                    MediaDownloadHelper.onTap(act, currentUrl, media.urls, media.title)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling download click", t)
            }
        }
    }

    private class ActionCallbackHandler(
        private val shareM20: Any?,
        private val target: Any?,
        private val callbackKey: Any
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "invoke" -> {
                    handleDownloadClick(shareM20, target)
                    DownloadActionCallback.getUnitInstance()
                }
                "hashCode" -> callbackKey.hashCode()
                "equals" -> {
                    val other = if (args != null && args.isNotEmpty()) args[0] else null
                    if (other === proxy) return true
                    if (other != null && Proxy.isProxyClass(other.javaClass)) {
                        val h = try { Proxy.getInvocationHandler(other) } catch (_: Throwable) { null }
                        if (h is ActionCallbackHandler) {
                            return callbackKey == h.callbackKey
                        }
                    }
                    false
                }
                "toString" -> "DownloadActionCallbackProxy($callbackKey)"
                else -> null
            }
        }
    }

    private fun createActionCallback(
        shareM20: Any?,
        target: Any?,
        callbackKey: Any,
        targetClass: Class<*>,
        m20Ctor: Constructor<*>
    ): Any? {
        return try {
            val fn0Class = sFunction0Class ?: run {
                val paramTypes = m20Ctor.parameterTypes
                val cls = if (paramTypes.size > 4) {
                    paramTypes[4]
                } else {
                    try { targetClass.classLoader?.loadClass("kotlin.jvm.functions.Function0") } catch (_: Throwable) { null }
                }
                if (cls != null && cls.isInterface) {
                    sFunction0Class = cls
                    cls
                } else null
            } ?: return null

            Proxy.newProxyInstance(
                fn0Class.classLoader ?: targetClass.classLoader,
                arrayOf(fn0Class),
                ActionCallbackHandler(shareM20, target, callbackKey)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create proxy action callback", t)
            null
        }
    }

    @JvmStatic
    fun renderDownloadPillB(
        shareM20: Any?,
        kcq: Any?,
        style: Any?,
        tag: Any?,
        modifier: Any?,
        k30: Any?,
        composer: Any?,
        changed: Int
    ) {
        renderDownloadPill(style, shareM20, composer, kcq, tag, isRefresh = false, modifierInstance = modifier, k30Instance = k30)
    }

    @JvmStatic
    fun renderDownloadPillC(
        shareM20: Any?,
        kcq: Any?,
        appearance: Any?,
        tag: Any?,
        modifier: Any?,
        size: Any?,
        k30: Any?,
        composer: Any?,
        changed1: Int,
        changed2: Int
    ) {
        renderDownloadPill(appearance, shareM20, composer, kcq, tag, isRefresh = true)
    }

    @JvmStatic
    fun renderDownloadButton(
        p0: Any?,
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?
    ) {
        renderDownloadPill(p1, p4, p6)
    }

    @JvmStatic
    fun hookLightboxToolbar(screen: Any?, toolbar: Any?) {
        val tb = toolbar as? ViewGroup ?: return
        tb.post {
            try {
                attachDownloadClicks(screen, tb)
            } catch (_: Throwable) {}
        }
    }

    private fun attachDownloadClicks(screen: Any?, vg: ViewGroup) {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: continue
            val desc = child.contentDescription?.toString() ?: ""
            val idName = try { child.resources.getResourceEntryName(child.id) } catch (_: Throwable) { "" }
            if ((strContainsIgnoreCase(desc, "download") || strContainsIgnoreCase(idName, "download")) &&
                (child !is ViewGroup || child.isClickable)
            ) {
                child.setOnClickListener {
                    // Direct tap handling without re-checking sLastTouchDurationMs since dedicated long-click is wired
                    onNativeTap(screen)
                }
                child.setOnLongClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onNativeLongPress(screen)
                    true
                }
            }
            if (child is ViewGroup) {
                attachDownloadClicks(screen, child)
            }
        }
    }

    // --- Legacy Native Download Override Interceptors ---

    private fun onNativeTap(target: Any?) {
        MAIN_HANDLER.post {
            try {
                val context = resolveActivity(target) ?: getAppContext() ?: return@post
                val media = extractMedia(target, allowCurrentPostFallback = true)
                val currentUrl = media.currentUrl ?: if (media.urls.isNotEmpty()) media.urls[0] else null
                MediaDownloadHelper.onTap(context, currentUrl, media.urls, media.title)
            } catch (t: Throwable) {
                Log.e(TAG, "Error in onNativeTap", t)
            }
        }
    }

    @JvmStatic
    fun onNativeDownload(target: Any?) {
        ensureTouchTracker()
        val isLongPress = consumeIsLongPress()
        MAIN_HANDLER.post {
            try {
                val context = resolveActivity(target) ?: getAppContext() ?: return@post
                val media = extractMedia(target, allowCurrentPostFallback = true)
                val currentUrl = media.currentUrl ?: if (media.urls.isNotEmpty()) media.urls[0] else null
                if (isLongPress) {
                    MediaDownloadHelper.onLongPress(context, currentUrl, media.urls, media.title)
                } else {
                    MediaDownloadHelper.onTap(context, currentUrl, media.urls, media.title)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in onNativeDownload", t)
            }
        }
    }

    @JvmStatic
    fun onNativeLongPress(target: Any?) {
        ensureTouchTracker()
        MAIN_HANDLER.post {
            try {
                val context = resolveActivity(target) ?: getAppContext() ?: return@post
                val media = extractMedia(target, allowCurrentPostFallback = true)
                val currentUrl = media.currentUrl ?: if (media.urls.isNotEmpty()) media.urls[0] else null
                MediaDownloadHelper.onLongPress(context, currentUrl, media.urls, media.title)
            } catch (t: Throwable) {
                Log.e(TAG, "Error in onNativeLongPress", t)
            }
        }
    }

    @JvmStatic
    fun onSaveMedia(screen: Any?, uri: String?, link: Any?) {
        ensureTouchTracker()
        val isLongPress = consumeIsLongPress()
        MAIN_HANDLER.post {
            try {
                val context = resolveActivity(screen) ?: getAppContext() ?: return@post
                val cleanUri = if (!isBlank(uri)) unescapeAmp(uri!!) else null
                val linkMedia = if (link != null) extractMedia(link, allowCurrentPostFallback = false) else MediaInfo()
                val media = if (linkMedia.urls.isEmpty() && screen != null) {
                    val screenMedia = extractMedia(screen, allowCurrentPostFallback = cleanUri == null)
                    if ((isBlank(screenMedia.title) || screenMedia.title == "reddit_media") &&
                        !isBlank(linkMedia.title) && linkMedia.title != "reddit_media"
                    ) {
                        screenMedia.title = linkMedia.title
                    }
                    screenMedia
                } else {
                    linkMedia
                }
                if (cleanUri != null && !containsSameMedia(media.urls, cleanUri)) {
                    media.urls.add(0, cleanUri)
                }
                val currentUrl = cleanUri ?: media.currentUrl ?: if (media.urls.isNotEmpty()) media.urls[0] else null
                if (isLongPress) {
                    MediaDownloadHelper.onLongPress(context, currentUrl, media.urls, media.title)
                } else {
                    MediaDownloadHelper.onTap(context, currentUrl, media.urls, media.title)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in onSaveMedia", t)
            }
        }
    }

    // --- Media Extraction Engine ---

    class MediaInfo {
        val urls: MutableList<String> = ArrayList()
        var currentUrl: String? = null
        var title: String? = "reddit_media"
        var isVideo = false
        var isGallery = false
        var isSingleVideoModel = false

        fun copy(): MediaInfo {
            val c = MediaInfo()
            c.urls.addAll(urls)
            c.currentUrl = currentUrl
            c.title = title
            c.isVideo = isVideo
            c.isGallery = isGallery
            c.isSingleVideoModel = isSingleVideoModel
            return c
        }
    }

    private fun extractLastSegmentLower(url: String): String {
        return try {
            lower(Uri.parse(url).lastPathSegment ?: "")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun containsSameMedia(urls: List<String>, candidate: String): Boolean {
        if (urls.contains(candidate)) return true
        val normCand = MediaDownloadHelper.normalizeUrl(candidate)
        val candSeg = extractLastSegmentLower(normCand)
        for (u in urls) {
            val normU = MediaDownloadHelper.normalizeUrl(u)
            if (normU == normCand) return true
            if (candSeg.length >= 6 && extractLastSegmentLower(normU) == candSeg) return true
        }
        return false
    }

    private fun hasDirectVideoUrl(info: MediaInfo): Boolean {
        for (u in info.urls) {
            if (videoCandidateScore(u) > 0) return true
        }
        return false
    }

    private fun extractFeedPostBundle(bundle: FeedPostBundle): MediaInfo {
        val cached = MEDIA_CACHE_BY_ID[bundle.postId]
        if (cached != null) return cached.copy()

        val verBefore = FEED_ELEMENT_VERSION[bundle.postId]?.get() ?: 0
        val info = MediaInfo()
        val liveLink = POST_BY_ID[bundle.postId] ?: bundle.initialLink
        if (liveLink != null) {
            val fromLink = extractMedia(liveLink, allowCurrentPostFallback = false)
            info.urls.addAll(fromLink.urls)
            info.currentUrl = fromLink.currentUrl
            info.title = fromLink.title
            info.isVideo = fromLink.isVideo
            info.isGallery = fromLink.isGallery
            info.isSingleVideoModel = fromLink.isSingleVideoModel
        }

        val liveElements = FEED_ELEMENTS_BY_ID[bundle.postId]
        var hasIoq0 = false
        if (liveElements != null) {
            for (el in liveElements) {
                val n = el.javaClass.name
                if (n == "ioq0" || strEndsWith(n, ".ioq0")) {
                    hasIoq0 = true
                    break
                }
            }
        }

        // Scan feed elements when liveLink had no URLs, OR when this is a video/ioq0 post without a direct video URL yet
        if (liveElements != null && liveElements.isNotEmpty() &&
            (info.urls.isEmpty() || ((info.isVideo || hasIoq0) && !hasDirectVideoUrl(info)))
        ) {
            for (el in liveElements) {
                scanFeedElementForMedia(el, info, IdentityHashMap(), 0)
            }
        }

        deduplicatePreviewResolutions(info)
        normalizeVideoMediaInfo(info)

        val verAfter = FEED_ELEMENT_VERSION[bundle.postId]?.get() ?: 0
        if (verBefore == verAfter && (info.urls.isNotEmpty() || liveLink != null)) {
            MEDIA_CACHE_BY_ID[bundle.postId] = info.copy()
        }
        return info
    }

    // Keeps only the highest-width (or unsized source) preview.redd.it URL per unique path
    private fun deduplicatePreviewResolutions(info: MediaInfo) {
        if (info.urls.size <= 1) return
        val bestByPath = LinkedHashMap<String, String>()
        val bestWidthByPath = LinkedHashMap<String, Int>()
        val order = ArrayList<String>(info.urls.size)

        for (u in info.urls) {
            if (extractHostLower(u) == "preview.redd.it") {
                val path = extractPathLower(u)
                val w = try {
                    val qp = Uri.parse(u).getQueryParameter("width")
                    if (isBlank(qp)) Int.MAX_VALUE else parsePositiveInt(qp)
                } catch (_: Throwable) {
                    Int.MAX_VALUE
                }
                val prevW = bestWidthByPath[path]
                if (prevW == null) {
                    bestWidthByPath[path] = w
                    bestByPath[path] = u
                    order.add("P:$path")
                } else if (w > prevW) {
                    bestWidthByPath[path] = w
                    bestByPath[path] = u
                }
            } else {
                order.add("U:$u")
            }
        }

        val deduped = ArrayList<String>(order.size)
        for (token in order) {
            val resolved = if (strStartsWith(token, "P:")) {
                bestByPath[token.substring(2)]
            } else {
                token.substring(2)
            }
            if (resolved != null && !deduped.contains(resolved)) {
                deduped.add(resolved)
            }
        }
        info.urls.clear()
        info.urls.addAll(deduped)
        val cur = info.currentUrl
        if (cur != null && !info.urls.contains(cur)) {
            if (extractHostLower(cur) == "preview.redd.it") {
                val upgraded = bestByPath[extractPathLower(cur)]
                info.currentUrl = upgraded ?: if (info.urls.isNotEmpty()) info.urls[0] else null
            } else {
                info.currentUrl = if (info.urls.isNotEmpty()) info.urls[0] else null
            }
        }
    }

    fun extractMedia(
        target: Any?,
        allowCurrentPostFallback: Boolean = true,
        visited: IdentityHashMap<Any, Boolean> = IdentityHashMap()
    ): MediaInfo {
        val info = MediaInfo()
        if (target == null) return info
        if (visited.containsKey(target)) return info
        visited[target] = java.lang.Boolean.TRUE

        try {
            if (target is FeedPostBundle) {
                return extractFeedPostBundle(target)
            }

            val cached = getPostCache(target)
            if (cached != null && cached !== target && !visited.containsKey(cached)) {
                val fromCached = extractMedia(cached, allowCurrentPostFallback = false, visited = visited)
                if (fromCached.urls.isNotEmpty()) {
                    return fromCached
                }
            }

            var actualTarget: Any = target

            // Unwrap AsyncScreenArg if present (walking superclasses)
            try {
                var cls: Class<*>? = actualTarget.javaClass
                outer@ while (cls != null && cls != Any::class.java) {
                    for (f in cls.declaredFields) {
                        if (strContains(f.type.name, "AsyncScreenArg")) {
                            f.isAccessible = true
                            val screenArg = f.get(actualTarget)
                            if (screenArg != null) {
                                val parcelable = screenArg.javaClass.getMethod("u").invoke(screenArg)
                                if (parcelable != null) {
                                    actualTarget = parcelable
                                    break@outer
                                }
                            }
                        }
                    }
                    cls = cls.superclass
                }
            } catch (_: Throwable) {}

            val targetObj = actualTarget

            // Current media URL via t5() on LightboxScreen (never blocks gallery/crosspost extraction!)
            var t5Url: String? = null
            try {
                val cur = target.javaClass.getMethod("t5").invoke(target) as? String
                if (!isBlank(cur) && isHttpUrl(cur) && !isStreamingManifest(cur!!) && !isBareVRedditUrl(cur)) {
                    val clean = unescapeAmp(cur)
                    t5Url = clean
                    info.currentUrl = clean
                }
            } catch (_: Throwable) {}

            // LinkPresentationModel
            if (strContains(targetObj.javaClass.name, "LinkPresentationModel")) {
                extractFromLinkPresentationModel(targetObj, info, visited)
            }

            // Title via getTitle()
            try {
                val t = targetObj.javaClass.getMethod("getTitle").invoke(targetObj) as? String
                if (!isBlank(t)) {
                    info.title = t
                }
            } catch (_: Throwable) {}

            // Direct URL via getUrl() (rejects bare v.redd.it/<id> HTML redirects)
            try {
                val url = targetObj.javaClass.getMethod("getUrl").invoke(targetObj) as? String
                if (!isBlank(url) && (isMediaUrl(url!!) || isRedGifs(url))) {
                    val clean = unescapeAmp(url)
                    if (!info.urls.contains(clean)) {
                        info.urls.add(clean)
                    }
                }
            } catch (_: Throwable) {}

            // MediaMetadata map (used by Reddit galleries)
            val mediaMetadataMap: Map<*, *>? = try {
                targetObj.javaClass.getMethod("getMediaMetadata").invoke(targetObj) as? Map<*, *>
            } catch (_: Throwable) { null }

            // Gallery via getGallery().getItems() + mediaMetadata lookup (prefers source descriptor over item.getUrl())
            var extractedGallery = false
            try {
                val gallery = targetObj.javaClass.getMethod("getGallery").invoke(targetObj)
                if (gallery != null) {
                    val items = gallery.javaClass.getMethod("getItems").invoke(gallery) as? List<*>
                    if (items != null && items.isNotEmpty()) {
                        info.isGallery = true
                        for (item in items) {
                            if (item == null) continue
                            var resolvedUrl: String? = null

                            val mediaId = try {
                                item.javaClass.getMethod("getMediaId").invoke(item) as? String
                            } catch (_: Throwable) { null }

                            // Prefer full-res source descriptor from mediaMetadataMap first
                            if (!isBlank(mediaId) && mediaMetadataMap != null) {
                                val meta = mediaMetadataMap[mediaId]
                                if (meta != null) {
                                    resolvedUrl = extractUrlFromMediaMetaData(meta)
                                }
                            }

                            if (isBlank(resolvedUrl)) {
                                try {
                                    val itemUrl = item.javaClass.getMethod("getUrl").invoke(item) as? String
                                    if (!isBlank(itemUrl) && isHttpUrl(itemUrl) && !isStreamingManifest(itemUrl!!)) {
                                        resolvedUrl = itemUrl
                                    }
                                } catch (_: Throwable) {}
                            }

                            if (isBlank(resolvedUrl)) {
                                try {
                                    val resolutions = item.javaClass.getMethod("getResolutions").invoke(item) as? List<*>
                                    if (resolutions != null && resolutions.isNotEmpty()) {
                                        val best = resolutions[resolutions.size - 1]
                                        if (best != null) {
                                            val rUrl = best.javaClass.getMethod("getUrl").invoke(best) as? String
                                            if (!isBlank(rUrl) && isHttpUrl(rUrl)) resolvedUrl = rUrl
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }

                            if (isBlank(resolvedUrl) && !isBlank(mediaId)) {
                                val mime = try {
                                    item.javaClass.getMethod("getMimeType").invoke(item) as? String
                                } catch (_: Throwable) { null }
                                if (!strContainsIgnoreCase(mime, "video") && !strContainsIgnoreCase(mime, "mp4")) {
                                    val ext = when {
                                        strContainsIgnoreCase(mime, "png") -> "png"
                                        strContainsIgnoreCase(mime, "gif") -> "gif"
                                        strContainsIgnoreCase(mime, "webp") -> "webp"
                                        else -> "jpg"
                                    }
                                    resolvedUrl = "https://i.redd.it/$mediaId.$ext"
                                }
                            }

                            if (!isBlank(resolvedUrl)) {
                                val clean = unescapeAmp(resolvedUrl!!)
                                if (!info.urls.contains(clean)) {
                                    info.urls.add(clean)
                                    extractedGallery = true
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}

            if (!extractedGallery && mediaMetadataMap != null && mediaMetadataMap.isNotEmpty()) {
                if (mediaMetadataMap.size > 1) info.isGallery = true
                for (meta in mediaMetadataMap.values) {
                    if (meta != null) {
                        val metaUrl = extractUrlFromMediaMetaData(meta)
                        if (!isBlank(metaUrl)) {
                            val clean = unescapeAmp(metaUrl!!)
                            if (!info.urls.contains(clean)) {
                                info.urls.add(clean)
                            }
                        }
                    }
                }
            }

            // Check Link.isVideo() BEFORE getPreview() so video posts never fall back to a JPEG poster
            try {
                if (targetObj.javaClass.getMethod("isVideo").invoke(targetObj) as? Boolean == true) {
                    info.isVideo = true
                    info.isSingleVideoModel = true
                }
            } catch (_: Throwable) {}

            // LinkMedia via getMedia()
            try {
                val linkMedia = targetObj.javaClass.getMethod("getMedia").invoke(targetObj)
                if (linkMedia != null) {
                    extractFromLinkMedia(linkMedia, info)
                }
            } catch (_: Throwable) {}

            // Crosspost parent list on com.reddit.domain.model.Link
            if (info.urls.isEmpty()) {
                try {
                    val parents = targetObj.javaClass.getMethod("getCrosspostParentList").invoke(targetObj) as? List<*>
                    if (parents != null && parents.isNotEmpty()) {
                        val parent = parents[0]
                        if (parent != null && !visited.containsKey(parent)) {
                            val fromParent = extractMedia(parent, allowCurrentPostFallback = false, visited = visited)
                            for (u in fromParent.urls) {
                                if (!info.urls.contains(u)) info.urls.add(u)
                            }
                            if (fromParent.isVideo) info.isVideo = true
                            if (fromParent.isGallery) info.isGallery = true
                            if ((isBlank(info.title) || info.title == "reddit_media") && !isBlank(fromParent.title)) {
                                info.title = fromParent.title
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            // Preview via getPreview() ONLY if we don't already have full-res media
            if (info.urls.isEmpty()) {
                try {
                    val preview = targetObj.javaClass.getMethod("getPreview").invoke(targetObj)
                    if (preview != null) {
                        extractFromPreview(preview, info)
                    }
                } catch (_: Throwable) {}
            }

            // Presentation model inspection strictly on zj60
            val targetClsName = targetObj.javaClass.name
            if (targetClsName == "zj60" || strEndsWith(targetClsName, ".zj60")) {
                try {
                    val titleObj = getFieldValue(targetObj, "c")
                    if (titleObj != null) {
                        val t = getFieldValue(titleObj, "a") as? String
                        if (!isBlank(t)) {
                            info.title = t
                        }
                    }
                    val content = getFieldValue(targetObj, "d")
                    if (content != null) {
                        extractFromContent(content, info, 0)
                    }
                } catch (_: Throwable) {}
            }

            // Merge t5Url if not already present (dedup by normalized URL / lastPathSegment)
            if (t5Url != null && !containsSameMedia(info.urls, t5Url)) {
                info.urls.add(0, t5Url)
            }

            deduplicatePreviewResolutions(info)
            normalizeVideoMediaInfo(info)

            val curPost = sCurrentPost?.get()
            if (allowCurrentPostFallback && info.urls.isEmpty() && curPost != null && curPost !== target && !visited.containsKey(curPost)) {
                val fallback = extractMedia(curPost, allowCurrentPostFallback = false, visited = visited)
                if (fallback.urls.isNotEmpty()) {
                    info.urls.addAll(fallback.urls)
                    if (isBlank(info.currentUrl)) info.currentUrl = fallback.currentUrl
                    if (isBlank(info.title) || info.title == "reddit_media") info.title = fallback.title
                    if (fallback.isVideo) info.isVideo = true
                    if (fallback.isGallery) info.isGallery = true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "extractMedia error", t)
        }

        return info
    }

    private fun extractUrlFromMediaMetaData(meta: Any): String? {
        try {
            val sourceDesc = try { meta.javaClass.getMethod("getSourceImageDescriptor").invoke(meta) } catch (_: Throwable) { null }
            if (sourceDesc != null) {
                val mp4Url = try { sourceDesc.javaClass.getMethod("getMp4Url").invoke(sourceDesc) as? String } catch (_: Throwable) { null }
                if (!isBlank(mp4Url) && isHttpUrl(mp4Url)) return mp4Url
                val gifUrl = try { sourceDesc.javaClass.getMethod("getGifUrl").invoke(sourceDesc) as? String } catch (_: Throwable) { null }
                if (!isBlank(gifUrl) && isHttpUrl(gifUrl)) return gifUrl
                val imgUrl = try { sourceDesc.javaClass.getMethod("getImageUrl").invoke(sourceDesc) as? String } catch (_: Throwable) { null }
                if (!isBlank(imgUrl) && isHttpUrl(imgUrl)) return imgUrl
            }
            val previews = try { meta.javaClass.getMethod("getPreviewImageDescriptor").invoke(meta) as? List<*> } catch (_: Throwable) { null }
            if (previews != null && previews.isNotEmpty()) {
                val bestPreview = previews[previews.size - 1]
                if (bestPreview != null) {
                    val pUrl = try { bestPreview.javaClass.getMethod("getImageUrl").invoke(bestPreview) as? String } catch (_: Throwable) { null }
                    if (!isBlank(pUrl) && isHttpUrl(pUrl)) return pUrl
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun extractFromLinkPresentationModel(model: Any, info: MediaInfo, visited: IdentityHashMap<Any, Boolean>) {
        try {
            val a = getFieldValue(model, "a")?.toString() ?: ""
            if (a == "VIDEO" || a == "GIF") {
                info.isVideo = true
                info.isSingleVideoModel = true
            }
            val c = getFieldValue(model, "c") as? String
            if (!isBlank(c)) info.title = c

            val k2 = getFieldValue(model, "k2") as? String
            val g2 = getFieldValue(model, "g2") as? String
            if (isHttpUrl(g2) && (isRedGifs(g2) || isMediaUrl(g2!!))) {
                val clean = unescapeAmp(g2!!)
                if (!info.urls.contains(clean)) info.urls.add(clean)
            } else if (isHttpUrl(k2) && isRedGifs(k2)) {
                val clean = unescapeAmp(k2!!)
                if (!info.urls.contains(clean)) info.urls.add(clean)
            }

            getFieldValue(model, "E2")?.let { extractFromLinkMedia(it, info) }
            val c2 = getFieldValue(model, "C2") as? List<*>
            if (c2 != null && c2.isNotEmpty()) {
                info.isGallery = true
                extractFromGalleryList(c2, info)
            }
            // Allow F2 crossposts when C2 didn't populate a gallery and we don't already have a video URL
            if ((c2 == null || c2.isEmpty()) && (!info.isVideo || !hasDirectVideoUrl(info))) {
                getFieldValue(model, "F2")?.let { f2 ->
                    if (!visited.containsKey(f2)) {
                        val linkMedia = extractMedia(f2, allowCurrentPostFallback = false, visited = visited)
                        for (u in linkMedia.urls) {
                            if (!info.urls.contains(u)) info.urls.add(u)
                        }
                        if (linkMedia.isVideo) info.isVideo = true
                        if (linkMedia.isGallery) info.isGallery = true
                        if (linkMedia.isSingleVideoModel) info.isSingleVideoModel = true
                    }
                }
            }
            if (info.urls.isEmpty()) {
                getFieldValue(model, "D2")?.let { extractFromPreview(it, info) }
            }
        } catch (_: Throwable) {}
    }

    private fun extractFromPreview(preview: Any, info: MediaInfo) {
        try {
            try {
                val rv = preview.javaClass.getMethod("getRedditVideoPreview").invoke(preview)
                if (rv != null) extractFromRedditVideo(rv, info)
            } catch (_: Throwable) {}

            // Never leak a video's JPEG poster thumbnail
            if (info.isVideo) return

            val images = preview.javaClass.getMethod("getImages").invoke(preview) as? List<*>
            if (images != null) {
                for (img in images) {
                    if (img == null) continue
                    try {
                        val source = img.javaClass.getMethod("getSource").invoke(img) ?: continue
                        val pUrl = source.javaClass.getMethod("getUrl").invoke(source) as? String
                        if (!isBlank(pUrl) && isHttpUrl(pUrl) && !strContainsIgnoreCase(pUrl, "external-preview.redd.it")) {
                            val clean = unescapeAmp(pUrl!!)
                            if (!info.urls.contains(clean)) info.urls.add(clean)
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun extractFromLinkMedia(linkMedia: Any, info: MediaInfo) {
        try {
            getFieldValue(linkMedia, "redditVideo")?.let { extractFromRedditVideo(it, info) }
            getFieldValue(linkMedia, "video")?.let { vm ->
                val url = getFieldValue(vm, "url") as? String
                if (!isBlank(url) && isHttpUrl(url) && !isStreamingManifest(url!!) && !isBareVRedditUrl(url)) {
                    val clean = unescapeAmp(url)
                    if (!info.urls.contains(clean)) info.urls.add(clean)
                    info.isVideo = true
                }
            }
        } catch (_: Throwable) {}
    }

    private fun extractFromRedditVideo(rv: Any, info: MediaInfo) {
        try {
            info.isVideo = true
            info.isSingleVideoModel = true
            val candidates = arrayOf("getDownloadUrl", "getPackagedMp4Url", "getFallbackUrl", "getFallBackUrl")
            for (name in candidates) {
                try {
                    val url = rv.javaClass.getMethod(name).invoke(rv) as? String
                    if (!isBlank(url) && isHttpUrl(url) && !isStreamingManifest(url!!) && !isAudioOnlyDashUrl(url) && !isBareVRedditUrl(url)) {
                        val clean = unescapeAmp(url)
                        if (!info.urls.contains(clean)) info.urls.add(clean)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun extractFromGalleryList(galleryList: List<*>, info: MediaInfo) {
        if (galleryList.size > 1) info.isGallery = true
        for (item in galleryList) {
            if (item == null) continue
            var foundPrimary = false
            try {
                val url = getFieldValue(item, "r") as? String
                if (!isBlank(url) && isHttpUrl(url) && !isStreamingManifest(url!!)) {
                    val clean = unescapeAmp(url)
                    if (!info.urls.contains(clean)) info.urls.add(clean)
                    foundPrimary = true
                }
            } catch (_: Throwable) {}
            if (!foundPrimary) {
                var cls: Class<*>? = item.javaClass
                outer@ while (cls != null && cls != Any::class.java) {
                    for (f in cls.declaredFields) {
                        if (f.type == String::class.java && !Modifier.isStatic(f.modifiers)) {
                            try {
                                f.isAccessible = true
                                val strVal = f.get(item) as? String
                                if (!isBlank(strVal) && isPostMediaUrl(strVal!!)) {
                                    val clean = unescapeAmp(strVal)
                                    if (!info.urls.contains(clean)) info.urls.add(clean)
                                    break@outer
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    cls = cls.superclass
                }
            }
        }
    }

    private fun extractFromContent(content: Any, info: MediaInfo, depth: Int) {
        if (depth > 3) return
        try {
            // 1. Single image (wc60) or Video (vc60): field c
            try {
                val cVal = getFieldValue(content, "c")
                if (cVal != null) {
                    if (strContains(cVal.javaClass.name, "VideoMetadata")) {
                        info.isVideo = true
                        getFieldValue(cVal, "b")?.let { scanFeedElementForMedia(it, info, IdentityHashMap(), 0) }
                        getFieldValue(cVal, "j0")?.let { scanFeedElementForMedia(it, info, IdentityHashMap(), 0) }
                        extractStringMediaFields(cVal, info)
                    } else {
                        val source = cVal.javaClass.getMethod("getSource").invoke(cVal)
                        val url = source?.javaClass?.getMethod("getUrl")?.invoke(source) as? String
                        if (!isBlank(url) && isHttpUrl(url) && !isExcludedNonPostMediaUrl(url!!)) {
                            val clean = unescapeAmp(url)
                            if (!info.urls.contains(clean)) info.urls.add(clean)
                        }
                    }
                }
            } catch (_: Throwable) {}

            // 2. Gallery (yc60): field d
            try {
                val raw = getFieldValue(content, "d")
                if (raw is List<*>) {
                    extractFromGalleryList(raw, info)
                } else if (raw is Iterable<*>) {
                    val copy = ArrayList<Any?>()
                    for (el in raw) copy.add(el)
                    extractFromGalleryList(copy, info)
                }
            } catch (_: Throwable) {}

            // 3. Crosspost (rc60): field o
            try {
                val nested = getFieldValue(content, "o")
                if (nested != null) extractFromContent(nested, info, depth + 1)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    // Fast-path direct extraction for native Reddit video feed elements (ioq0)
    // Only accepts actual video URLs (videoCandidateScore > 0), never JPEG poster thumbnails
    private fun extractFromIoq0(el: Any, info: MediaInfo) {
        info.isVideo = true
        info.isSingleVideoModel = true
        try {
            val xVal = getFieldValue(el, "x")
            if (xVal != null) {
                val videoUrls = try { xVal.javaClass.getMethod("a").invoke(xVal) } catch (_: Throwable) { null }
                extractFromVideoUrls(videoUrls, info)
            }
            val strFields = arrayOf("k", "q", "r", "s", "t", "u")
            for (name in strFields) {
                val s = getFieldValue(el, name) as? String
                if (!isBlank(s) && isHttpUrl(s) && videoCandidateScore(s!!) > 0) {
                    val clean = unescapeAmp(s)
                    if (!info.urls.contains(clean)) info.urls.add(clean)
                }
            }
            val wVal = getFieldValue(el, "w")
            if (wVal != null) {
                val wa = getFieldValue(wVal, "a") as? String
                if (!isBlank(wa) && isHttpUrl(wa) && videoCandidateScore(wa!!) > 0) {
                    val clean = unescapeAmp(wa)
                    if (!info.urls.contains(clean)) info.urls.add(clean)
                }
            }
            val fVal = getFieldValue(el, "F")
            if (fVal is Iterable<*>) {
                var count = 0
                for (item in fVal) {
                    if (item == null || count++ >= 8) break
                    for (f in item.javaClass.declaredFields) {
                        if (Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(item) ?: continue
                            if (v is String) {
                                if (isHttpUrl(v) && videoCandidateScore(v) > 0) {
                                    val clean = unescapeAmp(v)
                                    if (!info.urls.contains(clean)) info.urls.add(clean)
                                }
                            } else if (strContains(v.javaClass.name, "VideoUrls")) {
                                extractFromVideoUrls(v, info)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun extractFromVideoUrls(videoUrls: Any?, info: MediaInfo) {
        if (videoUrls == null) return
        info.isVideo = true
        var cls: Class<*>? = videoUrls.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                for (vf in cls.declaredFields) {
                    if (vf.type == String::class.java && !Modifier.isStatic(vf.modifiers)) {
                        vf.isAccessible = true
                        val u = vf.get(videoUrls) as? String
                        if (!isBlank(u) && isHttpUrl(u) && videoCandidateScore(u!!) > 0) {
                            val clean = unescapeAmp(u)
                            if (!info.urls.contains(clean)) info.urls.add(clean)
                        }
                    }
                }
            } catch (_: Throwable) {}
            cls = cls.superclass
        }
    }

    private fun extractStringMediaFields(obj: Any, info: MediaInfo) {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                for (f in cls.declaredFields) {
                    if (f.type == String::class.java && !Modifier.isStatic(f.modifiers)) {
                        f.isAccessible = true
                        val strVal = f.get(obj) as? String
                        if (!isBlank(strVal) && isPostMediaUrl(strVal!!)) {
                            val clean = unescapeAmp(strVal)
                            if (!info.urls.contains(clean)) info.urls.add(clean)
                        }
                    }
                }
            } catch (_: Throwable) {}
            cls = cls.superclass
        }
    }

    private fun isExcludedNonPostMediaUrl(url: String): Boolean {
        val l = lower(url)
        return strContains(l, "snoovatar") ||
            strContains(l, "/avatars/") ||
            strContains(l, "styles.redditmedia.com") ||
            strContains(l, "redditstatic.com") ||
            strContains(l, "award_images") ||
            strContains(l, "/emojis/") ||
            strContains(l, "communityicon") ||
            strContains(l, "bannerimage") ||
            strContains(l, "thumbs.redditmedia.com") ||
            strContains(l, "external-preview.redd.it") ||
            isStreamingManifest(url) ||
            isAudioOnlyDashUrl(url) ||
            isBareVRedditUrl(url)
    }

    private fun isPostMediaUrl(url: String): Boolean {
        if (!isHttpUrl(url)) return false
        if (isExcludedNonPostMediaUrl(url)) return false
        if (isLowResOrBlurredPreview(url)) return false
        val host = extractHostLower(url)
        val path = extractPathLower(url)
        return host == "i.redd.it" ||
            host == "preview.redd.it" ||
            host == "packaged-media.redd.it" ||
            (host == "v.redd.it" && hasMediaExtensionInPath(path)) ||
            host == "redgifs.com" || strEndsWith(host, ".redgifs.com") ||
            hasMediaExtensionInPath(path)
    }

    // Inspect ONLY the filename segment with explicit resolution tokens so random video IDs never skew ranking
    private fun resolutionBonus(lastSegLower: String): Int {
        return when {
            strContains(lastSegLower, "2160p") || strContains(lastSegLower, "_2160") -> 40
            strContains(lastSegLower, "1080p") || strContains(lastSegLower, "_1080") -> 35
            strContains(lastSegLower, "720p") || strContains(lastSegLower, "_720") -> 30
            strContains(lastSegLower, "480p") || strContains(lastSegLower, "_480") -> 20
            strContains(lastSegLower, "360p") || strContains(lastSegLower, "_360") -> 15
            strContains(lastSegLower, "240p") || strContains(lastSegLower, "_240") -> 10
            else -> 5
        }
    }

    private fun videoCandidateScore(url: String): Int {
        if (isStreamingManifest(url) || isAudioOnlyDashUrl(url) || isBareVRedditUrl(url)) return -1
        val host = extractHostLower(url)
        val path = extractPathLower(url)
        val lastSeg = extractLastSegmentLower(url)
        return when {
            host == "packaged-media.redd.it" -> 200 + resolutionBonus(lastSeg)
            host == "redgifs.com" || strEndsWith(host, ".redgifs.com") -> 150
            host == "v.redd.it" && strEndsWith(path, ".mp4") -> 100 + resolutionBonus(lastSeg)
            strEndsWith(path, ".mp4") -> 60 + resolutionBonus(lastSeg)
            else -> 0
        }
    }

    private fun normalizeVideoMediaInfo(info: MediaInfo) {
        if (info.urls.isEmpty()) {
            info.isVideo = false
            return
        }

        val filtered = ArrayList<String>(info.urls.size)
        var bestVideo: String? = null
        var bestScore = 0
        var nonVideoCount = 0
        var videoCount = 0

        for (u in info.urls) {
            if (isStreamingManifest(u) || isAudioOnlyDashUrl(u) || isBareVRedditUrl(u)) continue
            filtered.add(u)
            val score = videoCandidateScore(u)
            if (score > 0) {
                videoCount++
                if (score > bestScore) {
                    bestScore = score
                    bestVideo = u
                }
            } else {
                nonVideoCount++
            }
        }

        info.urls.clear()
        info.urls.addAll(filtered)

        if (info.urls.isEmpty()) {
            info.isVideo = false
            info.currentUrl = null
            return
        }

        if (info.currentUrl != null && !info.urls.contains(info.currentUrl)) {
            info.currentUrl = null
        }

        // Collapse to single best video ONLY for single-video models (ioq0/hrv/RedditVideo) or single-item videos,
        // NEVER wiping out multi-image or mixed galleries!
        val canCollapse = !info.isGallery && (info.isSingleVideoModel || (nonVideoCount <= 1 && videoCount <= 1))
        if (canCollapse && bestVideo != null && (info.isVideo || bestScore >= 60)) {
            info.isVideo = true
            info.urls.clear()
            info.urls.add(bestVideo)
            info.currentUrl = bestVideo
        } else if (bestVideo == null) {
            info.isVideo = false
        }
    }

    private fun scanFeedElementForMedia(
        obj: Any?,
        info: MediaInfo,
        visited: IdentityHashMap<Any, Boolean>,
        depth: Int
    ) {
        if (obj == null || depth > 4 || visited.size >= 80) return
        if (obj is String) {
            if (isPostMediaUrl(obj)) {
                val clean = unescapeAmp(obj)
                if (!info.urls.contains(clean)) info.urls.add(clean)
            }
            return
        }
        val cls = obj.javaClass
        if (cls.isPrimitive ||
            obj is Number ||
            obj is Boolean ||
            obj is Char ||
            obj is Enum<*> ||
            obj is Context ||
            obj is View ||
            obj is Class<*>
        ) {
            return
        }
        if (visited.containsKey(obj)) return
        visited[obj] = java.lang.Boolean.TRUE

        val clsName = cls.name
        if (strStartsWith(clsName, "android.") ||
            strStartsWith(clsName, "androidx.") ||
            strStartsWith(clsName, "java.lang.") ||
            strStartsWith(clsName, "kotlinx.coroutines.") ||
            strStartsWith(clsName, "kotlin.jvm.internal.")
        ) {
            return
        }

        if (clsName == "ioq0" || strEndsWith(clsName, ".ioq0")) {
            extractFromIoq0(obj, info)
            return
        }
        if (strContains(clsName, "RedditVideo")) {
            extractFromRedditVideo(obj, info)
            return
        }
        if (strContains(clsName, "VideoUrls")) {
            extractFromVideoUrls(obj, info)
            return
        }
        if (strContains(clsName, "VideoMetadata")) {
            info.isVideo = true
            getFieldValue(obj, "b")?.let { scanFeedElementForMedia(it, info, visited, depth + 1) }
            getFieldValue(obj, "j0")?.let { scanFeedElementForMedia(it, info, visited, depth + 1) }
            extractStringMediaFields(obj, info)
            return
        }
        if (strContains(clsName, "Preview") && strStartsWith(clsName, "com.reddit.domain.model.")) {
            extractFromPreview(obj, info)
            return
        }

        if (obj is Iterable<*>) {
            var count = 0
            for (item in obj) {
                if (count++ >= 20) break
                scanFeedElementForMedia(item, info, visited, depth + 1)
            }
            return
        }
        if (obj is Map<*, *>) {
            var count = 0
            for (v in obj.values) {
                if (count++ >= 20) break
                scanFeedElementForMedia(v, info, visited, depth + 1)
            }
            return
        }
        if (obj is Array<*>) {
            val max = if (obj.size < 20) obj.size else 20
            for (i in 0 until max) {
                scanFeedElementForMedia(obj[i], info, visited, depth + 1)
            }
            return
        }

        var curCls: Class<*>? = cls
        while (curCls != null && curCls != Any::class.java) {
            try {
                for (f in curCls.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val ft = f.type
                    if (ft.isPrimitive ||
                        ft == java.lang.Integer::class.java ||
                        ft == java.lang.Long::class.java ||
                        ft == java.lang.Boolean::class.java ||
                        ft == java.lang.Float::class.java ||
                        ft == java.lang.Double::class.java
                    ) {
                        continue
                    }
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (v is String) {
                        if (isPostMediaUrl(v)) {
                            val clean = unescapeAmp(v)
                            if (!info.urls.contains(clean)) info.urls.add(clean)
                        }
                    } else {
                        scanFeedElementForMedia(v, info, visited, depth + 1)
                    }
                }
            } catch (_: Throwable) {}
            curCls = curCls.superclass
        }
    }

    private fun isMediaUrl(url: String): Boolean {
        if (!isHttpUrl(url) || isStreamingManifest(url) || isBareVRedditUrl(url)) return false
        val host = extractHostLower(url)
        val path = extractPathLower(url)
        return host == "i.redd.it" ||
            host == "preview.redd.it" ||
            host == "packaged-media.redd.it" ||
            (host == "v.redd.it" && hasMediaExtensionInPath(path)) ||
            host == "redgifs.com" || strEndsWith(host, ".redgifs.com") ||
            hasMediaExtensionInPath(path)
    }

    private fun getDownloadIconResId(context: Context?): Int {
        val cached = sDownloadIconResId
        if (cached >= 0) return cached
        if (context == null) return 0
        val res = context.resources
        val pkg = context.packageName
        val candidates = arrayOf(
            "ic_icon_download",
            "ic_icon_download_outline",
            "icon_download_outline_20",
            "icon_download_outline_24",
            "ic_download",
            "ic_morphe_download_media"
        )
        for (name in candidates) {
            val id = res.getIdentifier(name, "drawable", pkg)
            if (id != 0) {
                sDownloadIconResId = id
                Log.i(TAG, "Using download icon drawable: $name (0x${Integer.toHexString(id)})")
                return id
            }
        }
        sDownloadIconResId = 0
        return 0
    }

    private fun ensureTouchTracker() {
        if (sLifecycleRegistered) return
        TOUCH_LOCK.lock()
        try {
            if (sLifecycleRegistered) return
            val context = getAppContext()
            if (context is Application) {
                context.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                        attachTouchTracker(activity)
                    }
                    override fun onActivityStarted(activity: Activity) {}
                    override fun onActivityResumed(activity: Activity) {
                        sResumedActivity = WeakReference(activity)
                        attachTouchTracker(activity)
                    }
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivityStopped(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {
                        if (sResumedActivity?.get() === activity) {
                            sResumedActivity = null
                        }
                    }
                })
                sLifecycleRegistered = true
            }
        } finally {
            TOUCH_LOCK.unlock()
        }
        val current = getCurrentActivity()
        if (current != null) {
            sResumedActivity = WeakReference(current)
            attachTouchTracker(current)
        }
    }

    private class TouchTrackerHandler(private val original: Window.Callback) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "equals" -> {
                    val other = if (args != null && args.isNotEmpty()) args[0] else null
                    return other === proxy
                }
                "hashCode" -> return System.identityHashCode(proxy)
                "toString" -> return "TouchTrackerWindowCallback($original)"
                "dispatchTouchEvent" -> {
                    if (args != null && args.isNotEmpty()) {
                        val first = args[0]
                        if (first is MotionEvent) when (first.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                sTouchDownTime = first.eventTime
                                sLastTouchDurationMs = 0L
                                sLastTouchUpElapsedMs = 0L
                            }
                            MotionEvent.ACTION_UP -> {
                                if (sTouchDownTime != 0L) {
                                    val diff = first.eventTime - sTouchDownTime
                                    sLastTouchDurationMs = if (diff < 0L) 0L else diff
                                    sLastTouchUpElapsedMs = SystemClock.elapsedRealtime()
                                    sTouchDownTime = 0L
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                sTouchDownTime = 0L
                                sLastTouchDurationMs = 0L
                                sLastTouchUpElapsedMs = 0L
                            }
                        }
                    }
                }
            }
            return try {
                method.invoke(original, *(args ?: emptyArray()))
            } catch (ite: InvocationTargetException) {
                throw ite.targetException ?: ite
            }
        }
    }

    private fun attachTouchTracker(activity: Activity?) {
        try {
            if (activity == null || activity.window == null) return
            val window = activity.window
            if (WRAPPED_WINDOWS[window] == true) return
            val original = window.callback ?: return
            if (Proxy.isProxyClass(original.javaClass)) {
                val h = try { Proxy.getInvocationHandler(original) } catch (_: Throwable) { null }
                if (h is TouchTrackerHandler) {
                    WRAPPED_WINDOWS[window] = true
                    return
                }
            }

            val proxy = Proxy.newProxyInstance(
                Window.Callback::class.java.classLoader,
                arrayOf(Window.Callback::class.java),
                TouchTrackerHandler(original)
            ) as Window.Callback
            window.callback = proxy
            WRAPPED_WINDOWS[window] = true
        } catch (_: Throwable) {}
    }

    fun getAppContext(): Context? {
        val cached = sAppContext
        if (cached != null) return cached
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val ctx = activityThreadClass.getMethod("currentApplication").invoke(null) as? Context
            if (ctx != null) sAppContext = ctx
            ctx
        } catch (_: Throwable) {
            null
        }
    }

    fun getCurrentActivity(): Activity? {
        val cached = sResumedActivity?.get()
        if (cached != null && !cached.isFinishing && !cached.isDestroyed) {
            return cached
        }
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (activityRecord in activities.values) {
                if (activityRecord != null) {
                    val recordClass = activityRecord.javaClass
                    val pausedField = recordClass.getDeclaredField("paused")
                    pausedField.isAccessible = true
                    if (!pausedField.getBoolean(activityRecord)) {
                        val activityField = recordClass.getDeclaredField("activity")
                        activityField.isAccessible = true
                        val act = activityField.get(activityRecord) as? Activity
                        if (act != null && !act.isFinishing && !act.isDestroyed) {
                            sResumedActivity = WeakReference(act)
                            return act
                        }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun isNightMode(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun dpF(context: Context, value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )
    }
}
