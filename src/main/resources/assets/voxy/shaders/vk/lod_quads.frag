#version 460 core

//A discard anywhere in the shader would otherwise disable early depth testing for the whole
//pipeline, and every hidden LOD quad behind a nearer one would run this shader. Forcing early tests
//costs us the usual guarantee - a discarded fragment has already written depth - but that is exactly
//harmless here: the only fragments discarded are ones vanilla terrain already covers, where nothing
//of ours should be visible at any depth anyway.
layout(early_fragment_tests) in;

layout(location = 0) in vec3 vColor;
layout(location = 0) out vec4 fragColor;

//Minecraft's depth buffer, sampled rather than depth tested against. LOD terrain renders with a far
//plane far beyond vanilla's, so the two sets of depth values are on different scales and comparing
//them numerically is meaningless. What survives the difference is whether vanilla drew anything
//here at all: where it did, it owns the pixel and we stay out of the way. This is voxy's OpenGL
//path's stencil mask, done as a texture read because Minecraft's Vulkan depth target has no stencil.
layout(binding = 2) uniform sampler2D vanillaDepth;

//Must stay byte for byte identical to the block in lod_quads.vert
layout(push_constant) uniform PushConstants {
    mat4 viewProj;
    vec4 params;
    vec4 depthParams;//x = the depth value meaning 'Minecraft drew nothing on this pixel'
} pc;

void main() {
    //Exact compare against the clear value, the same test voxy's OpenGL path makes. Under reverse Z
    //that value is 0.0 and anything drawn is strictly greater, so there is no near miss to tolerate.
    if (texelFetch(vanillaDepth, ivec2(gl_FragCoord.xy), 0).r != pc.depthParams.x) {
        discard;
    }
    fragColor = vec4(vColor, 1.0);
}
