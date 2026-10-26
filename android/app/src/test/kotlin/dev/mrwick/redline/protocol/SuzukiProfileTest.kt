package dev.mrwick.redline.protocol

import dev.mrwick.redline.nav.ManeuverMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuzukiProfileTest {
    @Test fun detectsOnlyVerifiedAccessAndBurgmanNameBranches() {
        for (prefix in listOf("SAS01", "SAS02")) {
            assertEquals(SuzukiModel.ACCESS, auto(prefix).effectiveModel)
            assertEquals(44, auto(prefix).maneuverByte(58))
            assertEquals(38, auto(prefix).maneuverByte(74))
        }
        for (prefix in listOf("SAS11", "SAS12")) {
            assertEquals(SuzukiModel.BURGMAN, auto(prefix).effectiveModel)
            assertEquals(46, auto(prefix).maneuverByte(58))
            assertEquals(44, auto(prefix).maneuverByte(74))
        }
    }

    @Test fun unknownNamesDoNotSilentlyChooseAccessOrGixxerMapping() {
        for (name in listOf(null, "", "S", "SAS21", "SBM11", "unrecognized")) {
            val profile = auto(name)
            assertNull(profile.effectiveModel)
            assertNull(profile.maneuverByte(58))
            assertNull(profile.maneuverByte(74))
            assertEquals(1, profile.maneuverByte(0))
            assertEquals(4, profile.maneuverByte(3))
            assertTrue(profile.description.contains("unknown"))
        }
    }

    @Test fun everyManualModelUsesTheFullExistingOemTranslationTable() {
        for (model in SuzukiModel.entries.filterNot { it == SuzukiModel.AUTO }) {
            val profile = SuzukiProfile(model, "SAS21")
            assertEquals(model, profile.effectiveModel)
            for (id in 0..75) {
                assertEquals(
                    "${model.name}: Mappls ID $id",
                    ManeuverMap.mapplsIdToClusterByte(id, model.oemName, "SAS21"),
                    profile.maneuverByte(id),
                )
            }
        }
    }

    @Test fun sbs51BtidSelectsSpecialBranchWithoutInventingAModel() {
        val profile = auto("SBS51")
        assertNull(profile.effectiveModel)
        assertEquals(44, profile.maneuverByte(58))
        assertEquals(38, profile.maneuverByte(74))
        assertTrue(profile.description.contains("SBS51"))
    }

    @Test fun sbs51BtidStillOverridesManualDefaultAsInOem() {
        val profile = SuzukiProfile(SuzukiModel.DEFAULT, "SBS51")
        assertEquals(44, profile.maneuverByte(58))
        assertEquals(38, profile.maneuverByte(74))
    }

    @Test fun manualOverrideAndSwitchingBackToAutoTakeEffect() {
        val original = auto("SAS01")
        val override = original.copy(selection = SuzukiModel.DEFAULT)
        assertEquals(46, override.maneuverByte(58))
        assertEquals(44, override.copy(selection = SuzukiModel.AUTO).maneuverByte(58))
    }

    @Test fun manualAccessDoesNotControlIndependentlyDetectedSas21Checksum() {
        val profile = SuzukiProfile(SuzukiModel.ACCESS, "SAS21")
        assertEquals(44, profile.maneuverByte(58))
        assertEquals(ClusterProtocol.LEGACY_ACCESS_BURGMAN, ClusterProtocol.fromClusterName(profile.clusterName))
        assertEquals(
            ClusterProtocol.fromClusterName(profile.clusterName),
            ClusterProtocol.fromClusterName(profile.copy(selection = SuzukiModel.DEFAULT).clusterName),
        )
    }

    @Test fun standardChecksumNameDoesNotEstablishNavigationModel() {
        assertEquals(ClusterProtocol.STANDARD, ClusterProtocol.fromClusterName("SBM11"))
        assertNull(auto("SBM11").effectiveModel)
    }

    @Test fun newPairedConfigurationDefaultsToAutomaticRatherThanPreviousBikeModel() {
        val previous = PairedSuzuki("previous", "SAS21", SuzukiModel.ACCESS)
        val next = PairedSuzuki("next", "SAS11")
        assertEquals(SuzukiModel.ACCESS, previous.profile.effectiveModel)
        assertEquals(SuzukiModel.AUTO, next.model)
        assertEquals(SuzukiModel.BURGMAN, next.profile.effectiveModel)
    }

    private fun auto(name: String?) = SuzukiProfile(SuzukiModel.AUTO, name)
}
