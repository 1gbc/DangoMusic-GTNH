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
            "search"->search; "user"->user; "up"->up; "down"->down; "⇄"->swap; else->playlist
        }
        ctx.drawIcon(LayoutBounds(cx-size/2f,cy-size/2f,size,size),path,color)
    }
}
