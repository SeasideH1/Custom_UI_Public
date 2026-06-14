#version 150

uniform sampler2D Sampler0;
uniform vec2 BlurDir;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    // 5-tap separable gaussian; BlurDir == (0,0) degenerates to a plain copy
    vec4 sum = texture(Sampler0, texCoord0) * 0.2270270270;
    vec2 off1 = BlurDir * 1.3846153846;
    vec2 off2 = BlurDir * 3.2307692308;
    sum += (texture(Sampler0, texCoord0 + off1) + texture(Sampler0, texCoord0 - off1)) * 0.3162162162;
    sum += (texture(Sampler0, texCoord0 + off2) + texture(Sampler0, texCoord0 - off2)) * 0.0702702703;
    fragColor = vec4(sum.rgb, 1.0);
}
