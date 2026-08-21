package why.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** Plain JUnit 4 — no platform fixture, because `WhyRoot.kt` uses no platform types. */
class WhyRootTest {

    private lateinit var tmp: Path

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("whyroot")
    }

    @After
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun dir(relative: String): Path = Files.createDirectories(tmp.resolve(relative))

    private fun file(relative: String, text: String = ""): Path =
        Files.createDirectories(tmp.resolve(relative).parent)
            .let { Files.write(tmp.resolve(relative), text.toByteArray()) }

    /** R8.1: two worktrees as separate content roots, one nested inside the other. */
    @Test
    fun nestedRootsResolveIndependently() {
        dir("outer/.why/tasks")
        dir("outer/inner/.why/tasks")
        val outerFile = file("outer/Assets/A.cs", "class A {}")
        val innerFile = file("outer/inner/Assets/B.cs", "class B {}")

        assertEquals(tmp.resolve("outer"), findWhyRoot(outerFile))
        // The deeper file must NOT resolve to the shallower root.
        assertEquals(tmp.resolve("outer/inner"), findWhyRoot(innerFile))
    }

    /** A directory argument resolves to itself when it holds `.why/`. */
    @Test
    fun directoryArgumentResolvesToItself() {
        dir("proj/.why/tasks")
        assertEquals(tmp.resolve("proj"), findWhyRoot(tmp.resolve("proj")))
    }

    /** R8.2: `.git` is a file in a linked worktree. Resolution is unaffected. */
    @Test
    fun gitAsAFileDoesNotBreakResolution() {
        dir("wt/.why/tasks")
        file("wt/.git", "gitdir: /somewhere/.git/worktrees/wt\n")
        val target = file("wt/src/C.cs")
        assertEquals(tmp.resolve("wt"), findWhyRoot(target))
    }

    /** No `.git` at all — not a repository, or a plain export. Still resolves. */
    @Test
    fun gitAbsentEntirelyDoesNotBreakResolution() {
        dir("plain/.why/tasks")
        val target = file("plain/src/D.cs")
        assertEquals(tmp.resolve("plain"), findWhyRoot(target))
    }

    /** No `.why/` up to the filesystem root: null, no exception, no endless walk. */
    @Test
    fun noWhyAnywhereReturnsNull() {
        val target = file("nothing/here/E.cs")
        assertNull(findWhyRoot(target))
    }

    /** A path that does not exist on disk is treated as a file and still walks up. */
    @Test
    fun nonExistentPathStillWalksUp() {
        dir("proj/.why")
        assertEquals(tmp.resolve("proj"), findWhyRoot(tmp.resolve("proj/src/Gone.cs")))
    }

    /** §5.3: the `file` key is forward-slashed on every platform. */
    @Test
    fun projectRelativePathUsesForwardSlashes() {
        val root = dir("proj")
        val target = file("proj/Assets/Scripts/PlayerController.cs")
        assertEquals("Assets/Scripts/PlayerController.cs", projectRelativePath(root, target))
        // Constructed from platform separators, so this fails on Windows if the
        // conversion ever returns Path.toString() directly.
        assertEquals(
            "Assets/Scripts/PlayerController.cs",
            projectRelativePath(root, root.resolve("Assets").resolve("Scripts").resolve("PlayerController.cs")),
        )
    }

    @Test
    fun projectRelativePathRejectsPathsOutsideTheRoot() {
        val root = dir("proj")
        assertNull(projectRelativePath(root, file("elsewhere/F.cs")))
        assertNull(projectRelativePath(root, root))
    }
}
