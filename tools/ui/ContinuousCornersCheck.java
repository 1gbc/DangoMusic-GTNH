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
        System.out.println("Continuous corner boundary, curvature, circle and zero-radius checks passed");
    }
}
