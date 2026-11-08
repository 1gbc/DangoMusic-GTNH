#version 120
uniform vec4 u_Bounds;
uniform vec4 u_Color;
uniform float u_Opacity;
uniform float u_StrokeWidth;
uniform int u_Filled;
uniform int u_SegmentCount;
uniform vec4 u_Segments[96];
varying vec2 v_Position;
void main() {
    vec2 p=(v_Position-u_Bounds.xy)/u_Bounds.zw*24.0;
    float distance=10000.0;
    bool inside=false;
    for(int i=0;i<96;i++) {
        if(i>=u_SegmentCount) break;
        vec2 a=u_Segments[i].xy;
        vec2 b=u_Segments[i].zw;
        vec2 edge=b-a;
        float t=clamp(dot(p-a,edge)/max(dot(edge,edge),0.000001),0.0,1.0);
        distance=min(distance,length(p-a-edge*t));
        if(u_Filled!=0 && ((a.y>p.y)!=(b.y>p.y))) {
            float x=a.x+(p.y-a.y)*edge.x/edge.y;
            if(p.x<x) inside=!inside;
        }
    }
    // One coverage mask, so joins have exactly the same opacity as straight strokes.
    float d=u_Filled!=0 ? (inside ? -distance : distance) : distance-u_StrokeWidth*0.5;
    float aa=max(fwidth(d),0.0001);
    float alpha=1.0-smoothstep(-aa*0.5,aa*0.5,d);
    gl_FragColor=vec4(u_Color.rgb,u_Color.a*u_Opacity*alpha);
}
