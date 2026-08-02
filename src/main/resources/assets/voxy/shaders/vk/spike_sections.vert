#version 460 core

//Draws a lattice of small cubes on Minecraft's 16 block section grid, in camera relative space,
//to prove world anchoring and depth interaction before real LOD geometry is wired up.

layout(push_constant) uniform PushConstants {
    mat4 viewProj;
    vec4 params;//xyz = lattice base in camera relative space, w = section spacing
} pc;

layout(location = 0) out vec3 vColor;

const int GRID_X = 9;
const int GRID_Y = 5;

//Unit cube centred on the origin, 12 triangles
const vec3 CUBE[36] = vec3[36](
    vec3(-0.5,-0.5,-0.5), vec3( 0.5,-0.5,-0.5), vec3( 0.5, 0.5,-0.5),
    vec3(-0.5,-0.5,-0.5), vec3( 0.5, 0.5,-0.5), vec3(-0.5, 0.5,-0.5),
    vec3(-0.5,-0.5, 0.5), vec3( 0.5, 0.5, 0.5), vec3( 0.5,-0.5, 0.5),
    vec3(-0.5,-0.5, 0.5), vec3(-0.5, 0.5, 0.5), vec3( 0.5, 0.5, 0.5),
    vec3(-0.5,-0.5,-0.5), vec3(-0.5, 0.5, 0.5), vec3(-0.5,-0.5, 0.5),
    vec3(-0.5,-0.5,-0.5), vec3(-0.5, 0.5,-0.5), vec3(-0.5, 0.5, 0.5),
    vec3( 0.5,-0.5,-0.5), vec3( 0.5,-0.5, 0.5), vec3( 0.5, 0.5, 0.5),
    vec3( 0.5,-0.5,-0.5), vec3( 0.5, 0.5, 0.5), vec3( 0.5, 0.5,-0.5),
    vec3(-0.5, 0.5,-0.5), vec3( 0.5, 0.5,-0.5), vec3( 0.5, 0.5, 0.5),
    vec3(-0.5, 0.5,-0.5), vec3( 0.5, 0.5, 0.5), vec3(-0.5, 0.5, 0.5),
    vec3(-0.5,-0.5,-0.5), vec3( 0.5,-0.5, 0.5), vec3( 0.5,-0.5,-0.5),
    vec3(-0.5,-0.5,-0.5), vec3(-0.5,-0.5, 0.5), vec3( 0.5,-0.5, 0.5)
);

void main() {
    int i = gl_InstanceIndex;
    ivec3 cell = ivec3(i % GRID_X, (i / GRID_X) % GRID_Y, i / (GRID_X * GRID_Y));

    vec3 centre = pc.params.xyz + vec3(cell) * pc.params.w;
    vec3 position = centre + CUBE[gl_VertexIndex] * 2.0;

    gl_Position = pc.viewProj * vec4(position, 1.0);
    //Colour by cell so the lattice structure is obvious
    vColor = vec3(cell) / vec3(GRID_X, GRID_Y, GRID_X);
}
