#version 460 core
#extension GL_ARB_gpu_shader_int64 : require

//Renders meshed LOD sections. Quads are voxy's packed 64 bit format in a storage buffer, fetched by
//gl_VertexIndex >> 2 with the four corners from the low two bits - no vertex buffer, same as the
//OpenGL path. One draw per section, with the section origin supplied as a push constant.
#define QUAD_DATA_USE_64_BIT
#import <voxy:lod/quad_format.glsl>

layout(std430, binding = 0) restrict readonly buffer QuadBuffer {
    uint64_t quadData[];
};

//One colour per block id, taken from Minecraft's map colours, packed ABGR
layout(std430, binding = 1) restrict readonly buffer ColourBuffer {
    uint blockColours[];
};

//Where a block state's per biome colours start, or -1 when the state is not tinted at all
layout(std430, binding = 2) restrict readonly buffer TintOffsetBuffer {
    int stateTintOffset[];
};

//One colour per (tinted state, biome), packed ABGR
layout(std430, binding = 3) restrict readonly buffer TintColourBuffer {
    uint tintColours[];
};

//Block id to baked model id, or -1 where the model has not been baked yet
layout(std430, binding = 4) restrict readonly buffer ModelIdBuffer {
    int modelIds[];
};

//Minecraft's own lightmap, so block and sky light read the same as they do up close
layout(binding = 6) uniform sampler2D lightmap;

//Must stay byte for byte identical to the block in lod_quads.frag - one push constant range is
//shared by both stages, so a mismatch silently misreads whichever stage disagrees
layout(push_constant) uniform PushConstants {
    mat4 viewProj;
    vec4 params;//xyz = section origin, camera relative; w = scale of one voxel at this LOD level
    vec4 depthParams;//x = the depth value meaning 'Minecraft drew nothing'; y = biome count
} pc;

layout(location = 0) out vec3 vColor;
//How far across the quad this corner is, in blocks, so the texture repeats once per block of a
//merged quad rather than being stretched across the whole run
layout(location = 1) out vec2 vUv;
layout(location = 2) out flat ivec2 vQuadSize;
//Where this model's face sits in the atlas, and -1 in z when there is no baked model to sample
layout(location = 3) out flat vec3 vAtlasBase;

//A face is axis*2 + direction, so the quad lies in the plane of the two axes that are not `axis`
vec3 planeOffset(uint axis, vec2 uv) {
    if (axis == 0u) {
        return vec3(0.0, uv.x, uv.y);
    } else if (axis == 1u) {
        return vec3(uv.x, 0.0, uv.y);
    }
    return vec3(uv.x, uv.y, 0.0);
}

vec3 unpackABGR(uint packed) {
    return vec3(float(packed & 255u), float((packed >> 8) & 255u), float((packed >> 16) & 255u)) / 255.0;
}

vec3 stateColour(uint stateId) {
    if (stateId >= uint(blockColours.length())) {
        return vec3(0.5);
    }
    return unpackABGR(blockColours[stateId]);
}

//The colour a tinted block should actually be in this biome, or -1 alpha meaning 'not tinted'.
//
//This replaces the base colour rather than multiplying it. Minecraft tints a greyscale texture, but
//what we have to start from is the block's map colour, which already has the tint baked in - grass
//is green before any biome is applied. Multiplying would apply the green twice and come out muddy,
//whereas the biome colour on its own is exactly the answer for the cases tinting exists for.
//The table is resolved on the CPU because a LOD quad spans many blocks and has no world to ask.
vec4 biomeTint(uint stateId, uint biomeId) {
    if (stateId >= uint(stateTintOffset.length())) {
        return vec4(0.0, 0.0, 0.0, -1.0);
    }
    int offset = stateTintOffset[stateId];
    if (offset < 0) {
        return vec4(0.0, 0.0, 0.0, -1.0);//Not a tinted state, which is nearly all of them
    }
    uint biomeCount = uint(max(pc.depthParams.y, 1.0));
    uint index = uint(offset) + min(biomeId, biomeCount - 1u);
    if (index >= uint(tintColours.length())) {
        return vec4(0.0, 0.0, 0.0, -1.0);
    }
    return vec4(unpackABGR(tintColours[index]), 1.0);
}

//Index zero is deliberately read as fully lit rather than as pitch black. It is what an unpopulated
//light value looks like, and getting that wrong the other way turns the entire world black - a
//slightly over-bright cave is a much better failure than a world that renders as nothing.
vec3 lightFor(uint lightId) {
    if (lightId == 0u) {
        return vec3(1.0);
    }
    vec2 base = vec2(float((lightId >> 4u) & 15u), float(lightId & 15u)) / 15.0;
    vec2 uv = clamp(base * (15.0 / 16.0) + (0.5 / 16.0), vec2(8.0 / 256.0), vec2(248.0 / 256.0));
    return textureLod(lightmap, uv, 0.0).rgb;
}

void main() {
    uint64_t quad = quadData[uint(gl_VertexIndex) >> 2];
    uint corner = uint(gl_VertexIndex) & 3u;

    vec3 base = extractPos(quad);
    ivec2 size = extractSize(quad);
    uint face = extractFace(quad);
    uint axis = face >> 1u;

    //Positive facing quads sit on the far side of the voxel
    vec3 origin = base;
    origin[axis] += float(face & 1u);

    vec2 cornerUv = vec2((corner == 1u || corner == 2u) ? 1.0 : 0.0,
                         (corner == 2u || corner == 3u) ? 1.0 : 0.0);
    //Winding is currently inconsistent between axes - the corner order naturally gives a +X normal
    //on axis 0 and +Z on axis 2, but -Y on axis 1 - so backface culling is disabled on the pipeline
    //rather than culling the wrong half. Fixing this properly means making winding uniform here AND
    //flipping frontFace, because Minecraft's Vulkan projection also flips Y in clip space. Doing
    //only one of those two culls everything or nothing.
    vec3 local = origin + planeOffset(axis, cornerUv * vec2(size));

    vec3 world = pc.params.xyz + local * pc.params.w;
    gl_Position = pc.viewProj * vec4(world, 1.0);

    //Flat directional shading so the geometry reads as solid rather than a colour field. This is
    //separate from the lightmap, exactly as it is in vanilla - one is which way the face points,
    //the other is how much light reaches it.
    float shade = (axis == 1u) ? ((face & 1u) == 1u ? 1.0 : 0.5) : (axis == 0u ? 0.8 : 0.65);

    uint stateId = extractStateId(quad);
    vec4 tint = biomeTint(stateId, extractBiomeId(quad));
    //With a real texture the tint multiplies it, the way Minecraft tints a greyscale sprite. With
    //only a map colour to fall back on the tint replaces it, because that colour already has the
    //tint baked in and applying it twice comes out muddy.
    bool tinted = tint.a >= 0.0;

    vUv = cornerUv * vec2(size);
    vQuadSize = size;

    int modelId = stateId < uint(modelIds.length()) ? modelIds[stateId] : -1;
    if (modelId < 0) {
        vAtlasBase = vec3(0.0, 0.0, -1.0);
        vColor = (tinted ? tint.rgb : stateColour(stateId)) * lightFor(extractLightId(quad)) * shade;
    } else {
        //A model owns one tile of a 256 by 256 grid; inside it the six faces sit in a 3 by 2 block
        vec2 modelUv = vec2(uint(modelId) & 0xFFu, (uint(modelId) >> 8) & 0xFFu) * (1.0 / 256.0);
        vec2 faceUv = vec2(face >> 1u, face & 1u) * (1.0 / (vec2(3.0, 2.0) * 256.0));
        vAtlasBase = vec3(modelUv + faceUv, 1.0);
        vColor = (tinted ? tint.rgb : vec3(1.0)) * lightFor(extractLightId(quad)) * shade;
    }
}
