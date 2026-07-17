package com.benzn.grandtime.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class DeletePromptTest {
    @Test fun `uploaded status warns it stays on the server`() {
        assertTrue(deleteConfirmMessage("uploaded").contains("stays on the server"))
    }

    @Test fun `pending status warns it will be lost`() {
        val msg = deleteConfirmMessage("pending")
        assertTrue(msg.contains("hasn't finished uploading"))
        assertTrue(msg.contains("will be lost"))
    }

    @Test fun `failed status warns it will be lost`() {
        val msg = deleteConfirmMessage("failed")
        assertTrue(msg.contains("hasn't finished uploading"))
        assertTrue(msg.contains("will be lost"))
    }

    @Test fun `uploading status warns it will be lost`() {
        val msg = deleteConfirmMessage("uploading")
        assertTrue(msg.contains("hasn't finished uploading"))
        assertTrue(msg.contains("will be lost"))
    }
}
