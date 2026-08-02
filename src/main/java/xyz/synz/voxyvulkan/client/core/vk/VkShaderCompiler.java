package xyz.synz.voxyvulkan.client.core.vk;

import xyz.synz.voxyvulkan.client.core.gl.shader.ShaderLoader;
import xyz.synz.voxyvulkan.client.core.gl.shader.ShaderType;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Compiles voxy's GLSL to SPIR-V.
 * <p>
 * Reuses {@link ShaderLoader}, so the existing {@code #import <voxy:...>} directives keep working
 * unchanged - only the backend compiler differs. Note that GL and Vulkan GLSL are not the same
 * dialect: bindings must be explicit and unique per descriptor set, {@code gl_VertexID} becomes
 * {@code gl_VertexIndex}, and there is no default uniform block, so ported shaders need push
 * constants or a uniform buffer instead of loose uniforms.
 */
public class VkShaderCompiler {

    public static ByteBuffer compile(String identifier, ShaderType type) {
        return compile(identifier, type, List.of());
    }

    /**
     * @param identifier voxy shader id, e.g. {@code voxy:util/set.comp}
     * @param defines    preprocessor defines, either {@code NAME} or {@code NAME value}
     * @return SPIR-V, caller owned - free with {@link MemoryUtil#memFree}
     */
    public static ByteBuffer compile(String identifier, ShaderType type, List<String> defines) {
        String source = injectDefines(ShaderLoader.parse(identifier), defines);
        return compileSource(source, identifier, type);
    }

    public static ByteBuffer compileSource(String source, String name, ShaderType type) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == MemoryUtil.NULL) {
            throw new IllegalStateException("Failed to initialize shaderc");
        }
        try {
            long result = Shaderc.shaderc_compile_into_spv(compiler, source, toShadercKind(type),
                    name, "main", MemoryUtil.NULL);
            if (result == MemoryUtil.NULL) {
                throw new IllegalStateException("shaderc returned no result for " + name);
            }
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    throw new IllegalStateException("Failed to compile " + name + ": "
                            + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
                MemoryUtil.memCopy(bytes, copy);
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /** Defines have to land after the #version line or the compiler rejects them. */
    private static String injectDefines(String source, List<String> defines) {
        if (defines.isEmpty()) {
            return source;
        }
        var builder = new StringBuilder();
        for (var define : defines) {
            builder.append("#define ").append(define).append('\n');
        }

        int versionEnd = source.indexOf('\n');
        if (versionEnd == -1 || !source.startsWith("#version")) {
            return builder + source;
        }
        return source.substring(0, versionEnd + 1) + builder + source.substring(versionEnd + 1);
    }

    private static int toShadercKind(ShaderType type) {
        return switch (type) {
            case VERTEX -> Shaderc.shaderc_vertex_shader;
            case FRAGMENT -> Shaderc.shaderc_fragment_shader;
            case COMPUTE -> Shaderc.shaderc_compute_shader;
            case MESH, TASK -> throw new UnsupportedOperationException(
                    "NV mesh/task shaders have no direct Vulkan equivalent here, the NV path needs replacing: " + type);
        };
    }
}
