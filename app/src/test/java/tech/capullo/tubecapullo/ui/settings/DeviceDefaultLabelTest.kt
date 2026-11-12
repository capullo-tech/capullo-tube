package tech.capullo.tubecapullo.ui.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The Settings device-default label names the chart it shows, and why when it substitutes. */
class DeviceDefaultLabelTest {

    private val supported = setOf("JP", "KR", "NI", "US")
    private lateinit var locale: Locale

    @Before
    fun setUp() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US) // country names in English
    }

    @After
    fun tearDown() = Locale.setDefault(locale)

    @Test
    fun `a served country is named alone`() {
        assertEquals("Device default (United States)", deviceDefaultLabel("US", supported))
    }

    @Test
    fun `an unserved country names itself and the substitute`() {
        assertEquals(
            "Device default (Taiwan has no YouTube chart · Japan)",
            deviceDefaultLabel("TW", supported),
        )
    }

    @Test
    fun `no locale country names the fallback chart`() {
        assertEquals("Device default (Nicaragua)", deviceDefaultLabel("", supported))
    }
}
