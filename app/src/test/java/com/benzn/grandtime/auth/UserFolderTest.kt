package com.benzn.grandtime.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class UserFolderTest {
    @Test fun `sanitize keeps alnum collapses others lowercases`() {
        assertEquals("jane_doe", UserFolder.sanitize("Jane Doe"))
        assertEquals("a_b_com", UserFolder.sanitize("a@b.com"))
        assertEquals("john", UserFolder.sanitize("  John!!  "))
        assertEquals("user", UserFolder.sanitize("@@@"))
    }

    @Test fun `derive uses name then email then user, appends 8 sub chars`() {
        assertEquals(
            MediaScope("jane_doe_abc12345", "jane_doe"),
            UserFolder.derive("Jane Doe", "j@x.com", "abc12345-6789-xxxx"),
        )
        assertEquals(
            MediaScope("j_x_com_sub99999", "j_x_com"),
            UserFolder.derive(null, "j@x.com", "sub99999-0000"),
        )
        assertEquals(
            MediaScope("user_deadbeef", "user"),
            UserFolder.derive(null, null, "deadbeef-1111"),
        )
    }
}
