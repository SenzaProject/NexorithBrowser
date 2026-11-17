package com.nexorith.browser.browser

enum class SiteFeature { CAMERA, MICROPHONE, LOCATION }

/** A website asking for a sensitive capability. Must be resolved exactly once. */
class SitePermissionRequest(
    val origin: String,
    val features: Set<SiteFeature>,
    private val onResult: (granted: Set<SiteFeature>) -> Unit,
) {
    private var resolved = false

    fun resolve(granted: Set<SiteFeature>) {
        if (resolved) return
        resolved = true
        onResult(granted)
    }
}
