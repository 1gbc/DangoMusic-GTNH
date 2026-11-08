#version 120

uniform vec4 u_Bounds;// x y width height
uniform float u_Radius;
uniform vec4 u_FillColor;

uniform float u_BorderWidth;
uniform vec4 u_BorderColor;

uniform vec4 u_ShadowColor;
uniform float u_ShadowSize;
uniform vec2 u_ShadowOffset;
// 1 = 光晕只画在形状外面
uniform float u_ShadowOutside;

uniform float u_Opacity;

varying vec2 v_Position;

// 1 = 连续曲率圆角（界面风格“连续圆角”），0 = 经典圆角
uniform float u_Continuous;

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
    vec2 local = v_Position - u_Bounds.xy;
    vec2 center = u_Bounds.zw * 0.5;

    float fillDist = sdRoundBox(local - center, center, u_Radius);
    float aa = fwidth(fillDist);
    float fillAlpha = 1.0 - smoothstep(-aa, 0.0, fillDist);

    vec4 shapeColor = u_FillColor * fillAlpha;
    // 形状（含边框）外缘的距离：光晕只画外面时用
    float shapeDist = fillDist;

    float hasRadius = step(0.1, u_Radius);
    if (u_BorderWidth > 0.0) {
        float expandedDist = fillDist - u_BorderWidth;
        float rectOuterDist = sdRoundBox(local - center, center + vec2(u_BorderWidth), 0.0);
        float outerDist = mix(rectOuterDist, expandedDist, hasRadius);

        shapeDist = outerDist;
        float outerAlpha = 1.0 - smoothstep(-aa, 0.0, outerDist);
        vec4 rawColor = mix(u_BorderColor, u_FillColor, fillAlpha);
        shapeColor = rawColor * outerAlpha;
    }

    vec4 shadowColor = vec4(0.0);
    if (u_ShadowSize > 0.0) {
        vec2 shadowLocal = local - vec2(u_ShadowOffset.x, -u_ShadowOffset.y);

        float shadowDistRound = sdRoundBox(shadowLocal - center, center, u_Radius) - u_BorderWidth;
        float shadowDistRect = sdRoundBox(shadowLocal - center, center + vec2(u_BorderWidth), 0.0);
        float shadowDist = mix(shadowDistRect, shadowDistRound, hasRadius);

        float shadowMask = 1.0 - smoothstep(0.0, u_ShadowSize, shadowDist);
        shadowMask *= mix(1.0, smoothstep(-aa, 0.0, shapeDist), u_ShadowOutside);
        float shadowAlpha = shadowMask * u_ShadowColor.a;
        shadowColor = vec4(u_ShadowColor.rgb * shadowAlpha, shadowAlpha);
    }

    vec4 color = shapeColor + shadowColor * (1.0 - shapeColor.a);
    gl_FragColor = vec4(color.rgb, color.a * u_Opacity);
}
