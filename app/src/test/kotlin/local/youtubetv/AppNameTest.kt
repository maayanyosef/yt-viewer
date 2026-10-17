package local.youtubetv

import java.io.File
import local.youtubetv.apikey.KEY_PAGE_EMAIL_SUBJECT
import local.youtubetv.apikey.keyPageHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build ticket 67: the app is called "YT Viewer" everywhere a person sees its name. */
class AppNameTest {

    /** The old name, written in two halves so that a search of the source does not find it here. */
    private val oldName = "YouTube" + " TV"

    /**
     * The `app/src/main` folder. Gradle runs JVM tests with `app/` as the working directory.
     * An IDE may run them from the repository root instead, so both are tried.
     */
    private val mainFolder: File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("Cannot find app/src/main from ${File(".").absolutePath}")

    @Test
    fun `the name is YT Viewer`() {
        assertEquals("YT Viewer", APP_NAME)
    }

    @Test
    fun `the Google TV app list shows the same name as the app`() {
        val strings = File(mainFolder, "res/values/strings.xml").readText()
        val appName = Regex("""<string\s+name="app_name"[^>]*>([^<]*)</string>""").find(strings)
        assertEquals(APP_NAME, appName?.groupValues?.get(1))
    }

    @Test
    fun `the Key page and its email name the app`() {
        val html = keyPageHtml(result = null, postPath = "/abcd23efgh", address = null)
        assertTrue(html.contains("<title>YT Viewer: your API key</title>"))
        assertTrue(html.contains("<i>YT Viewer</i>"))
        assertEquals("YT Viewer: the page for your API key", KEY_PAGE_EMAIL_SUBJECT)
    }

    @Test
    fun `no file under app src main names the old name`() {
        val files = mainFolder.walkTopDown().filter { it.isFile }.toList()
        assertTrue("found no files under $mainFolder", files.isNotEmpty())
        val named = files.filter { it.readText().contains(oldName, ignoreCase = true) }
        assertEquals(emptyList<File>(), named)
    }
}
