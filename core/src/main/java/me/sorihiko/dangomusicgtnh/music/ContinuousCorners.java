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
        if (r == 0) return Math.hypot(qx, qy) + inside;
        double ux = qx / Math.max(norm, 0.0001);
        double uy = qy / Math.max(norm, 0.0001);
        double gradient = Math.hypot(ux * ux * ux, uy * uy * uy);
        // Normalize only near the contour. Deep inside, a vanishing gradient must
        // not amplify the negative distance used by glass bevels and refraction.
        double blend = Math.max(0, Math.min((norm / r - 0.25) / 0.5, 1));
        blend = blend * blend * (3 - 2 * blend);
        double scale = 1 + (1 / Math.max(gradient, 0.70710678) - 1) * blend;
        return (norm - r) * scale + inside;
    }
}
