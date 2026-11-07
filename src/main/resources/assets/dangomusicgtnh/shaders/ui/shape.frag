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

float sdRoundBox(vec2 p, vec2 b, float r) {
    r = clamp(r, 0.0, min(b.x, b.y));
    // Keep genuinely circular controls and avatar masks circular.
    if (abs(b.x - b.y) < 0.001 && r >= min(b.x, b.y) - 0.001) return length(p) - r;
    vec2 q = abs(p) - b + r;
    float inside = min(max(q.x, q.y), 0.0);
    q = max(q, 0.0);
    vec2 square = q * q;
    float norm = sqrt(sqrt(dot(square, square)));
    if (r == 0.0) return length(q) + inside;
    vec2 unit = q / max(norm, 0.0001);
    float gradient = length(unit * unit * unit);
    // Normalize near the contour; smoothly use the unnormalized distance inside.
    // A vanishing interior gradient must not amplify glass bevel/refraction depth.
    float blend = smoothstep(0.25, 0.75, norm / r);
    float scale = mix(1.0, 1.0 / max(gradient, 0.70710678), blend);
    return (norm - r) * scale + inside;
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
