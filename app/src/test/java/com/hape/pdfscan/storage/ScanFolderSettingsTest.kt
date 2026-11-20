package com.hape.pdfscan.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ScanFolderSettingsTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun settings(): ScanFolderSettings {
        val file = tempFolder.newFile("settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(testDispatcher),
            produceFile = { file },
        )
        return ScanFolderSettings(dataStore = dataStore, defaultFolder = { tempFolder.root })
    }

    @Test
    fun candidateOverlay_isOffByDefault() = runTest(testDispatcher) {
        assertFalse(settings().showCandidateOverlay.first())
    }

    @Test
    fun candidateOverlay_canBeSwitchedOnAndOff() = runTest(testDispatcher) {
        val s = settings()
        s.setShowCandidateOverlay(true)
        assertTrue(s.showCandidateOverlay.first())
        s.setShowCandidateOverlay(false)
        assertFalse(s.showCandidateOverlay.first())
    }
}
