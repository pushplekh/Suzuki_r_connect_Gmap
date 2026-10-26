package dev.mrwick.redline.protocol

import dev.mrwick.redline.nav.ManeuverMap

/** Navigation branches verified in Ride Connect 7.6; these are not checksum profiles. */
enum class SuzukiModel(val label: String, val oemName: String?) {
    AUTO("Automatic detection", null),
    DEFAULT("Gixxer / other Suzuki (default mapping)", null),
    ACCESS("Access", "Access"),
    BURGMAN("Burgman Street (non-TFT)", "Burgman Street"),
    E_ACCESS("e-ACCESS", "e-ACCESS"),
    ACCESS_TFT("Access-TFT Edition", "Access-TFT Edition"),
    BURGMAN_TFT("Burgman Street-TFT Edition", "Burgman Street-TFT Edition"),
}

data class SuzukiProfile(val selection: SuzukiModel, val clusterName: String?) {
    val detectedModel: SuzukiModel?
        get() = when {
            clusterName == null || clusterName.length < 5 -> null
            clusterName.substring(1, 5) in setOf("AS01", "AS02") -> SuzukiModel.ACCESS
            clusterName.substring(1, 5) in setOf("AS11", "AS12") -> SuzukiModel.BURGMAN
            else -> null
        }

    val effectiveModel: SuzukiModel?
        get() = selection.takeUnless { it == SuzukiModel.AUTO } ?: detectedModel

    /** Access 125 capacity supplied by the rider; other variants are not inferred. */
    val fuelCapacityLitres: Double?
        get() = if (effectiveModel == SuzukiModel.ACCESS) 5.3 else null

    val description: String
        get() = when {
            selection != SuzukiModel.AUTO -> "Manual: ${selection.label}"
            effectiveModel != null -> "Detected: ${effectiveModel?.label}"
            clusterName?.contains("SBS51") == true -> "Detected SBS51 navigation branch; model unverified"
            else -> "Model unknown: select your Suzuki model. Only shared maneuver mappings are enabled."
        }

    fun maneuverByte(mapplsId: Int): Int? {
        // Only these two IDs depend on model/BTID in the verified OEM table.
        if (effectiveModel == null && clusterName?.contains("SBS51") != true &&
            mapplsId in setOf(58, 74)
        ) return null
        return ManeuverMap.mapplsIdToClusterByte(mapplsId, effectiveModel?.oemName, clusterName)
    }
}

data class PairedSuzuki(
    val mac: String? = null,
    val name: String? = null,
    val model: SuzukiModel = SuzukiModel.AUTO,
) {
    val profile: SuzukiProfile get() = SuzukiProfile(model, name)
}
