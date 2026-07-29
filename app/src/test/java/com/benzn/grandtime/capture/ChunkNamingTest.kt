package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkNamingTest {

    private val sid = "9f8c1e2a4b6d47f0a1b2c3d4e5f60718" // 32 hex

    @Test fun sessionId_strips_hyphens_and_lowercases() {
        assertEquals(sid, ChunkNaming.sessionId("9F8C1E2A-4B6D-47F0-A1B2-C3D4E5F60718"))
    }

    @Test fun wireFileName_video_segment1_is_c0000() {
        assertEquals(
            "ben_ucpk_2026-07-29_11-01-06_sid${sid}_c0000.mp4",
            ChunkNaming.wireFileName("ben_ucpk_2026-07-29_11-01-06.mp4", sid, 1),
        )
    }

    @Test fun wireFileName_video_segment12_is_c0011() {
        assertEquals(
            "ben_ucpk_2026-07-29_11-01-06_sid${sid}_c0011.mp4",
            ChunkNaming.wireFileName("ben_ucpk_2026-07-29_11-01-06.mp4", sid, 12),
        )
    }

    @Test fun wireFileName_audio_keeps_wav_extension() {
        assertEquals(
            "ben_ucpk_2026-07-29_11-01-06_sid${sid}_c0000.wav",
            ChunkNaming.wireFileName("ben_ucpk_2026-07-29_11-01-06.wav", sid, 1),
        )
    }

    @Test fun wireFileName_strips_hyphens_from_legacy_uuid_session_id() {
        assertEquals(
            "a_sid${sid}_c0000.mp4",
            ChunkNaming.wireFileName("a.mp4", "9f8c1e2a-4b6d-47f0-a1b2-c3d4e5f60718", 1),
        )
    }

    @Test fun wireFileName_falls_back_when_segmentIndex_null() { // e.g. photos
        assertEquals(
            "img_2026-07-29_11-01-06.jpg",
            ChunkNaming.wireFileName("img_2026-07-29_11-01-06.jpg", sid, null),
        )
    }

    @Test fun wireFileName_falls_back_when_sessionId_not_32hex() {
        assertEquals("a.mp4", ChunkNaming.wireFileName("a.mp4", "not-hex", 1))
    }

    @Test fun wireFileName_collision_suffixed_name_still_tokenizes() {
        // MediaStorage appends _N on name collisions; token still lands after the stem.
        assertEquals(
            "ben_ucpk_2026-07-29_11-01-06_1_sid${sid}_c0000.mp4",
            ChunkNaming.wireFileName("ben_ucpk_2026-07-29_11-01-06_1.mp4", sid, 1),
        )
    }

    @Test fun wireFileName_no_extension_appends_token_and_no_dot() {
        assertEquals("stemonly_sid${sid}_c0000", ChunkNaming.wireFileName("stemonly", sid, 1))
    }
}
