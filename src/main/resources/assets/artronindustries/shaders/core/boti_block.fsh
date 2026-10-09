#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in vec3 boxPos;

layout(location = 0) out vec4 fragColor;

void main() {
    // The box starts at the block layer the far doorway plane passes through; anything behind the plane is cut away.
    if (ModelOffset.x * boxPos.x + ModelOffset.y * boxPos.z + ModelOffset.z < 0.0) {
        discard;
    }
    #ifdef DITHER_FADE
    // Fading out (the arrival cover): drop a growing share of pixels in a 4x4 ordered pattern instead of blending, so
    // opaque geometry needs no sorting, never shows its inner faces and keeps writing depth where it is still drawn.
    const float bayer[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
    ivec2 cell = ivec2(gl_FragCoord.xy) & 3;
    if (ColorModulator.a < (bayer[cell.y * 4 + cell.x] + 0.5) / 16.0) {
        discard;
    }
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * vec4(ColorModulator.rgb, 1.0);
    #else
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    #endif
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
