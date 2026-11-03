/*
 * secondfactor.ai for Android, version 0.1.0. MIT licensed.
 * https://github.com/secondfactor/secondfactor-kotlin
 *
 * Copy this one file into your app's source tree. It has no dependencies
 * beyond the Android framework and the Kotlin standard library.
 */
package ai.secondfactor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import java.net.URLDecoder

/**
 * The client half of secondfactor.ai hosted verification, for Android.
 *
 * secondfactor.ai verifies a phone number on its own hosted page, much like
 * Stripe Checkout. Your server creates a verification session with its API key
 * (the `secondfactor` package on npm or PyPI) and gets back the page's `url`.
 * This object does the two things left for the app:
 *
 * ```
 * SecondFactor.openVerification(activity, url)          // send the user to our page
 *
 * // Later, when your return URL (an App Link) opens your activity:
 * val back = SecondFactor.takeVerificationReturn(intent) // sessionId, returnToken
 * // Send back.returnToken to your server, which passes it to verifySession.
 * ```
 *
 * It never calls the secondfactor.ai API and never holds a key, a phone number
 * or a code. Nothing it reports is proof that a number was verified: only your
 * server's `verifySession` call is.
 */
object SecondFactor {
    /** This file's version. */
    const val VERSION = "0.1.0"

    private const val SESSION_PARAM = "sf_session_id"
    private const val TOKEN_PARAM = "sf_return_token"

    // The Custom Tabs protocol is a plain intent extra: a browser that supports
    // Custom Tabs opens the URL in one when this key is present, even with a
    // null value, and any other browser ignores it. Using the key directly
    // avoids a dependency on androidx.browser for a single constant.
    private const val CUSTOM_TABS_SESSION_EXTRA = "android.support.customtabs.extra.SESSION"

    // Plain http:// is accepted only for these hosts, so a local verify page
    // can be used in development. `*.localhost` always resolves to the device
    // itself.
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

    private val VISIBLE_ASCII = Regex("^[\\x21-\\x7e]+$")
    private val PAGE_URL = Regex(
        "^([a-z][a-z0-9+.-]*)://([^/?#\\\\@]+)[^#\\\\]*#vst_[A-Za-z0-9_-]+$",
        RegexOption.IGNORE_CASE,
    )
    private val AUTHORITY = Regex(
        "^(\\[[0-9a-f:.]+]|[a-z0-9._-]+)(?::[0-9]{1,5})?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Opens the hosted verification page in a Custom Tab, or in the default
     * browser when no installed browser supports Custom Tabs.
     *
     * `url` must be the `url` your server received from `createSession`. It is
     * checked first, so that a URL which is not a verification page, such as a
     * `javascript:` or `intent:` URL that reached the app some other way, is
     * refused rather than opened.
     *
     * When the user finishes, the hosted page sends them to your return URL,
     * which must be an App Link handled by one of your activities. Give that
     * activity `android:launchMode="singleTask"`, so that the return closes the
     * Custom Tab and arrives in `onNewIntent` of the activity that is already
     * running, instead of starting a second copy of it on top of the tab.
     *
     * @param context An activity, preferably. Any other context starts the page
     *   in a new task, which means Back will not return to your app.
     * @param url The `url` from your server's `createSession` call.
     * @throws IllegalArgumentException when `url` is not a verification page.
     * @throws android.content.ActivityNotFoundException when the device has no
     *   browser at all.
     */
    @JvmStatic
    fun openVerification(context: Context, url: String) {
        require(isVerificationPageUrl(url)) {
            "url is not a secondfactor.ai verification page. Pass the url your server got from createSession."
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .putExtras(Bundle().apply { putBinder(CUSTOM_TABS_SESSION_EXTRA, null as IBinder?) })
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Reads the hosted page's parameters from the intent that opened your
     * return activity, and removes the URL from the intent so it is not read
     * again later in the activity's life.
     *
     * Call it from `onCreate` when `savedInstanceState` is null, and from
     * `onNewIntent`. Returns null when the intent did not come from the hosted
     * page, for example when the app was opened from the launcher.
     *
     * @param intent The intent your activity was started or re-delivered with.
     * @return The session ID and return token, or null when the intent carries
     *   neither. `returnToken` is null when the user did not verify.
     */
    @JvmStatic
    fun takeVerificationReturn(intent: Intent?): VerificationReturn? {
        val found = parseVerificationReturn(intent?.dataString) ?: return null
        intent?.data = null
        return found
    }

    /**
     * Reads `sf_session_id` and `sf_return_token` from any URL string, without
     * touching an intent. Nothing here is checked against secondfactor.ai:
     * your server does that when it confirms the session.
     *
     * @param url Any URL, such as the one your App Link was opened with. Null
     *   is accepted and returns null.
     * @return The session ID and return token, or null when the URL carries no
     *   session ID. `returnToken` is null when the user canceled, ran out of
     *   attempts or the session expired.
     */
    @JvmStatic
    fun parseVerificationReturn(url: String?): VerificationReturn? {
        if (url == null) return null
        val query = url.substringBefore('#')
        val start = query.indexOf('?')
        if (start == -1) return null
        val params = HashMap<String, String>()
        for (pair in query.substring(start + 1).split('&')) {
            val separator = pair.indexOf('=')
            val key = decode(if (separator == -1) pair else pair.substring(0, separator)) ?: continue
            // The first value wins. The hosted page replaces any parameter of
            // the same name rather than adding a second, so a repeat did not
            // come from it.
            if (key !in params) params[key] = decode(if (separator == -1) "" else pair.substring(separator + 1)) ?: ""
        }
        val sessionId = params[SESSION_PARAM]
        if (sessionId.isNullOrEmpty()) return null
        return VerificationReturn(sessionId, params[TOKEN_PARAM]?.ifEmpty { null })
    }

    /**
     * Whether `url` has the shape of a hosted verification page: `https://`
     * (or plain `http://` on a loopback host), no user name or password, and
     * the session's client token in the fragment, where the page reads it.
     *
     * It is matched by hand rather than with `Uri`, which accepts almost any
     * string and would let schemes such as `intent:` through.
     */
    @JvmStatic
    fun isVerificationPageUrl(url: String): Boolean {
        if (!VISIBLE_ASCII.matches(url)) return false
        val parts = PAGE_URL.matchEntire(url) ?: return false
        val authority = AUTHORITY.matchEntire(parts.groupValues[2]) ?: return false
        val scheme = parts.groupValues[1].lowercase()
        val host = authority.groupValues[1].lowercase()
        return scheme == "https" || (scheme == "http" && (host in LOOPBACK_HOSTS || host.endsWith(".localhost")))
    }

    /** A query-string component decoded, or null when it is malformed. */
    private fun decode(value: String): String? =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        }
}

/**
 * The two parameters the hosted page adds to your return URL.
 *
 * @property sessionId `sf_session_id`. Tells you which stored session the user
 *   came back from. Anyone can edit a URL, so your server must always confirm
 *   the session ID it stored, never this one.
 * @property returnToken `sf_return_token`. Present only when the user verified;
 *   null when they canceled, ran out of attempts or the session expired. Send
 *   it to your server, which passes it to `verifySession`. It works once.
 */
class VerificationReturn(val sessionId: String, val returnToken: String?) {
    override fun equals(other: Any?): Boolean =
        other is VerificationReturn && other.sessionId == sessionId && other.returnToken == returnToken

    override fun hashCode(): Int = 31 * sessionId.hashCode() + (returnToken?.hashCode() ?: 0)

    // The return token is left out so that logging this object, or showing it
    // in a crash report, never leaks a token that can still be redeemed.
    override fun toString(): String =
        "VerificationReturn(sessionId=$sessionId, returnToken=${if (returnToken == null) "null" else "[redacted]"})"
}
