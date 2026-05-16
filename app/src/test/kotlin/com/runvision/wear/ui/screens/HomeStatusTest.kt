package com.runvision.wear.ui.screens

import org.junit.Assert.*
import org.junit.Test

class HomeStatusTest {

    @Test
    fun `unsupported takes top priority`() {
        // unsupported wins even if startFailed also true
        assertEquals(HomeOverride.UNSUPPORTED, cyclingHomeOverride(cyclingSupported = false, cyclingStartFailed = true))
        assertEquals(HomeOverride.UNSUPPORTED, cyclingHomeOverride(cyclingSupported = false, cyclingStartFailed = false))
    }

    @Test
    fun `start failed when supported`() {
        assertEquals(HomeOverride.START_FAILED, cyclingHomeOverride(cyclingSupported = true, cyclingStartFailed = true))
    }

    @Test
    fun `no override in the normal case`() {
        assertNull(cyclingHomeOverride(cyclingSupported = true, cyclingStartFailed = false))
    }
}
