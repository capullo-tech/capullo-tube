package tech.capullo.tubecapullo.data.settings

import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.capullo.tubecapullo.data.similar.SimilarDepth
import java.io.File

class AppSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, "app_settings.preferences_pb")

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    /**
     * One process lifetime: settings over [file], with the app's corruption handler and, when
     * given, the copy from an old SharedPreferences file. [block] gets the store too, to wait
     * for a queued write to reach the file before the process ends.
     */
    private fun <T> process(
        oldPrefs: SharedPreferences? = null,
        block: suspend (AppSettings, DataStore<Preferences>) -> T,
    ): T = runBlocking {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = replaceCorruptStore("app_settings"),
            migrations = listOfNotNull(oldPrefs?.let { prefs -> SharedPreferencesMigration({ prefs }) }),
            scope = scope,
        ) { file }
        try {
            block(AppSettings(store, scope), store)
        } finally {
            // DataStore allows one instance per file; the file is free once its scope has ended.
            job.cancelAndJoin()
        }
    }

    private suspend fun DataStore<Preferences>.awaitSaved(done: (Preferences) -> Boolean) {
        withTimeout(5_000) { data.first(done) }
    }

    /** A SharedPreferences file as an older build left it; the migration empties it. */
    private fun sharedPreferences(values: Map<String, Any>): SharedPreferences {
        val left = values.toMutableMap()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.clear() } answers {
            left.clear()
            editor
        }
        every { editor.commit() } returns true
        val prefs = mockk<SharedPreferences>()
        every { prefs.all } answers { left.toMap() }
        every { prefs.contains(any()) } answers { firstArg<String>() in left }
        every { prefs.edit() } returns editor
        return prefs
    }

    @Test
    fun `every value of the old SharedPreferences file is read after the move`() {
        val old = sharedPreferences(
            mapOf(
                "chart_regions" to "JP,BR",
                "more_like_rows" to 3,
                "continue_cards" to 2,
                "full_albums" to false,
                "radio_like_mode" to "SWITCH_NOW",
                "mix_songs" to 4,
                "ask_for_shared_links" to true,
                "lyrics_card" to true,
                "lyrics_online" to false,
                "lyrics_delay_ms" to -300,
                "history_retention" to "ONE_YEAR",
                "similar_online" to false,
                "similar_depth" to "DEEP",
                "lastfm_api_key" to "user-key",
            ),
        )

        process(old) { _, _ -> }
        // The second process has no migration: the values must come from the DataStore file.
        process { settings, _ ->
            assertEquals(listOf("JP", "BR"), settings.chartRegions.value)
            assertEquals(3, settings.moreLikeRows.value)
            assertEquals(2, settings.continueCards.value)
            assertFalse(settings.fullAlbums.value)
            assertEquals(LikeMode.SWITCH_NOW, settings.likeMode.value)
            assertEquals(4, settings.songsPerLike.value)
            assertTrue(settings.askForSharedLinks.value)
            assertTrue(settings.lyricsCard.value)
            assertFalse(settings.lyricsOnline.value)
            assertEquals(-300, settings.lyricsDelayMs.value)
            assertEquals(HistoryRetention.ONE_YEAR, settings.historyRetention.value)
            assertFalse(settings.similarOnline.value)
            assertEquals(SimilarDepth.DEEP, settings.similarDepth.value)
            assertEquals("user-key", settings.lastfmApiKey.value)
            assertFalse(settings.resetNotice.value)
        }
        assertTrue(old.all.isEmpty())
    }

    @Test
    fun `the single chart region of the oldest builds becomes the list`() {
        process(sharedPreferences(mapOf("chart_region" to "DE"))) { settings, store ->
            assertEquals(listOf("DE"), settings.chartRegions.value)
            store.awaitSaved { it.asMap().keys.map { key -> key.name } == listOf("chart_regions") }
        }
        assertEquals(listOf("DE"), process { settings, _ -> settings.chartRegions.value })
    }

    @Test
    fun `songs added per like is 2 until the listener picks a count`() {
        assertEquals(2, process { settings, _ -> settings.songsPerLike.value })
    }

    @Test
    fun `an odd count stored by an earlier build rounds up to whole pairs`() {
        for ((old, now) in listOf(1 to 2, 3 to 4, 5 to 6)) {
            process { _, store -> store.edit { it[intPreferencesKey("mix_songs")] = old } }
            assertEquals("stored $old", now, process { settings, _ -> settings.songsPerLike.value })
        }
    }

    @Test
    fun `a count is rounded up to a pair, clamped to 0 to 6, and survives a restart`() {
        for ((asked, kept) in listOf(3 to 4, AppSettings.MAX_SONGS_PER_LIKE + 1 to 6, -1 to 0)) {
            process { settings, store ->
                settings.setSongsPerLike(asked)
                assertEquals(kept, settings.songsPerLike.value)
                store.awaitSaved { it[intPreferencesKey("mix_songs")] == kept }
            }
            assertEquals("asked $asked", kept, process { settings, _ -> settings.songsPerLike.value })
        }
    }

    @Test
    fun `quick writes reach the file in the order they were made`() {
        process { settings, store ->
            for (step in -20..20) settings.setLyricsDelayMs(step * AppSettings.LYRICS_DELAY_STEP_MS)
            settings.setFullAlbums(false)
            store.awaitSaved { it.asMap().keys.any { key -> key.name == "full_albums" } }
        }
        assertEquals(2_000, process { settings, _ -> settings.lyricsDelayMs.value })
    }

    @Test
    fun `a corrupt file is replaced and the notice stays until it is acknowledged`() {
        file.writeBytes(ByteArray(32) { 0xFF.toByte() })

        process { settings, store ->
            assertTrue(settings.resetNotice.value)
            assertEquals(AppSettings.DEFAULT_MORE_LIKE_ROWS, settings.moreLikeRows.value)
            // Without the replacement this write fails with the CorruptionException of the read.
            settings.setMoreLikeRows(4)
            store.awaitSaved { it[intPreferencesKey("more_like_rows")] == 4 }
        }
        // Not acknowledged: a process that ended before a screen showed the notice still owes it.
        process { settings, store ->
            assertTrue(settings.resetNotice.value)
            assertEquals(4, settings.moreLikeRows.value)
            settings.acknowledgeReset()
            assertFalse(settings.resetNotice.value)
            store.awaitSaved { it[RESET_NOTICE] == null }
        }
        assertFalse(process { settings, _ -> settings.resetNotice.value })
    }

    @Test
    fun `a file that cannot be read gives the defaults and is left as it is`() {
        // A directory in the file's place fails to open with an IOException that is not corruption.
        file.mkdir()

        process { settings, _ ->
            assertEquals(AppSettings.DEFAULT_MORE_LIKE_ROWS, settings.moreLikeRows.value)
            assertFalse(settings.resetNotice.value)
        }
        assertTrue(file.isDirectory)
    }

    @Test
    fun `a failure that is not an IOException is not turned into the defaults`() {
        val defect = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("defect") }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = error("not used")
        }

        assertThrows(IllegalStateException::class.java) {
            AppSettings(defect, CoroutineScope(Dispatchers.Unconfined))
        }
    }
}
