import me.sorihiko.dangomusicgtnh.music.ContinuousCorners;

public class ContinuousCornersCheck {
    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        double r = 25;
        double inset = diagonalInset(200, 50, r);
        check(inset > 0.34 * r && inset < 0.39 * r, "Visibly rounder card corners");
        check(Math.abs(ContinuousCorners.distance(200, 0, 200, 50, r)) < 1e-8, "Right edge stays in bounds");
        check(Math.abs(ContinuousCorners.distance(0, 50, 200, 50, r)) < 1e-8, "Top edge stays in bounds");
        double previous = Double.MAX_VALUE;
        for (double t : new double[] { 3, 1.5, 0.75 }) {
            double y = topBoundary(200 - 1.5 * r + t, 200, 50, r);
            double curvatureEstimate = (50 - y) / (t * t);
            check(curvatureEstimate < previous, "Curvature tends to zero at the straight edge");
            previous = curvatureEstimate;
        }
        check(ContinuousCorners.distance(0, 0, 200, 50, 25) < 0, "Interior");
        check(ContinuousCorners.distance(201, 51, 200, 50, 25) > 0, "Exterior");
        check(Math.abs(ContinuousCorners.distance(30, 40, 50, 50, 50)) < 1e-8, "Circular buttons");
        for (double[] bounds : new double[][] {{17,10}, {11,8}, {80,16}, {10,17}}) {
            double hw = bounds[0], hh = bounds[1], radius = Math.min(hw, hh);
            for (double angle : new double[] {0, Math.PI/8, Math.PI/4, Math.PI/2}) {
                double x = hw - radius + radius * Math.cos(angle);
                double y = hh - radius + radius * Math.sin(angle);
                double d = ContinuousCorners.distance(x,y,hw,hh,radius);
                check(Math.abs(d) < 0.045 * radius, "Continuous capsule ends stay visually close to round");
                check(Math.abs(ContinuousCorners.distance(x,y,hw,hh,radius - 0.002) - d) < 0.002,
                    "Near-full radius converges smoothly to a capsule");
            }
        }
        previous = Double.MAX_VALUE;
        for (double t : new double[] { 1.5, 0.75, 0.375 }) {
            double curvatureEstimate = (10 - topBoundary(7+t,17,10,10)) / (t*t);
            check(curvatureEstimate < previous, "Full-radius capsule joins also have continuous curvature");
            previous = curvatureEstimate;
        }
        check(Math.abs(ContinuousCorners.distance(8/Math.sqrt(2),8/Math.sqrt(2),8.002,8,8)) < 0.002,
            "Stretched thumb converges continuously to a circle");
        check(Double.isFinite(ContinuousCorners.distance(0,0,200,50,0)), "Zero radius");
        for (double radius : new double[] { 1, 6, 13, 25 }) {
            double step = radius / 10000;
            double extent = 1.5 * radius;
            for (double angle : new double[] { 0, Math.PI / 8, Math.PI / 4, Math.PI / 2 }) {
                double last = ContinuousCorners.distance(200-extent,50-extent,200,50,radius);
                check(Math.abs(last + extent) < 1e-8, "Stable depth at corner origin");
                double lastBend = bend(last);
                // Cross the old near-zero gradient singularity and both blend endpoints.
                for (int i = 1; i <= 22000; i++) {
                    double distance = ContinuousCorners.distance(200-extent + i*step*Math.cos(angle),
                        50-extent + i*step*Math.sin(angle),200,50,radius);
                    check(Double.isFinite(distance), "Finite interior distance");
                    check(Math.abs(distance-last) <= 2*step, "No interior distance jump");
                    check(Math.abs(bend(distance)-lastBend) <= 2*step, "No refraction jump");
                    last = distance;
                    lastBend = bend(distance);
                }
            }
        }
        check(Math.abs(ContinuousCorners.distance(203,54,200,50,0) - 5) < 1e-8, "Rectangular distance");
        System.out.println("Continuous corner geometry and interior/refraction continuity checks passed");
    }
    static double diagonalInset(double hw, double hh, double r) {
        double lo = 0, hi = Math.min(hw, hh);
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (ContinuousCorners.distance(hw-mid,hh-mid,hw,hh,r) > 0) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }
    static double topBoundary(double x, double hw, double hh, double r) {
        double lo = 0, hi = hh;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (ContinuousCorners.distance(x,mid,hw,hh,r) > 0) hi = mid;
            else lo = mid;
        }
        return (lo + hi) / 2;
    }
    static double bend(double distance) {
        double edge = 1 - Math.max(0, Math.min(-distance / 32, 1));
        return edge * edge * 10;
    }
}
