package com.nexorith.browser.incognito

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

private const val UNSUPPORTED = "Private profiles are not supported by this WebView"

/** [PrivateProfiles] backed by the AndroidX WebKit multi-profile API. */
class WebKitPrivateProfiles : PrivateProfiles {

    override val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    override fun create(name: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(name)
        } else {
            throw UnsupportedOperationException(UNSUPPORTED)
        }
    }

    override fun delete(name: String): Boolean =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().deleteProfile(name)
                true
            } catch (e: IllegalStateException) {
                // A WebView still uses the profile.
                false
            }
        } else {
            true
        }

    override fun listNames(): List<String> =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().allProfileNames
        } else {
            emptyList()
        }
}

/**
 * Binds [webView] to the named profile. Must run before the WebView loads anything.
 * Never falls back to the default profile: if binding is impossible it throws.
 */
fun bindWebViewToProfile(webView: WebView, name: String) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
        WebViewCompat.setProfile(webView, name)
    } else {
        throw UnsupportedOperationException(UNSUPPORTED)
    }
}
