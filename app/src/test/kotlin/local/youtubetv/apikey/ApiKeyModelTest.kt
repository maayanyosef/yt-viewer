package local.youtubetv.apikey

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build ticket 64: checking, saving and removing a key, without a device or a network. */
class ApiKeyModelTest {

    private val checked = mutableListOf<String>()
    private val logged = mutableListOf<String>()
    private val saved = mutableListOf<KeyCheckResult>()

    private fun model(store: ApiKeyStore, answer: KeyCheckResult) = ApiKeyModel(
        store = store,
        checker = { key -> checked += key; answer },
        onSaved = { saved += it },
        log = { logged += it },
    )

    @Test
    fun `a working key is saved once, and the screen is told`() = runBlocking {
        val store = InMemoryApiKeyStore()
        val model = model(store, KeyCheckResult.WORKS)
        assertFalse(model.state.value.replacing)

        assertEquals(KeyCheckResult.WORKS, model.submit(" AIzaSyGood\n", KeyFrom.KEY_PAGE))

        assertEquals("AIzaSyGood", store.read())
        assertEquals(listOf("AIzaSyGood"), checked)
        assertEquals(listOf(KeyCheckResult.WORKS), saved)
        assertEquals(KeyCheckResult.WORKS, model.state.value.lastResult)
        assertFalse(model.state.value.checking)
    }

    @Test
    fun `a key with a spent quota is saved too`() = runBlocking {
        val store = InMemoryApiKeyStore()
        model(store, KeyCheckResult.WORKS_BUT_QUOTA_USED).submit("AIzaSyGood", KeyFrom.REMOTE)
        assertEquals("AIzaSyGood", store.read())
    }

    @Test
    fun `a wrong key and a key without the API enabled save nothing and say so`() = runBlocking {
        for (answer in listOf(KeyCheckResult.WRONG_KEY, KeyCheckResult.API_NOT_ENABLED, KeyCheckResult.NO_NETWORK)) {
            val store = InMemoryApiKeyStore("AIzaSyOld")
            val model = model(store, answer)
            assertEquals(answer, model.submit("AIzaSyBad", KeyFrom.KEY_PAGE))
            assertEquals("AIzaSyOld", store.read())
            assertEquals(answer, model.state.value.lastResult)
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `an empty key is answered without a call`() = runBlocking {
        val model = model(InMemoryApiKeyStore(), KeyCheckResult.WORKS)
        assertEquals(KeyCheckResult.EMPTY, model.submit("  ", KeyFrom.REMOTE))
        assertTrue(checked.isEmpty())
    }

    @Test
    fun `a key that arrives after the screen closed is not saved`() = runBlocking {
        val store = InMemoryApiKeyStore()
        val model = model(store, KeyCheckResult.WORKS)
        model.close()
        assertEquals(KeyCheckResult.SCREEN_CLOSED, model.submit("AIzaSyLate", KeyFrom.KEY_PAGE))
        assertNull(store.read())
        assertTrue(saved.isEmpty())
        assertTrue("no call is spent on a key that cannot be saved", checked.isEmpty())
    }

    /** Found by `/code-review`: this used to answer WORKS while the old key stayed in use. */
    @Test
    fun `a working key whose screen closed during the check is answered as not saved`() = runBlocking {
        val store = InMemoryApiKeyStore("AIzaSyOld")
        lateinit var model: ApiKeyModel
        model = ApiKeyModel(
            store = store,
            checker = { model.close(); KeyCheckResult.WORKS },
            onSaved = { saved += it },
            log = { logged += it },
        )
        assertEquals(KeyCheckResult.SCREEN_CLOSED, model.submit("AIzaSyNew", KeyFrom.KEY_PAGE))
        assertEquals("AIzaSyOld", store.read())
        assertTrue(saved.isEmpty())
        assertTrue(logged.single().contains("nothing is saved"))
    }

    @Test
    fun `only the first of two working keys is saved`() = runBlocking {
        val store = InMemoryApiKeyStore()
        val model = model(store, KeyCheckResult.WORKS)
        model.submit("AIzaSyFirst", KeyFrom.KEY_PAGE)
        assertEquals(KeyCheckResult.SCREEN_CLOSED, model.submit("AIzaSySecond", KeyFrom.REMOTE))
        assertEquals("AIzaSyFirst", store.read())
        assertEquals(1, saved.size)
    }

    @Test
    fun `the key never reaches the log`() = runBlocking {
        model(InMemoryApiKeyStore(), KeyCheckResult.WRONG_KEY).submit("AIzaSySecret", KeyFrom.KEY_PAGE)
        model(InMemoryApiKeyStore(), KeyCheckResult.WORKS).submit("AIzaSySecret", KeyFrom.REMOTE)
        assertEquals(2, logged.size)
        assertTrue(logged.none { it.contains("AIzaSySecret") })
    }

    @Test
    fun `a saved key makes the screen the replace screen, and removing it makes it the first-run one`() {
        val store = InMemoryApiKeyStore("AIzaSyOld")
        val model = model(store, KeyCheckResult.WORKS)
        assertTrue(model.state.value.replacing)

        model.askRemove()
        assertTrue(model.state.value.confirmingRemove)
        model.remove()

        assertNull(store.read())
        assertFalse(model.state.value.replacing)
        assertFalse(model.state.value.confirmingRemove)
    }

    @Test
    fun `BACK steps out of the confirmation, then out of typing, then hands back`() {
        val model = model(InMemoryApiKeyStore("AIzaSyOld"), KeyCheckResult.WORKS)
        model.startTyping()
        model.typed("AIza")
        assertTrue(model.back())
        assertFalse(model.state.value.typing)
        model.askRemove()
        assertTrue(model.back())
        assertFalse(model.state.value.confirmingRemove)
        assertFalse(model.back())
    }
}
