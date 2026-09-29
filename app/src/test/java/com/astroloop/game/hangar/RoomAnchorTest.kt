package com.astroloop.game.hangar

import com.astroloop.game.core.DesignSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAnchorTest {

    // Pixel-9-Pro-shaped phone, below the sw600 gate: the room is the whole portrait screen.
    private val phonePortraitW = DesignSpace.metricsFor(1280f, 2844f).width      // ~964
    private val phoneLandscapeW = DesignSpace.metricsFor(2844f, 1280f).width     // 2142
    private val phoneLandscapeH = DesignSpace.metricsFor(2844f, 1280f).height    // ~964

    @Test
    fun `portrait page origins are the shipped single-page transform`() {
        // Below the gate the room IS the screen, so page p sits at (p - current) * screenWidth.
        val w = phonePortraitW
        for (current in 0..2) {
            for (p in 0..2) {
                assertEquals(
                    (p - current) * w,
                    RoomAnchor.pageOriginX(p, current, 0f, w, w, landscape = false),
                    0.01f
                )
            }
        }
    }

    @Test
    fun `portrait above the gate keeps the centred room block`() {
        // Tablet: the room narrows to the content column and the three-room block is centred.
        val m = DesignSpace.metricsFor(1600f, 2560f)
        val roomW = HangarMetrics.roomWidth(m.width, m.layout.content.width, smallestScreenWidthDp = 800)
        val centring = (m.width - roomW) / 2f
        assertTrue("tablet room must be narrower than the screen", roomW < m.width)
        assertEquals(centring, RoomAnchor.anchorX(0, m.width, roomW, landscape = false), 0.01f)
        assertEquals(centring, RoomAnchor.anchorX(1, m.width, roomW, landscape = false), 0.01f)
        assertEquals(centring, RoomAnchor.anchorX(2, m.width, roomW, landscape = false), 0.01f)
    }

    @Test
    fun `landscape anchors crew right, shop left, launchpad flush`() {
        val screenW = phoneLandscapeW
        val roomW = phonePortraitW
        assertEquals(screenW - roomW, RoomAnchor.anchorX(0, screenW, roomW, landscape = true), 0.01f)
        assertEquals(0f, RoomAnchor.anchorX(2, screenW, roomW, landscape = true), 0.01f)
        assertEquals(0f, RoomAnchor.anchorX(1, screenW, roomW, landscape = true), 0.01f)
    }

    @Test
    fun `rotated phone dimensions this suite is built on count as landscape`() {
        // phoneLandscapeW x phoneLandscapeH is the actual (width, height) DesignSpace hands back
        // for the rotated device every landscape test in this file uses. Confirm RoomAnchor's own
        // tie-break — not just DesignSpace's — agrees it is landscape, so the fixture is not
        // silently exercising the portrait branch everywhere else in the file.
        assertTrue(RoomAnchor.isLandscape(phoneLandscapeW, phoneLandscapeH))
    }

    @Test
    fun `landscape page widths are portrait for the rooms and full screen for the launchpad`() {
        val screenW = phoneLandscapeW
        val roomW = phonePortraitW
        assertEquals(roomW, RoomAnchor.pageWidth(0, screenW, roomW, landscape = true), 0.01f)
        assertEquals(screenW, RoomAnchor.pageWidth(1, screenW, roomW, landscape = true), 0.01f)
        assertEquals(roomW, RoomAnchor.pageWidth(2, screenW, roomW, landscape = true), 0.01f)
    }

    @Test
    fun `landscape stride stays one page per screen`() {
        assertEquals(
            phoneLandscapeW,
            RoomAnchor.stride(phoneLandscapeW, phonePortraitW, landscape = true),
            0.01f
        )
    }

    @Test
    fun `viewportX is one screen per page plus drag, in world units`() {
        // World X of the screen's left edge. In landscape the stride is a full screen, so this is
        // just currentPage screens in, plus however far the page has been dragged.
        val screenW = phoneLandscapeW
        val roomW = phonePortraitW

        // At rest on page 0, the screen's left edge IS the world origin by construction.
        assertEquals(0f, RoomAnchor.viewportX(0, 0f, screenW, roomW, landscape = true), 0.01f)

        // At rest on page 1: one full screen further into the world.
        assertEquals(screenW, RoomAnchor.viewportX(1, 0f, screenW, roomW, landscape = true), 0.01f)

        // At rest on page 2: two full screens in.
        assertEquals(2f * screenW, RoomAnchor.viewportX(2, 0f, screenW, roomW, landscape = true), 0.01f)

        // Mid-drag on page 1: the offset shifts the world/screen mapping by exactly itself.
        assertEquals(screenW + 75f, RoomAnchor.viewportX(1, 75f, screenW, roomW, landscape = true), 0.01f)

        // Portrait, below the gate: the room IS the screen, so stride is screenWidth and this is
        // the original single-page-per-screen transform — two pages, two screen-widths in.
        val w = phonePortraitW
        assertEquals(2f * w, RoomAnchor.viewportX(2, 0f, w, w, landscape = false), 0.01f)
    }

    @Test
    fun `landscape page origins place neighbours by absolute screen position`() {
        // Every expected number here is placed by hand from the design, not by calling
        // pageOriginX/toRoomX on themselves — these are the tests that actually discriminate
        // anchorX(page) from anchorX(currentPage); see the round-trip test below for why it
        // cannot do this job by itself.
        val screenW = phoneLandscapeW
        val roomW = phonePortraitW

        // Resting on the shipyard (page 1, full-screen, flush at screen X 0): the crew room is
        // one screen to the left in world space, and hugs the RIGHT edge of its OWN slot, so its
        // right edge lands exactly on the shipyard's left screen edge with nothing beyond it but
        // starfield. Left edge (the origin) is therefore one room-width short of X 0.
        assertEquals(-roomW, RoomAnchor.pageOriginX(0, 1, 0f, screenW, roomW, landscape = true), 0.01f)

        // Same view: the shop is one screen to the right, hugging the LEFT edge of its own slot,
        // so its left edge (the origin) lands exactly on the shipyard's right screen edge.
        assertEquals(screenW, RoomAnchor.pageOriginX(2, 1, 0f, screenW, roomW, landscape = true), 0.01f)

        // Resting on the crew page (page 0): its room hugs the right of screen 0, spanning
        // [screenW - roomW, screenW]. The shop is two full pages further right in world space and
        // still hugs the LEFT of its own (third) slot, so its origin is exactly two screen-widths
        // out — no crew-side hug term carries over onto it.
        // This is the case that catches an anchorX(page) -> anchorX(currentPage) swap: anchorX(2)
        // is 0 but anchorX(0) is screenW - roomW, so the swap moves this number by that whole gap.
        assertEquals(2f * screenW, RoomAnchor.pageOriginX(2, 0, 0f, screenW, roomW, landscape = true), 0.01f)

        // Resting on the shop page (page 2): its room hugs the left of screen 2, spanning
        // [2*screenW, 2*screenW + roomW]. The crew page is two full pages to the left and still
        // hugs the RIGHT of its own (first) slot, so its origin is two screens back from there,
        // plus its own hug term (screenW - roomW) — the shop's hug does not carry over either.
        assertEquals(
            -2f * screenW + (screenW - roomW),
            RoomAnchor.pageOriginX(0, 2, 0f, screenW, roomW, landscape = true),
            0.01f
        )
    }

    @Test
    fun `the room inverse round-trips in both orientations, dragged and at rest`() {
        // NOTE: this reduces to `roomLocal + X - X == roomLocal` for ANY pageOriginX, because
        // toRoomX is defined as `screenX - pageOriginX(currentPage, currentPage, ...)` — it always
        // measures a page against itself, so it cannot fail no matter how pageOriginX is broken.
        // It stays here because it documents the inverse relationship; the tests that actually
        // guard the arithmetic are `landscape page origins place neighbours by absolute screen
        // position` and `viewportX is one screen per page plus drag, in world units` above, which
        // place pages by hand-derived absolute screen position instead of round-tripping.
        data class Case(val screenW: Float, val roomW: Float, val landscape: Boolean)
        val cases = listOf(
            Case(phonePortraitW, phonePortraitW, false),
            Case(phoneLandscapeW, phonePortraitW, true)
        )
        for (c in cases) {
            for (current in 0..2) {
                for (offset in listOf(-120f, 0f, 250f)) {
                    for (roomLocal in listOf(0f, 37f, c.roomW / 2f, c.roomW)) {
                        val screenX = roomLocal +
                            RoomAnchor.pageOriginX(current, current, offset, c.screenW, c.roomW, c.landscape)
                        assertEquals(
                            "landscape=${c.landscape} page=$current offset=$offset",
                            roomLocal,
                            RoomAnchor.toRoomX(screenX, current, offset, c.screenW, c.roomW, c.landscape),
                            0.01f
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `dragging shifts every page by the same amount`() {
        val w = phoneLandscapeW
        val r = phonePortraitW
        val rest = RoomAnchor.pageOriginX(2, 1, 0f, w, r, landscape = true)
        val dragged = RoomAnchor.pageOriginX(2, 1, 120f, w, r, landscape = true)
        assertEquals(rest - 120f, dragged, 0.01f)
    }

    @Test
    fun `isLandscape breaks the square tie toward portrait`() {
        assertTrue(RoomAnchor.isLandscape(2142f, 960f))
        assertTrue(!RoomAnchor.isLandscape(960f, 2142f))
        assertTrue(!RoomAnchor.isLandscape(1000f, 1000f))
    }
}
