package io.github.eladimany.spindle.data.covers

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderCoverRankTest {

    private fun best(vararg names: String) = names.sortedBy { FolderCoverSource.rank(it) }.first()

    @Test
    fun `cover and folder beat everything else`() {
        assertEquals("Cover.jpg", best("back.jpg", "scan1.jpg", "Cover.jpg", "folder.jpg"))
        assertEquals("folder.jpg", best("back.jpg", "AlbumArtSmall.jpg", "folder.jpg"))
    }

    @Test
    fun `front scan beats back, cd and inlay`() {
        assertEquals("Front Cover.png", best("Back Cover.png", "CD.jpg", "Inlay.jpg", "Front Cover.png"))
    }

    @Test
    fun `an unknown name beats the small Windows thumbnail and the back`() {
        assertEquals("holy diver.jpg", best("AlbumArtSmall.jpg", "back.jpg", "holy diver.jpg"))
    }
}
