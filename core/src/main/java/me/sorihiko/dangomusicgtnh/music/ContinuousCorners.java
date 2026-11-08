package me.sorihiko.dangomusicgtnh.music;

/** Soft continuous corners, including capsule ends. Mirrors the UI shaders. */
public final class ContinuousCorners {
    private ContinuousCorners() {}

    public static double distance(double x, double y, double halfWidth, double halfHeight, double radius) {
        double r = Math.max(0, Math.min(radius, Math.min(halfWidth, halfHeight)));
        double limit = Math.min(halfWidth, halfHeight);
        if (r <= 0.001) {
            double cx = Math.abs(x) - halfWidth + r;
            double cy = Math.abs(y) - halfHeight + r;
            return Math.hypot(Math.max(cx, 0), Math.max(cy, 0)) + Math.min(Math.max(cx, cy), 0) - r;
        }
        // A true circle already has continuous curvature; only square full-radius bounds qualify.
        if (Math.abs(halfWidth - halfHeight) < 0.001 && r >= limit - 0.001) {
            return Math.hypot(x, y) - r;
        }
        // Give the corner a longer, softer shoulder instead of a tight quartic squircle.
        // Capsule ends stay close to circular, but keep zero curvature at the straight join.
        // Reduce the exponent continuously as a stretched thumb returns to a circle.
        double roundness = Math.max(0, Math.min((r / limit - 0.5) / 0.5, 1));
        roundness = roundness * roundness * (3 - 2 * roundness);
        double capsulePower = 2 + 0.25 * Math.min((Math.max(halfWidth, halfHeight) - limit) / limit, 1);
        double power = 2.5 + (capsulePower - 2.5) * roundness;
        double extent = Math.min(1.5 * r, limit);
        double qx = Math.abs(x) - halfWidth + extent;
        double qy = Math.abs(y) - halfHeight + extent;
        double inside = Math.min(Math.max(qx, qy), 0);
        qx = Math.max(qx, 0);
        qy = Math.max(qy, 0);
        double norm = Math.pow(Math.pow(qx, power) + Math.pow(qy, power), 1 / power);
        double ux = qx / Math.max(norm, 0.0001);
        double uy = qy / Math.max(norm, 0.0001);
        double gradient = Math.hypot(Math.pow(ux, power - 1), Math.pow(uy, power - 1));
        // Normalize only near the contour. Deep inside, a vanishing gradient must
        // not amplify the negative distance used by glass bevels and refraction.
        double blend = Math.max(0, Math.min((norm / extent - 0.25) / 0.5, 1));
        blend = blend * blend * (3 - 2 * blend);
        double scale = 1 + (1 / Math.max(gradient, 0.70710678) - 1) * blend;
        return (norm - extent) * scale + inside;
    }
}
