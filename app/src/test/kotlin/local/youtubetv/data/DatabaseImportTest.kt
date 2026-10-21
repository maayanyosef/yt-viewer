package local.youtubetv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one check an uploaded backup gets before it is written: it begins like SQLite. Fork 0.7. */
class DatabaseImportTest {

    @Test
    fun `an SQLite header is accepted and anything else is refused`() {
        assertTrue(isSqliteFile("SQLite format 3\u0000and the rest".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(isSqliteFile("<!doctype html>".toByteArray()))
        assertFalse(isSqliteFile(ByteArray(4)))
    }
}
