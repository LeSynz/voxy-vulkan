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

//Must stay byte for byte identical to the block in lod_quads.frag - one push constant range is
//shared by both stages, so a mismatch silently misreads whichever stage disagrees
layout(push_constant) uniform PushConstants {
    mat4 viewProj;
    vec4 params;//xyz = section origin, camera relative; w = scale of one voxel at this LOD level
    vec4 depthParams;//x = the depth value meaning 'Minecraft drew nothing on this pixel'
} pc;

layout(location = 0) out vec3 vColor;

//A face is axis*2 + direction, so the quad lies in the plane of the two axes that are not `axis`
vec3 planeOffset(uint axis, vec2 uv) {
    if (axis == 0u) {
        return vec3(0.0, uv.x, uv.y);
    } else if (axis == 1u) {
        return vec3(uv.x, 0.0, uv.y);
    }
    return vec3(uv.x, uv.y, 0.0);
}

vec3 stateColour(uint stateId) {
    if (stateId >= uint(blockColours.length())) {
        return vec3(0.5);
    }
    uint packed = blockColours[stateId];
    return vec3(float(packed & 255u), float((packed >> 8) & 255u), float((packed >> 16) & 255u)) / 255.0;
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

    //Flat directional shading so the geometry reads as solid rather than a colour field
    float shade = (axis == 1u) ? ((face & 1u) == 1u ? 1.0 : 0.5) : (axis == 0u ? 0.8 : 0.65);
    vColor = stateColour(extractStateId(quad)) * shade;
}
