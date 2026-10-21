package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rule inside the thumbnail seam that does not need Android.
 *
 * Downloading and decoding a JPEG needs a real device, so those are not tested here. Choosing
 * how far to shrink a picture is arithmetic, and getting it wrong costs eight times the memory
 * on a television with very little of it.
 */
class ThumbnailLoaderTest {

    @Test
    fun `a maxres thumbnail is halved once, which still covers a card`() {
        // YouTube's maxresdefault is 1280 wide. 640 still covers a card of about 340 pixels.
        assertEquals(2, sampleSizeFor(1_280))
    }

    @Test
    fun `a picture already near the card size is not shrunk at all`() {
        assertEquals(1, sampleSizeFor(480))
        assertEquals(1, sampleSizeFor(320))
    }

    @Test
    fun `a very large picture is halved more than once`() {
        assertEquals(4, sampleSizeFor(1_920))
        assertEquals(8, sampleSizeFor(3_840))
    }

    @Test
    fun `a picture whose size could not be read is left alone`() {
        // BitmapFactory reports -1 when it could not decode the bounds at all.
        assertEquals(1, sampleSizeFor(-1))
        assertEquals(1, sampleSizeFor(0))
    }
}

/** How big the picture cache may grow, from what Android says about the device. */
class ThumbnailCacheBytesTest {

    @Test
    fun `a television with a small heap keeps a small cache`() {
        assertEquals(LOW_RAM_CACHE_BYTES, thumbnailCacheBytes(memoryClassMb = 128, lowRamDevice = false))
        assertEquals(LOW_RAM_CACHE_BYTES, thumbnailCacheBytes(memoryClassMb = 192, lowRamDevice = false))
    }

    @Test
    fun `a device that says it is low on RAM keeps a small cache whatever its heap`() {
        assertEquals(LOW_RAM_CACHE_BYTES, thumbnailCacheBytes(memoryClassMb = 512, lowRamDevice = true))
    }

    @Test
    fun `a television with room keeps the full cache`() {
        assertEquals(CACHE_BYTES, thumbnailCacheBytes(memoryClassMb = 256, lowRamDevice = false))
    }
}
