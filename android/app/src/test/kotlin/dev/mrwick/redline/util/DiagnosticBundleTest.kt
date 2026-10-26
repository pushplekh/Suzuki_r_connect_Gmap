package dev.mrwick.redline.util

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticBundleTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun bundleIncludesCurrentAndRotatedLogsButNoUnrelatedFiles() {
        val dir = folder.newFolder("diag")
        val expected = listOf("app.log", "app.1.log", "frames.log", "frames.1.log")
        expected.forEach { File(dir, it).writeText("captured $it") }
        File(dir, "unrelated.txt").writeText("excluded")
        val output = DiagnosticBundle.create(dir, File(folder.root, "bundle.zip"), "Profile=Access")
        ZipFile(output).use { zip ->
            expected.forEach {
                assertEquals("captured $it", zip.getInputStream(zip.getEntry(it)).bufferedReader().readText())
            }
            val info = zip.getInputStream(zip.getEntry("capture-info.txt")).bufferedReader().readText()
            assertTrue(info.contains("Profile=Access"))
            assertTrue(info.contains("share privately"))
            assertEquals(5, zip.size())
            assertFalse(zip.entries().asSequence().any { it.name == "unrelated.txt" })
        }
    }

    @Test fun missingRotationsAreExplicitAndDoNotHideExistingFrames() {
        val dir = folder.newFolder("partial")
        File(dir, "frames.log").writeText("TX captured")
        val output = DiagnosticBundle.create(dir, File(folder.root, "partial.zip"), "test")
        ZipFile(output).use { zip ->
            assertEquals(2, zip.size())
            val info = zip.getInputStream(zip.getEntry("capture-info.txt")).bufferedReader().readText()
            assertTrue(info.contains("Missing files: [app.log, app.1.log, frames.1.log]"))
        }
    }

    @Test(expected = IOException::class)
    fun emptyDirectoryFailsExplicitly() {
        DiagnosticBundle.create(folder.newFolder("empty"), File(folder.root, "empty.zip"), "test")
    }

    @Test fun crashStacksAreIncludedEvenWithoutNavigationLogsAndAreBounded() {
        val dir = folder.newFolder("crash-only-diag")
        val crashes = folder.newFolder("crash-logs")
        for (i in 1..7) {
            File(crashes, "crash-$i.txt").apply {
                writeText("Build: code=$i\nIllegalStateException: upgrade failure $i")
                assertTrue(setLastModified(i * 1_000L))
            }
        }
        File(crashes, "unrelated.txt").writeText("excluded")
        File(crashes, "crash-invalid.png").writeText("excluded")
        val output = DiagnosticBundle.create(dir, File(folder.root, "crashes.zip"), "code=8", crashes)
        ZipFile(output).use { zip ->
            assertEquals(6, zip.size())
            val info = zip.getInputStream(zip.getEntry("capture-info.txt")).bufferedReader().readText()
            assertTrue(info.contains("Crash logs included: 5"))
            assertTrue(info.contains("clearing app data deletes saved stacks"))
            for (i in 3..7) {
                val entry = zip.getEntry("crash-logs/crash-$i.txt")
                assertTrue(zip.getInputStream(entry).bufferedReader().readText().contains("upgrade failure $i"))
            }
            assertFalse(zip.entries().asSequence().any { it.name.endsWith("crash-1.txt") || it.name.endsWith("unrelated.txt") })
        }
    }

    @Test fun regularLogsAndCrashStacksAreExportedTogether() {
        val dir = folder.newFolder("logs")
        val crashes = folder.newFolder("stacks")
        File(dir, "app.log").writeText("app records")
        File(crashes, "crash-latest.txt").writeText("fatal stack")
        val output = DiagnosticBundle.create(dir, File(folder.root, "combined.zip"), "test", crashes)
        ZipFile(output).use { zip ->
            assertEquals(3, zip.size())
            assertEquals("app records", zip.getInputStream(zip.getEntry("app.log")).bufferedReader().readText())
            assertEquals("fatal stack", zip.getInputStream(zip.getEntry("crash-logs/crash-latest.txt")).bufferedReader().readText())
        }
    }
}
