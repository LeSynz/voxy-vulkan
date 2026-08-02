#version 460 core
#extension GL_ARB_gpu_shader_int64 : require

//Renders quads straight out of voxy's packed quad format, the same 64 bit layout the real LOD
//geometry uses, decoded with voxy's own quad_format.glsl. Geometry is GPU driven: there is no
//vertex buffer, quads are fetched from an SSBO indexed by gl_VertexIndex >> 2 and the four corners
//come from the low two bits, exactly like the OpenGL path.
#define QUAD_DATA_USE_64_BIT
#import <voxy:lod/quad_format.glsl>

layout(std430, binding = 0) restrict readonly buffer QuadBuffer {
    uint64_t quadData[];
};

layout(push_constant) uniform PushConstants {
    mat4 viewProj;
    vec4 params;//xyz = section origin in camera relative space, w = unused
} pc;

layout(location = 0) out vec3 vColor;

//Faces are encoded as axis*2 + direction, so the quad plane depends on face >> 1
vec3 planeOffset(uint axis, vec2 uv) {
    if (axis == 0u) {
        return vec3(0.0, uv.y, uv.x);
    } else if (axis == 1u) {
        return vec3(uv.x, 0.0, uv.y);
    }
    return vec3(uv.x, uv.y, 0.0);
}

void main() {
    uint64_t quad = quadData[uint(gl_VertexIndex) >> 2];
    uint corner = uint(gl_VertexIndex) & 3u;

    vec3 base = extractPos(quad);
    ivec2 size = extractSize(quad);
    uint face = extractFace(quad);
    uint axis = face >> 1u;

    //Quad corners in winding order
    vec2 cornerUv = vec2((corner == 1u || corner == 2u) ? 1.0 : 0.0,
                         (corner == 2u || corner == 3u) ? 1.0 : 0.0);
    vec3 position = base + planeOffset(axis, cornerUv * vec2(size));

    gl_Position = pc.viewProj * vec4(pc.params.xyz + position, 1.0);

    //Shade by face direction and state id so distinct quads are visually separable
    float shade = 0.55 + 0.15 * float(axis) + 0.15 * float(face & 1u);
    uint stateId = extractStateId(quad);
    vColor = shade * vec3(
        0.35 + 0.65 * float((stateId >> 0u) & 3u) / 3.0,
        0.35 + 0.65 * float((stateId >> 2u) & 3u) / 3.0,
        0.35 + 0.65 * float((stateId >> 4u) & 3u) / 3.0);
}
