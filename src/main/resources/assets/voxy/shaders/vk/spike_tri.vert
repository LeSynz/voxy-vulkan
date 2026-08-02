#version 460 core

layout(location = 0) out vec3 vColor;

//Geometry is generated from the vertex index, so no vertex buffer or input state is needed
void main() {
    vec2 positions[3] = vec2[3](
        vec2(-0.6, -0.6),
        vec2( 0.6, -0.6),
        vec2( 0.0,  0.6)
    );
    vec3 colors[3] = vec3[3](
        vec3(1.0, 0.0, 0.0),
        vec3(0.0, 1.0, 0.0),
        vec3(0.0, 0.0, 1.0)
    );
    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    vColor = colors[gl_VertexIndex];
}
