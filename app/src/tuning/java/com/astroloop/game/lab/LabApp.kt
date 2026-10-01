package com.astroloop.game.lab

import android.app.Application
import android.content.Context
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.tuning.RunHooks

/**
 * Process-wide Lab setup. It lives in the Application because a restored process can bring back
 * the game activity without ever creating the Lab screen; the tuning, run listener and report
 * must be in place before any activity.
 */
class LabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initLab(this)
    }

    companion object {
        lateinit var presets: PresetStore
            private set

        internal fun initLab(context: Context) {
            com.astroloop.game.render.IconCache.preload(context)
            val persistence = PersistenceManager(context)
            LabSeed.seedIfNeeded(persistence)
            LabSeed.ensureAstroLoop(persistence)
            presets = PresetStore(PrefsKeyValue(context.getSharedPreferences("lab_presets", Context.MODE_PRIVATE)))
            LabSession.attach(PrefsKeyValue(context.getSharedPreferences("lab_session", Context.MODE_PRIVATE)))
            RunHooks.listener = LabSession
            presets.save(LabSession.activatePreset(presets.active()!!).normalised)
        }
    }
}
