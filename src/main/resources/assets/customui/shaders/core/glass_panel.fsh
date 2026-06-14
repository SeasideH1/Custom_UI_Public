#version 150

uniform sampler2D Sampler0;
uniform vec4 PanelRect;
uniform vec2 ScreenSize;
uniform float CornerRadius;
uniform float BorderWidth;
uniform vec4 TintColor;
uniform vec4 BorderColor;
uniform float PanelAlpha;

out vec4 fragColor;

void main() {
    vec2 p = gl_FragCoord.xy;
    vec2 center = (PanelRect.xy + PanelRect.zw) * 0.5;
    vec2 halfSize = max((PanelRect.zw - PanelRect.xy) * 0.5 - vec2(CornerRadius), vec2(0.0));
    vec2 q = abs(p - center) - halfSize;
    float dist = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - CornerRadius;

    // Anti-aliased rounded-rect mask
    float shape = 1.0 - smoothstep(-0.75, 0.75, dist);
    if (shape <= 0.001) {
        discard;
    }

    vec3 backdrop = texture(Sampler0, p / ScreenSize).rgb;
    vec3 color = mix(backdrop, TintColor.rgb, TintColor.a);

    // Thin light border just inside the edge
    float border = (1.0 - smoothstep(0.0, BorderWidth + 0.75, abs(dist + BorderWidth))) * BorderColor.a;
    color = mix(color, BorderColor.rgb, border);

    fragColor = vec4(color, shape * PanelAlpha);
}
