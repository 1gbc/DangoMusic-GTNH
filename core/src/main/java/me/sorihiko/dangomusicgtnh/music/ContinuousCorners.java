package me.sorihiko.dangomusicgtnh.music;

/** Quartic corner with zero curvature where it meets a straight edge. Mirrors the UI shaders. */
public final class ContinuousCorners {
    private ContinuousCorners() {}

    public static double distance(double x, double y, double halfWidth, double halfHeight, double radius) {
        double r = Math.max(0, Math.min(radius, Math.min(halfWidth, halfHeight)));
        double qx = Math.abs(x) - halfWidth + r;
        double qy = Math.abs(y) - halfHeight + r;
        double inside = Math.min(Math.max(qx, qy), 0);
        qx = Math.max(qx, 0);
        qy = Math.max(qy, 0);
        if (Math.abs(halfWidth - halfHeight) < 0.001 && r >= Math.min(halfWidth, halfHeight) - 0.001) {
            return Math.hypot(x, y) - r;
        }
        double norm = Math.sqrt(Math.sqrt(qx * qx * qx * qx + qy * qy * qy * qy));
        double gradient = Math.hypot(qx * qx * qx, qy * qy * qy) / Math.max(norm * norm * norm, 0.0001);
        return (norm - r) / Math.max(gradient, 0.70710678) + inside;
    }
}
