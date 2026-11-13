package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.RenderContext
import me.sorihiko.dangomusicgtnh.ui.VectorIcon
import me.sorihiko.dangomusicgtnh.utils.Color
import kotlin.math.cos
import kotlin.math.sin

/** Sidebar paths use the same 24px canvas, stroke and single-pass coverage as playback icons. */
internal object SidebarIcons {
    private val home=VectorIcon.path {
        move(3.0,10.0); line(12.0,3.0); line(21.0,10.0)
        move(5.0,9.0); line(5.0,21.0); line(10.0,21.0); line(10.0,14.0)
        line(14.0,14.0); line(14.0,21.0); line(19.0,21.0); line(19.0,9.0)
    }
    private val chart=VectorIcon.path {
        roundRect(3.0,12.0,4.0,9.0,1.5); roundRect(10.0,3.0,4.0,18.0,1.5); roundRect(17.0,8.0,4.0,13.0,1.5)
    }
    private val music=VectorIcon.path {
        move(8.0,17.0); line(8.0,6.0); line(19.0,3.0); line(19.0,14.0)
        circle(5.5,18.0,2.5); circle(16.5,15.0,2.5)
    }
    private val people=VectorIcon.path {
        circle(8.0,7.0,2.5); circle(17.0,8.0,2.2)
        move(2.5,19.0); curve(2.5,12.0,13.5,12.0,13.5,19.0); line(13.5,21.0); line(2.5,21.0); close()
        move(16.0,14.0); curve(19.5,13.8,22.0,15.5,22.0,19.0); line(22.0,21.0); line(17.0,21.0)
    }
    private val cloud=VectorIcon.path {
        move(6.0,20.0); curve(0.0,20.0,0.0,11.0,6.0,11.0)
        curve(5.0,3.0,18.0,2.0,18.0,10.0); curve(25.0,10.0,25.0,20.0,18.0,20.0); close()
    }
    private val settings=VectorIcon.path {
        for(i in 0 until 32) {
            val a=-Math.PI/2+i*Math.PI/16
            val r=if(i%4==1 || i%4==2) 9.5 else 7.5
            val x=12+cos(a)*r; val y=12+sin(a)*r
            if(i==0) move(x,y) else line(x,y)
        }
        close(); circle(12.0,12.0,3.0)
    }
    private val heart=VectorIcon.path {
        move(12.0,21.0); curve(9.5,18.0,2.5,13.5,2.5,8.0)
        curve(2.5,2.5,9.0,1.5,12.0,6.5); curve(15.0,1.5,21.5,2.5,21.5,8.0)
        curve(21.5,13.5,14.5,18.0,12.0,21.0); close()
    }
    private val playlist=VectorIcon.path {
        roundRect(4.0,3.0,16.0,18.0,3.0)
        for(y in listOf(7.0,12.0,17.0)) { move(8.0,y); line(16.0,y) }
    }
    private val search=VectorIcon.path { circle(10.0,10.0,6.5); move(14.7,14.7); line(21.0,21.0) }
    private val discover = VectorIcon.path {
        for (x in listOf(3.0, 13.0)) for (y in listOf(3.0, 13.0)) roundRect(x, y, 8.0, 8.0, 1.5)
    }
    private val radio = VectorIcon.path {
        circle(12.0, 12.0, 1.4)
        move(8.0, 8.0); curve(5.8, 10.0, 5.8, 14.0, 8.0, 16.0)
        move(16.0, 8.0); curve(18.2, 10.0, 18.2, 14.0, 16.0, 16.0)
        move(5.0, 4.5); curve(1.0, 8.0, 1.0, 16.0, 5.0, 19.5)
        move(19.0, 4.5); curve(23.0, 8.0, 23.0, 16.0, 19.0, 19.5)
    }
    private val pin = VectorIcon.path {
        move(7.0, 3.0); line(17.0, 3.0); line(14.5, 6.0); line(14.5, 10.0)
        curve(17.0, 11.0, 18.0, 13.0, 18.0, 15.0); line(6.0, 15.0)
        curve(6.0, 13.0, 7.0, 11.0, 9.5, 10.0); line(9.5, 6.0); close()
        move(12.0, 15.0); line(12.0, 22.0)
    }
    private val clock = VectorIcon.path { circle(12.0, 12.0, 9.0); move(12.0, 6.0); line(12.0, 12.0); line(7.0, 12.0) }
    private val artist = VectorIcon.path {
        move(4.0, 20.0); line(8.0, 12.0); line(12.0, 16.0); line(4.0, 20.0)
        circle(15.5, 8.5, 5.5); move(12.0, 4.5); line(19.5, 12.0)
    }
    private val album = VectorIcon.path {
        roundRect(4.0, 7.0, 16.0, 14.0, 2.0)
        move(6.0, 4.0); line(18.0, 4.0); move(8.0, 1.5); line(16.0, 1.5)
    }
    private val screen = VectorIcon.path {
        roundRect(2.0, 4.0, 20.0, 14.0, 2.0); move(8.0, 21.0); line(16.0, 21.0)
    }
    private val video = VectorIcon.path {
        roundRect(2.0, 4.0, 20.0, 14.0, 2.0)
        move(10.0, 8.0); line(15.0, 11.0); line(10.0, 14.0); close()
        move(8.0, 21.0); line(16.0, 21.0)
    }
    private val right = VectorIcon.path { move(9.0, 6.0); line(15.0, 12.0); line(9.0, 18.0) }
    private val left = VectorIcon.path { move(15.0, 6.0); line(9.0, 12.0); line(15.0, 18.0) }
    private val clear = VectorIcon.path { move(6.0, 6.0); line(18.0, 18.0); move(18.0, 6.0); line(6.0, 18.0) }
    private val check = VectorIcon.path { move(4.0, 12.0); line(9.0, 17.0); line(20.0, 6.0) }
    private fun starPath(filled: Boolean) = VectorIcon.path(filled = filled) {
        for (i in 0 until 10) {
            val angle = -Math.PI / 2 + i * Math.PI / 5
            val radius = if (i % 2 == 0) 10.0 else 4.6
            val x = 12 + cos(angle) * radius
            val y = 12 + sin(angle) * radius
            if (i == 0) move(x, y) else line(x, y)
        }
        close()
    }
    private val star = starPath(false)
    private val filledStar = starPath(true)
    private val user=VectorIcon.path {
        circle(12.0,7.5,3.0); move(4.0,21.0); curve(4.0,11.0,20.0,11.0,20.0,21.0)
    }
    private val down=VectorIcon.path { move(6.0,9.0); line(12.0,15.0); line(18.0,9.0) }
    private val up=VectorIcon.path { move(6.0,15.0); line(12.0,9.0); line(18.0,15.0) }
    private val swap=VectorIcon.path {
        move(3.0,7.0); line(21.0,7.0); move(17.0,3.0); line(21.0,7.0); line(17.0,11.0)
        move(21.0,17.0); line(3.0,17.0); move(7.0,13.0); line(3.0,17.0); line(7.0,21.0)
    }
    fun draw(ctx: RenderContext,glyph: String,cx: Float,cy: Float,color: Color,size: Float=20f) {
        val path=when(glyph) {
            "⌂"->home; "▤"->chart; "♫"->music; "♬"->people; "☁"->cloud; "⚙"->settings; "♥"->heart
            "search" -> search; "user" -> user; "up" -> up; "down" -> down; "⇄" -> swap
            "discover" -> discover; "radio" -> radio; "pin" -> pin; "clock" -> clock; "artist" -> artist
            "album" -> album; "screen" -> screen; "video" -> video; "genre" -> chart; "compilation" -> people
            "right" -> right; "left" -> left; "clear" -> clear; "check" -> check; "star" -> star; "filledStar" -> filledStar
            else -> playlist
        }
        ctx.drawIcon(LayoutBounds(cx-size/2f,cy-size/2f,size,size),path,color)
    }
}
