package why

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Proves the headless platform fixture harness starts. Asserts nothing about the plugin,
 * because there is nothing yet. Every later task's tier-2 tests depend on this passing.
 */
class FixtureHarnessTest : BasePlatformTestCase() {
    fun testFixtureCreatesAFile() {
        val file = myFixture.configureByText("Placeholder.txt", "why\n")
        assertNotNull(file)
        assertEquals("Placeholder.txt", file.name)
    }
}
