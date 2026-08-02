package xyz.synz.voxyvulkan.client;

import xyz.synz.voxyvulkan.client.core.IVoxyRenderSystemHolder;
import xyz.synz.voxyvulkan.client.core.VoxyRenderSystem;
import xyz.synz.voxyvulkan.commonImpl.VoxyCommon;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class VoxyDebugScreenEntry implements DebugScreenEntry {
    @Override
    public void display(DebugScreenDisplayer lines, @Nullable Level world, @Nullable LevelChunk clientChunk, @Nullable LevelChunk chunk) {
        if (!VoxyCommon.isAvailable()) {
            return;
        }

        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return;
        }

        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();

        //lines.addLineToSection();
        List<String> instanceLines = new ArrayList<>();
        instance.addDebug(instanceLines);
        lines.addToGroup(Identifier.fromNamespaceAndPath("voxy", "instance_debug"), instanceLines);

        List<String> renderLines = new ArrayList<>();
        if (vrs != null) {
            vrs.addDebugInfo(renderLines);
        } else {
            //On Vulkan the OpenGL render system is never created, so its debug lines never appear.
            //The Vulkan renderer keeps the same kind of numbers and they are just as worth seeing.
            var lod = xyz.synz.voxyvulkan.client.core.vk.VkLodRenderer.getActive();
            if (lod != null) {
                lod.addDebugInfo(renderLines);
            }
        }
        if (!renderLines.isEmpty()) {
            lines.addToGroup(Identifier.fromNamespaceAndPath("voxy", "render_debug"), renderLines);
        }
    }


}
