#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:globals.glsl>

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec3 viewPos;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
    #ifdef SHIMMER
    // Fallback doorway: dark surface with slow moving bands (no view rendered).
    float t = GameTime * 1200.0;
    float bands = sin(viewPos.y * 6.0 + t) * 0.5 + 0.5;
    float swirl = sin((viewPos.x + viewPos.z) * 4.0 - t * 0.7) * 0.5 + 0.5;
    color.rgb += vec3(0.05, 0.08, 0.16) * bands * swirl;
    #endif
    fragColor = color;
}
