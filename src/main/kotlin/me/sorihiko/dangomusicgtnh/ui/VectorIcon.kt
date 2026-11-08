package me.sorihiko.dangomusicgtnh.ui

import java.awt.geom.FlatteningPathIterator
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.RoundRectangle2D

/** 24-unit path; the whole stroke is unioned before antialiasing, never alpha-overlapped. */
class VectorIcon private constructor(val segments: FloatArray, val strokeWidth: Float, val filled: Boolean) {
    class Builder {
        internal val path = Path2D.Double()
        fun move(x: Double, y: Double) { path.moveTo(x,y) }
        fun line(x: Double, y: Double) { path.lineTo(x,y) }
        fun curve(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) { path.curveTo(x1,y1,x2,y2,x,y) }
        fun close() { path.closePath() }
        fun rect(x: Double, y: Double, w: Double, h: Double) { move(x,y); line(x+w,y); line(x+w,y+h); line(x,y+h); close() }
        fun roundRect(x: Double,y: Double,w: Double,h: Double,r: Double) { path.append(RoundRectangle2D.Double(x,y,w,h,r*2,r*2),false) }
        fun circle(x: Double, y: Double, r: Double) {
            val k = r * 0.5522847498307936
            move(x+r,y); curve(x+r,y+k,x+k,y+r,x,y+r)
            curve(x-k,y+r,x-r,y+k,x-r,y); curve(x-r,y-k,x-k,y-r,x,y-r)
            curve(x+k,y-r,x+r,y-k,x+r,y); close()
        }
    }
    companion object {
        const val MAX_SEGMENTS = 96
        const val STROKE = 1.75f
        fun path(filled: Boolean = false, build: Builder.() -> Unit): VectorIcon {
            val path=Builder().apply(build).path
            var tolerance=0.025
            var segments=flatten(path,tolerance)
            // Closed, rounded subpaths can need more segments than open playback icons.
            // Adapt subpixel flattening precision rather than truncate a path or split its mask.
            while(segments.size/4>MAX_SEGMENTS && tolerance<0.2) {
                tolerance*=1.5
                segments=flatten(path,tolerance)
            }
            require(segments.size/4<=MAX_SEGMENTS) { "Icon path exceeds shader capacity" }
            return VectorIcon(segments,STROKE,filled)
        }
        private fun flatten(path: Path2D.Double,tolerance: Double): FloatArray {
            val it = FlatteningPathIterator(path.getPathIterator(null),tolerance,12)
            val p = DoubleArray(6); val data = ArrayList<Float>()
            var x=0.0; var y=0.0; var sx=0.0; var sy=0.0
            fun segment(nx: Double, ny: Double) {
                if (x!=nx || y!=ny) { data.add(x.toFloat()); data.add(y.toFloat()); data.add(nx.toFloat()); data.add(ny.toFloat()) }
                x=nx; y=ny
            }
            while (!it.isDone) {
                when (it.currentSegment(p)) {
                    PathIterator.SEG_MOVETO -> { x=p[0]; y=p[1]; sx=x; sy=y }
                    PathIterator.SEG_LINETO -> segment(p[0],p[1])
                    PathIterator.SEG_CLOSE -> segment(sx,sy)
                }
                it.next()
            }
            return data.toFloatArray()
        }
    }
}
