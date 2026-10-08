#version 330
#extension GL_ARB_separate_shader_objects : require

// Doorway quads (stencil mask, backdrop, depth seal, fallback shimmer). Positions are camera-relative.
// FAR_DEPTH pushes the quad onto the far plane (reverse-Z: clip z = 0), which clears depth inside the doorway.

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec3 viewPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    #ifdef FAR_DEPTH
    gl_Position.z = 0.0;
    #endif
    vertexColor = Color;
    viewPos = Position;
}
