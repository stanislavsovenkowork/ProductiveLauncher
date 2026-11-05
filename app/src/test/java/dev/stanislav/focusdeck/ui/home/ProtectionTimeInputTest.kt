package dev.stanislav.focusdeck.ui.home

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtectionTimeInputTest {
    @Test fun acceptsWhitespaceAroundValidTime() {
        assertEquals(LocalTime.of(17, 15), parseProtectionTime(" 17:15 "))
    }

    @Test fun rejectsInvalidTime() {
        assertThrows(java.time.format.DateTimeParseException::class.java) { parseProtectionTime("17:99 ") }
    }
}
