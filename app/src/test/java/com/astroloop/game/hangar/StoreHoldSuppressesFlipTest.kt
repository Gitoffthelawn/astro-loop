package com.astroloop.game.hangar

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real touch-handling production code — `purchaseHeldUpgrade` and `handleStoreTap` —
 * rather than poking `HangarState` directly (`StoreTapFlipTest`'s approach), so this is the test
 * that would have caught the flip-after-purchase bug: a completed hold buys a level and leaves
 * the finger down, so the release that follows falls through to `handleTap` → `handleStoreTap`
 * on the very tile that was just bought.
 *
 * What this does NOT cover: full `MotionEvent` dispatch through `onTouchEvent` — `ACTION_DOWN`
 * tile resolution, the 15f slop cancel, and `ACTION_UP`'s dispatch into `handleTap`. Those all
 * resolve tiles against `renderer.upgradeRects`, which `StorePageRenderer.draw()` only populates
 * from inside a live Canvas pass, and `HangarSurfaceView.render()` gets that Canvas from
 * `holder.lockCanvas()` — there is no valid `Surface` to draw into under Robolectric (same as on
 * a real device before the surface is ready), so that call returns null and no draw ever runs.
 * Injecting a throwaway `RectF` into `upgradeRects` and calling the two handlers directly is the
 * highest seam available that still runs the actual suppression decision rather than a
 * reimplementation of it. The `ACTION_DOWN`/`ACTION_MOVE`/`ACTION_UP` wiring itself remains
 * verified only by code trace and a device check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StoreHoldSuppressesFlipTest {

    private lateinit var persistence: PersistenceManager
    private lateinit var view: HangarSurfaceView

    // upgradeIds[0] == "health" in both handleUpgradeTap and StoreUpgradeDefinitions.
    private val tileIndex = 0
    private val tileRect = RectF(0f, 0f, 100f, 100f)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        persistence = PersistenceManager(context)
        persistence.resetAllProgress()
        persistence.setYen(100_000)
        view = HangarSurfaceView(context) { _, _ -> }
        // handleUpgradeTap's affordability check reads state.actualYen, a mirror of
        // persistence's yen that normally gets its initial value from state.initialize()
        // (never run here — nothing else in this test needs the rest of surfaceCreated).
        view.state.actualYen = persistence.getYen()
        // Stand-in for a completed draw pass — see class doc. pageScrollOffset defaults to 0f,
        // so handleStoreTap's roomX() is the identity and this rect lines up with the (x, y)
        // used below without any layout math.
        view.renderer.upgradeRects.add(tileRect)
    }

    @Test
    fun `a completed hold buys one level and the release does not flip the tile`() {
        val yenBefore = persistence.getYen()

        view.purchaseHeldUpgrade(tileIndex)                            // the hold completing
        view.handleStoreTap(tileRect.centerX(), tileRect.centerY())    // the finger lifting

        assertEquals("a completed hold buys exactly one level",
            1, persistence.getUpgradeLevel("health"))
        assertTrue("the purchase must have spent yen", persistence.getYen() < yenBefore)
        assertFalse("the release after a purchase must not flip the tile",
            view.state.isStoreCardFlipped(tileIndex))
    }

    @Test
    fun `a quick tap flips the tile and buys nothing`() {
        val yenBefore = persistence.getYen()

        view.handleStoreTap(tileRect.centerX(), tileRect.centerY())    // tap, no hold beforehand

        assertEquals("a tap must never cost money", yenBefore, persistence.getYen())
        assertEquals(0, persistence.getUpgradeLevel("health"))
        assertTrue("a tap flips the tile it landed on",
            view.state.isStoreCardFlipped(tileIndex))
    }
}
