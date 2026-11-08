#version 120
uniform sampler2D u_Texture;
uniform float u_Opacity;
uniform vec4 u_Bounds;
uniform float u_Radius;
uniform float u_Continuous;
varying vec2 uv;
varying vec2 v_Position;

float sdRoundBox(vec2 p, vec2 b, float r) {
    // 经典风格（默认）：普通圆角
    if (u_Continuous < 0.5) {
        vec2 c = abs(p) - b + r;
        return length(max(c, 0.0)) + min(max(c.x, c.y), 0.0) - r;
    }
    r = clamp(r, 0.0, min(b.x, b.y));
    float limit = min(b.x, b.y);
    if (r <= 0.001) {
        vec2 c = abs(p) - b + r;
        return length(max(c, 0.0)) + min(max(c.x, c.y), 0.0) - r;
    }
    // A circle is already curvature-continuous; elongated capsules need smooth shoulders.
    if (abs(b.x - b.y) < 0.001 && r >= limit - 0.001) return length(p) - r;
    // A wider shoulder and gentler exponent avoid the boxy quartic contour.
    float capsulePower = 2.0 + 0.25 * min((max(b.x, b.y) - limit) / limit, 1.0);
    float power = mix(2.5, capsulePower, smoothstep(0.5, 1.0, r / limit));
    float extent = min(1.5 * r, limit);
    vec2 q = abs(p) - b + extent;
    float inside = min(max(q.x, q.y), 0.0);
    q = max(q, 0.0);
    vec2 powered = pow(q, vec2(power));
    float norm = pow(powered.x + powered.y, 1.0 / power);
    vec2 unit = q / max(norm, 0.0001);
    float gradient = length(pow(unit, vec2(power - 1.0)));
    // Normalize near the contour; smoothly use the unnormalized distance inside.
    // A vanishing interior gradient must not amplify glass bevel/refraction depth.
    float blend = smoothstep(0.25, 0.75, norm / extent);
    float scale = mix(1.0, 1.0 / max(gradient, 0.70710678), blend);
    return (norm - extent) * scale + inside;
}
void main() {
    vec4 color = texture2D(u_Texture, uv);
    float mask = 1.0;
    if (u_Radius > 0.0) {
        vec2 halfSize = u_Bounds.zw * 0.5;
        float d = sdRoundBox(v_Position - u_Bounds.xy - halfSize, halfSize, u_Radius);
        float aa = max(fwidth(d), 0.0001);
        mask = 1.0 - smoothstep(-aa, 0.0, d);
    }
    gl_FragColor = vec4(color.rgb, color.a * mask * u_Opacity);
}