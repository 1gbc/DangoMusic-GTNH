import me.sorihiko.dangomusicgtnh.music.ContinuousCorners;

public class ContinuousCornersCheck {
    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        double r = 25;
        for (double t : new double[] { 0, 0.01, 0.1, 1, 12, 25 }) {
            double y = Math.pow(Math.pow(r, 4) - Math.pow(t, 4), 0.25);
            check(Math.abs(ContinuousCorners.distance(175 + t, 25 + y, 200, 50, r)) < 1e-8, "Quartic boundary");
        }
        double previous = Double.MAX_VALUE;
        for (double t : new double[] { 1, 0.5, 0.25 }) {
            double curvature = 3 * r * r * r * r * t * t / Math.pow(Math.pow(r,4) - Math.pow(t,4),1.75);
            check(curvature < previous, "Curvature tends to zero at the straight edge");
            previous = curvature;
        }
        check(ContinuousCorners.distance(0, 0, 200, 50, 25) < 0, "Interior");
        check(ContinuousCorners.distance(201, 51, 200, 50, 25) > 0, "Exterior");
        check(Math.abs(ContinuousCorners.distance(30, 40, 50, 50, 50)) < 1e-8, "Circular buttons");
        check(Double.isFinite(ContinuousCorners.distance(0,0,200,50,0)), "Zero radius");
        for (double radius : new double[] { 1, 6, 13, 25 }) {
            double step = radius / 10000;
            for (double angle : new double[] { 0, Math.PI / 8, Math.PI / 4, Math.PI / 2 }) {
                double last = ContinuousCorners.distance(200-radius,50-radius,200,50,radius);
                check(Math.abs(last + radius) < 1e-8, "Stable depth at corner origin");
                double lastBend = bend(last);
                // Cross the old near-zero gradient singularity and both blend endpoints.
                for (int i = 1; i <= 15000; i++) {
                    double distance = ContinuousCorners.distance(200-radius + i*step*Math.cos(angle),
                        50-radius + i*step*Math.sin(angle),200,50,radius);
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
    static double bend(double distance) {
        double edge = 1 - Math.max(0, Math.min(-distance / 32, 1));
        return edge * edge * 10;
    }
}
