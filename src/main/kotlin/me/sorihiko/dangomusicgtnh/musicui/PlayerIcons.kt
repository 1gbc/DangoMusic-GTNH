package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.RenderContext
import me.sorihiko.dangomusicgtnh.ui.VectorIcon
import me.sorihiko.dangomusicgtnh.utils.Color

/** Original 24px paths; one consistent stroke and one coverage mask per icon. */
internal object PlayerIcons {
    private val repeat = VectorIcon.path {
        move(4.0,10.0); line(4.0,9.0); curve(4.0,6.8,5.8,5.0,8.0,5.0); line(20.0,5.0)
        move(16.5,1.5); line(20.0,5.0); line(16.5,8.5)
        move(20.0,14.0); line(20.0,15.0); curve(20.0,17.2,18.2,19.0,16.0,19.0); line(4.0,19.0)
        move(7.5,15.5); line(4.0,19.0); line(7.5,22.5)
    }
    private val repeatOne = VectorIcon.path {
        move(4.0,10.0); line(4.0,9.0); curve(4.0,6.8,5.8,5.0,8.0,5.0); line(20.0,5.0)
        move(16.5,1.5); line(20.0,5.0); line(16.5,8.5)
        move(20.0,14.0); line(20.0,15.0); curve(20.0,17.2,18.2,19.0,16.0,19.0); line(4.0,19.0)
        move(7.5,15.5); line(4.0,19.0); line(7.5,22.5)
        move(10.5,10.0); line(12.5,8.5); line(12.5,15.5)
    }
    private val shuffle = VectorIcon.path {
        move(3.0,6.0); line(5.0,6.0); curve(9.0,6.0,15.0,18.0,19.0,18.0); line(21.0,18.0)
        move(17.5,14.5); line(21.0,18.0); line(17.5,21.5)
        move(3.0,18.0); line(5.0,18.0); curve(6.8,18.0,8.2,15.8,9.5,14.0)
        move(14.5,10.0); curve(16.0,7.8,17.5,6.0,19.0,6.0); line(21.0,6.0)
        move(17.5,2.5); line(21.0,6.0); line(17.5,9.5)
    }
    val play = VectorIcon.path(filled = true) { move(7.0,3.5); line(21.0,12.0); line(7.0,20.5); close() }
    val previous = VectorIcon.path(filled = true) {
        move(12.0,4.0); line(2.0,12.0); line(12.0,20.0); close()
        move(22.0,4.0); line(12.0,12.0); line(22.0,20.0); close()
    }
    val next = VectorIcon.path(filled = true) {
        move(2.0,4.0); line(12.0,12.0); line(2.0,20.0); close()
        move(12.0,4.0); line(22.0,12.0); line(12.0,20.0); close()
    }
    val stop = VectorIcon.path(filled = true) { rect(5.0,5.0,14.0,14.0) }
    val queue = VectorIcon.path {
        circle(3.5,5.0,0.8); move(8.0,5.0); line(21.0,5.0)
        circle(3.5,12.0,0.8); move(8.0,12.0); line(21.0,12.0)
        circle(3.5,19.0,0.8); move(8.0,19.0); line(21.0,19.0)
    }
    private fun speakerPath(waves: Int, muted: Boolean) = VectorIcon.path {
        move(3.0,9.0); line(7.0,9.0); line(12.0,5.0); line(12.0,19.0); line(7.0,15.0); line(3.0,15.0); close()
        if (muted) {
            move(16.0,9.0); line(21.0,15.0); move(16.0,15.0); line(21.0,9.0)
        } else {
            if (waves >= 1) { move(16.0,8.0); curve(18.5,10.0,18.5,14.0,16.0,16.0) }
            if (waves >= 2) { move(19.0,4.5); curve(23.5,8.5,23.5,15.5,19.0,19.5) }
        }
    }
    private val speakers = arrayOf(speakerPath(0,false),speakerPath(1,false),speakerPath(2,false),speakerPath(0,true))
    fun draw(ctx: RenderContext, icon: VectorIcon, cx: Float, cy: Float, size: Float, color: Color) =
        ctx.drawIcon(LayoutBounds(cx-size/2f,cy-size/2f,size,size),icon,color)
    fun mode(ctx: RenderContext, mode: MusicPlayer.Mode, cx: Float, cy: Float, size: Float, color: Color) =
        draw(ctx,when(mode) { MusicPlayer.Mode.SEQUENTIAL -> repeat; MusicPlayer.Mode.SHUFFLE -> shuffle; MusicPlayer.Mode.REPEAT_ONE -> repeatOne },cx,cy,size,color)
    fun speaker(ctx: RenderContext, cx: Float, cy: Float, color: Color, scale: Float = 1f, waves: Int = 2, muted: Boolean = false) =
        draw(ctx,speakers[if(muted) 3 else waves.coerceIn(0,2)],cx,cy,24f*scale,color)
}
