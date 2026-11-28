package host.vorv.selfupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a self-change must obey, pinned without a network or a device.
 *
 * These are new rules, so there is no old code for them to fail against: before
 * this file, the source/mirror pairing and the "is this really Vorv" check did
 * not exist anywhere.
 */
class SelfUpdateTest {

    // -------------------------------------------------- source ⇄ mirror pairing

    @Test
    fun `the mirror path is the shipped copy of the same file`() {
        val source = "app/src/main/java/host/vorv/tools/NotifyTool.kt"
        assertEquals("app/src/main/assets/source/host/vorv/tools/NotifyTool.kt", SelfUpdate.mirrorPathFor(source))
    }

    @Test
    fun `a nested package keeps its shape`() {
        val source = "app/src/main/java/host/vorv/llm/provider/ProviderChainLlm.kt"
        assertEquals(
            "app/src/main/assets/source/host/vorv/llm/provider/ProviderChainLlm.kt",
            SelfUpdate.mirrorPathFor(source),
        )
    }

    @Test
    fun `files outside Vorv's source are refused`() {
        assertNull(SelfUpdate.mirrorPathFor("app/build.gradle.kts"))
        assertNull(SelfUpdate.mirrorPathFor("build.gradle.kts"))
        assertNull(SelfUpdate.mirrorPathFor("app/src/main/AndroidManifest.xml"))
        assertNull(SelfUpdate.mirrorPathFor("app/src/main/assets/source/host/vorv/AgentViewModel.kt"))
        assertNull(SelfUpdate.mirrorPathFor(".github/workflows/build.yml"))
        assertNull(SelfUpdate.mirrorPathFor(""))
    }

    @Test
    fun `a traversal or a java file is refused`() {
        assertNull(SelfUpdate.mirrorPathFor("app/src/main/java/../../secrets.kt"))
        assertNull(SelfUpdate.mirrorPathFor("app/src/main/java/host/vorv/Main.java"))
        assertNull(SelfUpdate.mirrorPathFor("app/src/main/java//host/vorv/X.kt"))
        assertNull(SelfUpdate.mirrorPathFor("app/src/main/java\\host\\vorv\\X.kt"))
    }

    @Test
    fun `a file directly under the source root has no package and is refused`() {
        assertNull(SelfUpdate.mirrorPathFor("app/src/main/java/X.kt"))
    }

    // -------------------------------------------------- build identification

    private fun release(title: String, notes: String, assets: List<GitHubRepo.Asset> = emptyList()) =
        GitHubRepo.Release(tag = "ci-latest", title = title, notes = notes, assets = assets)

    @Test
    fun `the commit is read from the workflow's notes`() {
        val r = release(
            title = "Vorv CI build 1a2b3c4 (debug + unsigned release)",
            notes = "Automated CI output of commit 1a2b3c4 — NOT a signed release.",
        )
        assertEquals("1a2b3c4", r.commitSha())
    }

    @Test
    fun `the commit is read from the title when the notes do not name one`() {
        val r = release("Vorv CI build deadbee (debug + unsigned release)", notes = "")
        assertEquals("deadbee", r.commitSha())
    }

    @Test
    fun `a build that names no commit matches nothing`() {
        val r = release("Vorv CI build", notes = "no sha here")
        assertNull(r.commitSha())
        assertFalse(SelfUpdate.buildMatches(r, "1a2b3c4"))
    }

    @Test
    fun `a short pushed id matches the build the workflow named`() {
        val r = release("Vorv CI build 1a2b3c4", notes = "commit 1a2b3c4d5e6")
        assertTrue(SelfUpdate.buildMatches(r, "1a2b3c4"))
        assertTrue(SelfUpdate.buildMatches(r, "1a2b3c4d5e6"))
        assertFalse(SelfUpdate.buildMatches(r, "9f8e7d6"))
    }

    @Test
    fun `an empty commit never matches, and neither does no release`() {
        val r = release("Vorv CI build 1a2b3c4", notes = "commit 1a2b3c4")
        assertFalse(SelfUpdate.buildMatches(r, "   "))
        assertFalse(SelfUpdate.buildMatches(null, "1a2b3c4"))
    }

    // -------------------------------------------------- which file gets installed

    @Test
    fun `the debug APK is chosen and the unsigned release never is`() {
        val assets = listOf(
            GitHubRepo.Asset("app-release-unsigned.apk", "https://example/a", 100),
            GitHubRepo.Asset(SelfUpdate.APK_ASSET, "https://example/b", 200),
        )
        val chosen = SelfUpdate.apkAsset(release("b", "commit abc1234", assets))
        assertNotNull(chosen)
        assertEquals(SelfUpdate.APK_ASSET, chosen!!.name)
        assertEquals(200L, chosen.size)
    }

    @Test
    fun `a release with only the unsigned file yields nothing to install`() {
        val assets = listOf(GitHubRepo.Asset("app-release-unsigned.apk", "https://example/a", 100))
        assertNull(SelfUpdate.apkAsset(release("b", "commit abc1234", assets)))
    }

    // ------------------------------------------- shipped copy ⇄ GitHub main

    @Test
    fun `the same file in both places raises no note`() {
        assertNull(SelfUpdate.driftNote("class A\n", "class A\n"))
    }

    @Test
    fun `windows line endings are not a code difference`() {
        assertNull(SelfUpdate.driftNote("class A\r\nclass B\r\n", "class A\nclass B\n"))
    }

    @Test
    fun `a different copy says which one the phone runs`() {
        val note = SelfUpdate.driftNote("class A\n", "class B\n")
        assertNotNull(note)
        assertTrue(note!!.contains("DIFFER"))
        assertTrue(note.contains("RUNS the shipped copy"))
    }

    @Test
    fun `a file only on GitHub is not passed off as the shipped one`() {
        val note = SelfUpdate.driftNote(null, "class New\n")
        assertNotNull(note)
        assertTrue(note!!.contains("read from GitHub main"))
    }

    @Test
    fun `a file deleted on GitHub is reported, not silently trusted`() {
        val note = SelfUpdate.driftNote("class A\n", null)
        assertNotNull(note)
        assertTrue(note!!.contains("no copy of this file on main"))
    }

    @Test
    fun `nothing was compared means nothing is claimed`() {
        assertNull(SelfUpdate.driftNote(null, null))
    }
}
