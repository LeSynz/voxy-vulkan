package xyz.synz.voxyvulkan.client.core.vk;

import org.lwjgl.util.shaderc.Shaderc;
import xyz.synz.voxyvulkan.client.core.gl.shader.ShaderType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles the Vulkan shaders straight from the source tree, without Minecraft.
 * <p>
 * Exists because a GLSL mistake otherwise only surfaces when the game reaches the first frame that
 * builds a pipeline - by which point a whole launch has been spent, the renderer disables itself,
 * and the only evidence is one line of shaderc output. A redefined local cost exactly that once.
 * <p>
 * Run with {@code ./gradlew validateShaders}. It reimplements {@code #import} resolution rather than
 * using {@link xyz.synz.voxyvulkan.client.core.gl.shader.ShaderLoader}, because that one reads
 * through Minecraft's resource manager and there is no Minecraft here.
 */
public final class VkShaderValidator {
    private VkShaderValidator() {}

    private static final String SHADER_ROOT = "src/main/resources/assets/voxy/shaders";

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : SHADER_ROOT);
        Path vkDir = root.resolve("vk");
        if (!Files.isDirectory(vkDir)) {
            System.err.println("No shader directory at " + vkDir.toAbsolutePath());
            System.exit(1);
        }

        var failures = new ArrayList<String>();
        int checked = 0;
        try (var stream = Files.list(vkDir)) {
            for (Path file : stream.sorted().toList()) {
                var type = typeOf(file.getFileName().toString());
                if (type == null) {
                    continue;
                }
                checked++;
                String name = "voxy:vk/" + file.getFileName();
                try {
                    String source = resolveImports(file, root, new LinkedHashSet<>());
                    VkShaderCompiler.compileSource(source, name, type);
                    System.out.println("  ok   " + name);
                } catch (Throwable t) {
                    failures.add(name + ": " + t.getMessage());
                    System.out.println("  FAIL " + name);
                }
            }
        }

        System.out.println("Checked " + checked + " Vulkan shaders, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            for (String failure : failures) {
                System.err.println();
                System.err.println(failure);
            }
            System.exit(1);
        }
    }

    /**
     * Inlines {@code #import <voxy:path>} the way voxy's loader does, including its trick of using a
     * second import of the same file to undo its defines - so a file genuinely can appear twice and
     * repeats must not be skipped.
     */
    private static String resolveImports(Path file, Path root, Set<Path> stack) throws IOException {
        if (!stack.add(file)) {
            throw new IOException("Circular import reaching " + file);
        }
        try {
            var out = new StringBuilder();
            for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n", -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#import")) {
                    int open = trimmed.indexOf('<');
                    int close = trimmed.lastIndexOf('>');
                    if (open == -1 || close == -1) {
                        throw new IOException("Malformed import in " + file + ": " + trimmed);
                    }
                    String id = trimmed.substring(open + 1, close);
                    int colon = id.indexOf(':');
                    String path = colon == -1 ? id : id.substring(colon + 1);
                    Path target = root.resolve(path);
                    if (!Files.isRegularFile(target)) {
                        throw new IOException("Import not found: " + id + " (looked at " + target + ")");
                    }
                    out.append(resolveImports(target, root, new LinkedHashSet<>(stack))).append('\n');
                } else {
                    out.append(line).append('\n');
                }
            }
            return out.toString();
        } finally {
            stack.remove(file);
        }
    }

    private static ShaderType typeOf(String fileName) {
        if (fileName.endsWith(".vert")) return ShaderType.VERTEX;
        if (fileName.endsWith(".frag")) return ShaderType.FRAGMENT;
        if (fileName.endsWith(".comp")) return ShaderType.COMPUTE;
        return null;
    }

    static {
        //Touch shaderc so a missing native fails here with a clear message rather than mid compile
        List<String> ignored = new ArrayList<>();
        ignored.add(String.valueOf(Shaderc.shaderc_vertex_shader));
    }
}
