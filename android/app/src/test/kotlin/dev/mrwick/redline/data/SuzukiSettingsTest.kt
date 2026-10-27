package dev.mrwick.redline.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.mrwick.redline.protocol.SuzukiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SuzukiSettingsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun selectionSurvivesDataStoreReopen() = runTest {
        val file = File(temporaryFolder.root, "profile.preferences_pb")
        withSettings(file) { settings ->
            settings.setPairedBike("first", "SAS21", SuzukiModel.ACCESS)
            settings.setSuzukiModel(SuzukiModel.ACCESS_TFT)
        }
        withSettings(file) { settings ->
            val restored = settings.pairedSuzuki.first()
            assertEquals("first", restored.mac)
            assertEquals("SAS21", restored.name)
            assertEquals(SuzukiModel.ACCESS_TFT, restored.model)
        }
    }

    @Test fun switchingOrForgettingBikeDoesNotLeakManualSelection() = runTest {
        withSettings(File(temporaryFolder.root, "profile.preferences_pb")) { settings ->
            settings.setPairedBike("first", "SAS21", SuzukiModel.ACCESS)
            settings.setPairedBike("second", "SAS11")
            assertEquals(SuzukiModel.AUTO, settings.pairedSuzuki.first().model)
            assertEquals(SuzukiModel.BURGMAN, settings.pairedSuzuki.first().profile.effectiveModel)

            settings.setSuzukiModel(SuzukiModel.BURGMAN_TFT)
            settings.setBikeMac(null)
            val cleared = settings.pairedSuzuki.first()
            assertNull(cleared.mac)
            assertNull(cleared.name)
            assertEquals(SuzukiModel.AUTO, cleared.model)
        }
    }

    @Test fun macOnlyReplacementClearsStaleNameAndModel() = runTest {
        withSettings(File(temporaryFolder.root, "profile.preferences_pb")) { settings ->
            settings.setPairedBike("first", "SAS21", SuzukiModel.ACCESS)
            settings.setBikeMac("second")
            val replacement = settings.pairedSuzuki.first()
            assertEquals("second", replacement.mac)
            assertNull(replacement.name)
            assertEquals(SuzukiModel.AUTO, replacement.model)
        }
    }

    @Test fun accessCapacityFollowsProfileAndSurvivesReopen() = runTest {
        val file = File(temporaryFolder.root, "capacity.preferences_pb")
        withSettings(file) { settings ->
            assertEquals(12.0, settings.fuelCapacityL.first(), 0.0)
            settings.setPairedBike("first", "SAS21", SuzukiModel.ACCESS)
            assertEquals(5.3, settings.fuelCapacityL.first(), 0.0)
        }
        withSettings(file) { settings ->
            assertEquals(5.3, settings.fuelCapacityL.first(), 0.0)
            settings.setPairedBike("second", "SBS01", SuzukiModel.DEFAULT)
            assertEquals(12.0, settings.fuelCapacityL.first(), 0.0)
            settings.setPairedBike("third", "SAS01")
            assertEquals(5.3, settings.fuelCapacityL.first(), 0.0)
        }
    }

    @Test fun explicitCapacityOverrideIsPreserved() = runTest {
        withSettings(File(temporaryFolder.root, "override.preferences_pb")) { settings ->
            settings.setFuelCapacityL(6.0)
            settings.setPairedBike("first", "SAS21", SuzukiModel.ACCESS)
            assertEquals(6.0, settings.fuelCapacityL.first(), 0.0)
        }
    }

    private suspend fun withSettings(file: File, block: suspend (Settings) -> Unit) {
        val job = SupervisorJob()
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        try {
            block(Settings(ds))
        } finally {
            job.cancelAndJoin()
        }
    }
}
