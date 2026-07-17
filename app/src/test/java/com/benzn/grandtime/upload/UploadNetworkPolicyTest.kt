package com.benzn.grandtime.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadNetworkPolicyTest {

    @Test fun `video requires unmetered when setting on`() {
        assertEquals(true, uploadRequiresUnmetered("video", videoUploadWifiOnly = true))
    }

    @Test fun `video does not require unmetered when setting off`() {
        assertEquals(false, uploadRequiresUnmetered("video", videoUploadWifiOnly = false))
    }

    @Test fun `audio never requires unmetered even when setting on`() {
        assertEquals(false, uploadRequiresUnmetered("audio", videoUploadWifiOnly = true))
    }

    @Test fun `photo never requires unmetered even when setting on`() {
        assertEquals(false, uploadRequiresUnmetered("photo", videoUploadWifiOnly = true))
    }
}
