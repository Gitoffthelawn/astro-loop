package com.astroloop.game.core

import android.graphics.Canvas
import android.view.SurfaceHolder
import com.astroloop.game.tuning.RunHooks

class GameThread(
    private val surfaceHolder: SurfaceHolder,
    private val gameView: GameSurfaceView
) : Thread() {

    @Volatile
    var isRunning: Boolean = false
        private set

    private var lastFrameTime: Long = 0
    private var frameCount: Int = 0
    private var lastFpsTime: Long = 0
    @Volatile
    var currentFps: Int = 0
        private set

    fun setRunning(running: Boolean) {
        isRunning = running
    }

    override fun run() {
        var canvas: Canvas?

        lastFrameTime = System.nanoTime()
        lastFpsTime = System.currentTimeMillis()

        while (isRunning) {
            canvas = null

            // Capture frame start time BEFORE canvas lock
            val frameStart = System.nanoTime()
            val deltaTime = (frameStart - lastFrameTime) / 1_000_000_000f
            lastFrameTime = frameStart
            val clampedDelta = deltaTime.coerceAtMost(0.033f)

            // Never lock a surface that has already been torn down.
            //
            // lockHardwareCanvas + unlockCanvasAndPost posts the frame to HWUI's RenderThread
            // (DrawFrameTask::postAndWait). If the surface was destroyed since we last drew, that
            // post lands on an abandoned buffer queue, and on some drivers — Samsung's Android 9
            // Mali stack among them — HWUI asserts and aborts the process rather than degrading.
            // The reported crash is exactly that sequence: surfaceDestroyed, then our frame 13ms
            // later, then SIGABRT in EglManager::damageFrame with EGL_BAD_ACCESS.
            if (!surfaceHolder.surface.isValid) {
                try {
                    sleep(GameConfig.FRAME_TIME_MS)
                } catch (e: InterruptedException) {
                    // Ignore — the loop condition is re-checked immediately.
                }
                continue
            }

            try {
                canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        surfaceHolder.lockHardwareCanvas()
                    } catch (e: Exception) {
                        surfaceHolder.lockCanvas()
                    }
                } else {
                    // API 28 and below take the software canvas deliberately.
                    //
                    // lockHardwareCanvas posts the frame through HWUI's RenderThread, and when the
                    // surface is torn down with a swap still in flight, Android P's HWUI does not
                    // degrade — it calls LOG_ALWAYS_FATAL and takes the process with it. A reported
                    // Samsung Android 9 death cutscene aborts with EGL_BAD_ALLOC on exactly that
                    // path, roughly 19 deaths in 20. The software canvas has no EGL context, so
                    // there is nothing to abandon. It costs frame rate on hardware that is a decade
                    // old; it buys a game that does not die when the player does.
                    surfaceHolder.lockCanvas()
                }

                if (canvas != null) {
                    synchronized(surfaceHolder) {
                        gameView.update(clampedDelta)
                        gameView.render(canvas)

                        // FPS counter
                        frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTime >= 1000) {
                            currentFps = frameCount
                            frameCount = 0
                            lastFpsTime = now
                        }
                    }
                }
            } catch (e: Throwable) {
                // Catch Throwable (not just Exception) so a per-frame Error — e.g.
                // OutOfMemoryError during a heavy scene's bitmap rebuild on resume, or
                // a recycled-bitmap draw racing surfaceChanged — skips the frame and is
                // logged instead of killing the process. The next frame can recover.
                try { RunHooks.listener?.onUpdateError(e) } catch (_: Throwable) {}
                e.printStackTrace()
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // Sleep for remainder of frame budget, measured from frameStart
            val elapsed = (System.nanoTime() - frameStart) / 1_000_000
            val targetFrameTime = GameConfig.FRAME_TIME_MS
            if (elapsed < targetFrameTime) {
                try {
                    sleep(targetFrameTime - elapsed)
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }
        }
    }

    fun pause() {
        isRunning = false
        interrupt()          // wake the thread immediately if it's sleeping
        try {
            // Generous, but still bounded. This was join(1000), and that timeout was the bug:
            // when it expired the loop was still alive, so the caller went on to swap the content
            // view while we kept posting frames into a surface being destroyed underneath us,
            // which aborts the process on some drivers — the same crash the surface-validity
            // check in the loop above exists to prevent.
            //
            // Three seconds rather than no limit at all. A healthy exit takes under one frame —
            // the loop body is bounded by a frame plus its sleep, and interrupt() above wakes that
            // sleep — so nothing legitimate reaches this timeout and the race window is closed in
            // every realistic case. Removing the bound entirely would close it in *all* cases, but
            // this runs on the UI thread: a game thread wedged behind a stalled RenderThread would
            // then hang the app rather than recover, trading a crash for an ANR. The valve stays.
            join(3000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun resumeThread() {
        // Thread will be restarted by GameSurfaceView
    }
}
