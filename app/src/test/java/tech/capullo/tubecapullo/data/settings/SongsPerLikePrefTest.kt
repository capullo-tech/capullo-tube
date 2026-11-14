package tech.capullo.tubecapullo.data.settings

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class SongsPerLikePrefTest {
    private val stored = mutableMapOf<String, Int>()

    private fun settings(): AppSettings {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putInt(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
        every { prefs.getString(any(), any()) } answers { secondArg() }
        every { prefs.getBoolean(any(), any()) } answers { secondArg() }
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return AppSettings(context)
    }

    @Test
    fun `songs added per like is 2 until the listener picks a count`() {
        assertEquals(2, settings().songsPerLike.value)
    }

    @Test
    fun `an odd count stored by an earlier build rounds up to whole pairs`() {
        for ((old, now) in listOf(1 to 2, 3 to 4, 5 to 6)) {
            stored["mix_songs"] = old
            assertEquals("stored $old", now, settings().songsPerLike.value)
        }
    }

    @Test
    fun `a count is rounded up to a pair, clamped to 0 to 6, and survives a restart`() {
        val settings = settings()

        settings.setSongsPerLike(3)
        assertEquals(4, settings.songsPerLike.value)
        assertEquals(4, settings().songsPerLike.value)

        settings.setSongsPerLike(AppSettings.MAX_SONGS_PER_LIKE + 1)
        assertEquals(6, settings.songsPerLike.value)
        assertEquals(6, settings().songsPerLike.value)

        settings.setSongsPerLike(-1)
        assertEquals(0, settings.songsPerLike.value)
        assertEquals(0, settings().songsPerLike.value)
    }
}
