package com.astroloop.game.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import com.astroloop.game.core.GameConfig
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.render.IconCache
import com.astroloop.game.render.ShapeRenderer
import com.astroloop.game.render.ShipRenderer
import com.astroloop.game.tuning.KnobsWeapons

/** The game's own icons as line-height drawables for the Lab's plain views. */
object LabIcons {
    const val MARK = "￼"
    private const val SIZE_DP = 28
    private val shipBitmaps = HashMap<String, Bitmap>()

    fun iconSizePx(ctx: Context): Int = LabUi.dp(ctx, SIZE_DP)

    fun weapon(ctx: Context, id: String): Drawable? = wrap(ctx, IconCache.getWeaponIcon(id))
    fun passive(ctx: Context, id: String): Drawable? =
        wrap(ctx, IconCache.getPassiveIcon(if (id == "drone") "combat_drone" else id))
    fun pilot(ctx: Context, pilotId: String): Drawable? = wrap(ctx, IconCache.getPortrait(pilotId))

    /** The ship as the hangar draws it, nose up, rendered once and cached. */
    fun ship(ctx: Context, shipId: String): Drawable? {
        val def = ShipDefinitions.getShip(shipId) ?: return null
        val bmp = synchronized(shipBitmaps) {
            shipBitmaps.getOrPut(shipId) {
                val side = (GameConfig.SHIP_BASE_SIZE * 4).toInt()
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { b ->
                    ShipRenderer.drawShip(
                        canvas = Canvas(b), shapeRenderer = ShapeRenderer(),
                        x = side / 2f, y = side / 2f, rotation = (-Math.PI / 2).toFloat(),
                        size = GameConfig.SHIP_BASE_SIZE, shipColor = def.color, pilotColor = def.color,
                        startingWeaponId = def.startingWeaponId,
                    )
                }
            }
        }
        return wrap(ctx, bmp)
    }

    fun forKnobGroup(ctx: Context, knobIdPrefix: String): Drawable? = when {
        KnobsWeapons.all.any { it.weaponId == knobIdPrefix } -> weapon(ctx, knobIdPrefix)
        else -> passive(ctx, knobIdPrefix)
    }

    fun label(name: CharSequence, icon: Drawable?, iconFirst: Boolean): CharSequence {
        if (icon == null) return name
        val sb = SpannableStringBuilder()
        if (iconFirst) {
            sb.append(MARK)
            sb.setSpan(ImageSpan(icon, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(' ').append(name)
        } else {
            sb.append(name).append(' ')
            val at = sb.length
            sb.append(MARK)
            sb.setSpan(ImageSpan(icon, ImageSpan.ALIGN_BOTTOM), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun wrap(ctx: Context, bmp: Bitmap?): Drawable? {
        bmp ?: return null
        val px = iconSizePx(ctx)
        return BitmapDrawable(ctx.resources, bmp).apply { setBounds(0, 0, px, px) }
    }
}
