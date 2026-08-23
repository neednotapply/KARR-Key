package com.karr.key.desktop.ui

/**
 * Desktop equivalents of the Android BuildConfig flags the ported screens read.
 * On Android the raw Command console and live trace are debug-only; the desktop
 * research host exposes them by design, so these are enabled here.
 */
object DesktopFlags {
    const val DEBUG: Boolean = true
}
