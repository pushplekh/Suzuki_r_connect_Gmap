package dev.mrwick.redline.util

import dev.mrwick.redline.BuildConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildIdentityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun fingerprintsActualBytesAndIncludesSourceRevision() {
        val file = folder.newFile()
        file.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BuildIdentity.installedApkSha256(file),
        )
        file.writeText("abcd")
        assertNotEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BuildIdentity.installedApkSha256(file),
        )
        assertTrue(BuildConfig.SOURCE_FINGERPRINT.matches(Regex("[0-9a-f]{64}")))
        assertTrue(BuildIdentity.summary.contains(BuildConfig.SOURCE_FINGERPRINT))
    }
}
