package dev.mrwick.redline.nav

/** Experimental reuse of the identity name, not a navigation-text protocol field. */
internal fun clusterInstructionText(parsed: ParsedNavData?): String? {
    if (parsed == null) return null
    val text = when (parsed.navigationState) {
        MapsNavigationState.STARTING -> "STARTING NAVIGATION"
        MapsNavigationState.REROUTING -> "REROUTING"
        MapsNavigationState.ARRIVED -> "DESTINATION ARRIVED"
        MapsNavigationState.NAVIGATING -> parsed.roundaboutExit?.let { "ROUNDABOUT EXIT $it" }
            ?: parsed.streetName ?: return null
    }
    return text.replace('\u00a0', ' ').replace('\u202f', ' ')
        .replace(Regex("""(?i)^\s*(?:in\s+)?\d+(?:[.,]\d+)?\s*(?:km|mi|ft|m)\b\s*[-·:]?\s*"""), "")
        .map { if (it.code in 32..126) it else ' ' }.joinToString("")
        .replace(Regex("""\s+"""), " ").trim().take(20).takeIf { it.isNotBlank() }
}
