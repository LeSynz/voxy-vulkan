#version 460 core

//A discard anywhere in the shader would otherwise disable early depth testing for the whole
//pipeline, and every hidden LOD quad behind a nearer one would run this shader. Forcing early tests
//costs us the usual guarantee - a discarded fragment has already written depth - but that is exactly
//harmless here: the only fragments discarded are ones vanilla terrain already covers, where nothing
//of ours should be visible at any depth anyway.
layout(early_fragment_tests) in;

layout(location = 0) in vec3 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 3) in flat vec3 vAtlasBase;

layout(location = 0) out vec4 fragColor;

//Minecraft's depth buffer, sampled rather than depth tested against. LOD terrain renders with a far
//plane far beyond vanilla's, so the two sets of depth values are on different scales and comparing
//them numerically is meaningless. What survives the difference is whether vanilla drew anything
//here at all: where it did, it owns the pixel and we stay out of the way. This is voxy's OpenGL
//path's stencil mask, done as a texture read because Minecraft's Vulkan depth target has no stencil.
layout(binding = 5) uniform sampler2D vanillaDepth;

//The baked block model atlas: 256x256 model tiles, each holding six faces as a 3 by 2 arrangement
layout(binding = 7) uniform sampler2D modelAtlas;

//Must stay byte for byte identical to the block in lod_quads.vert
layout(push_constant) uniform PushConstants {
    mat4 viewProj;
    vec4 params;
    vec4 depthParams;//x = 'Minecraft drew nothing' depth; y = biome count; z = output alpha
} pc;

//One face's slot in the atlas, in normalised coordinates
const vec2 FACE_SCALE = 1.0 / (vec2(3.0, 2.0) * 256.0);

void main() {
    //Exact compare against the clear value, the same test voxy's OpenGL path makes. Under reverse Z
    //that value is 0.0 and anything drawn is strictly greater, so there is no near miss to tolerate.
    if (texelFetch(vanillaDepth, ivec2(gl_FragCoord.xy), 0).r != pc.depthParams.x) {
        discard;
    }

    if (vAtlasBase.z < 0.0) {
        //No baked model for this block yet, so the vertex stage already resolved a flat colour
        fragColor = vec4(vColor, pc.depthParams.z);
        return;
    }

    //A merged quad spans several blocks, so the texture repeats once per block along it
    vec2 withinBlock = fract(vUv);
    vec2 texPos = vAtlasBase.xy + withinBlock * FACE_SCALE;
    //Derivatives taken from the atlas coordinate rather than the block coordinate, so mip selection
    //accounts for the repeat and distant terrain does not alias into noise
    vec2 atlasUv = vUv * FACE_SCALE;
    vec4 sampled = textureGrad(modelAtlas, texPos, dFdx(atlasUv), dFdy(atlasUv));

    //Cut out the gaps in things like leaves and grass. Sampled without mips so that a blurred mip
    //cannot fade a solid pixel below the threshold and punch holes in distant foliage.
    if (textureLod(modelAtlas, texPos, 0.0).a <= 0.1) {
        discard;
    }

    fragColor = vec4(sampled.rgb * vColor, pc.depthParams.z);
}
