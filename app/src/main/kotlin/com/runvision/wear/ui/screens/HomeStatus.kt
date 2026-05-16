package com.runvision.wear.ui.screens

/**
 * Which override message (if any) the HomeScreen status line should show for
 * cycling. Pure, Android/Compose-free, independently testable.
 *
 * Priority: capability (unsupported device) > runtime start failure >
 * none (fall back to the existing connectionState mapping).
 */
enum class HomeOverride { UNSUPPORTED, START_FAILED }

fun cyclingHomeOverride(cyclingSupported: Boolean, cyclingStartFailed: Boolean): HomeOverride? =
    when {
        !cyclingSupported -> HomeOverride.UNSUPPORTED
        cyclingStartFailed -> HomeOverride.START_FAILED
        else -> null
    }
