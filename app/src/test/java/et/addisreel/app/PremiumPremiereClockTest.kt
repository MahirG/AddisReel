package et.addisreel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class PremiumPremiereClockTest {
    @Test fun validPremiereUsesUtcRegardlessOfDeviceTimezone() {
        val expected = 1830343200000L
        val original = TimeZone.getDefault()
        try {
            for (timezone in listOf("UTC","Africa/Addis_Ababa","America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(timezone))
                val actual = PremiereClock.parseUtc("2028-01-01T12:40:00Z")
                assertEquals(expected, actual)
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun corruptOrOffsetPremiereDatesAreRejected() {
        for (date in listOf(
            "2028-02-30T12:00:00Z",
            "2028-13-03T12:00:00Z",
            "2028-01-01T12:00Z",
            "2028-01-01T12:00:00+03:00",
            "not-a-date",
            "2028-01-01T12:99:00Z"
        )) assertNull(date + " should be invalid", PremiereClock.parseUtc(date))
    }

    @Test fun countdownClampsExpiredReleases() {
        assertEquals("00:00:00", PremiereClock.remaining(900L,1000L))
        assertEquals("00:00:00", PremiereClock.remaining(1000L,1000L))
    }

    @Test fun countdownUsesDaysHoursMinutesSecondsWithoutRollover() {
        val base = 1_000_000L
        assertEquals("00:00:01", PremiereClock.remaining(base+200L,base))
        assertEquals("01:01:01", PremiereClock.remaining(base+3_661_000L,base))
        assertEquals("1d  00:00:00", PremiereClock.remaining(base+86_400_000L,base))
        assertEquals("1d  02:03:04", PremiereClock.remaining(base+93_784_000L,base))
    }

    @Test fun premiereClockNeverShowsNegativeDurations() {
        assertTrue(PremiereClock.remaining(1L,Long.MAX_VALUE).startsWith("00"))
    }
}
