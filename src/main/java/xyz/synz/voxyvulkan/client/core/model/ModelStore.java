package xyz.synz.voxyvulkan.client.core.model;

import xyz.synz.voxyvulkan.client.core.RenderResourceReuse;
import xyz.synz.voxyvulkan.client.core.gl.GlBuffer;
import xyz.synz.voxyvulkan.client.core.gl.GlTexture;
import xyz.synz.voxyvulkan.common.util.GlobalCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.lang.ref.Cleaner;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore implements IModelSink {
    public static final int MODEL_SIZE = 64;
    private Cleaner.Cleanable ref;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        var tex = this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
        this.ref = GlobalCleaner.CLEANER.register(this, ()->RenderResourceReuse.giveBackModelStoreTextureAtlas(tex));

        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .maxMipLevel;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
    }


    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.ref.clean();
        glDeleteSamplers(this.blockSampler);
    }


    @Override
    public void beginUploads() {
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    @Override
    public void flushUploads() {
        xyz.synz.voxyvulkan.client.core.rendering.util.UploadStream.INSTANCE.commit();
    }

    @Override
    public void uploadModelField(int modelId, int byteOffset, int value) {
        org.lwjgl.system.MemoryUtil.memPutInt(
                xyz.synz.voxyvulkan.client.core.rendering.util.UploadStream.INSTANCE
                        .upload(this.modelBuffer, (long) modelId * MODEL_SIZE + byteOffset, 4), value);
    }

    @Override
    public void uploadModel(int modelId, xyz.synz.voxyvulkan.common.util.MemoryBuffer data) {
        data.cpyTo(xyz.synz.voxyvulkan.client.core.rendering.util.UploadStream.INSTANCE
                .upload(this.modelBuffer, (long) modelId * MODEL_SIZE, MODEL_SIZE));
    }

    @Override
    public void uploadBiomeColours(int firstIndex, xyz.synz.voxyvulkan.common.util.MemoryBuffer data) {
        data.cpyTo(xyz.synz.voxyvulkan.client.core.rendering.util.UploadStream.INSTANCE
                .upload(this.modelColourBuffer, firstIndex * 4L, data.size));
    }

    @Override
    public void uploadModelTexture(int modelId, long address) {
        int x = (modelId & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 3;
        int y = ((modelId >> 8) & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 2;
        long source = address;
        for (int lvl = 0; lvl < ModelFactory.LAYERS; lvl++) {
            org.lwjgl.opengl.ARBDirectStateAccess.nglTextureSubImage2D(this.textures.id, lvl,
                    x >> lvl, y >> lvl,
                    (ModelFactory.MODEL_TEXTURE_SIZE * 3) >> lvl,
                    (ModelFactory.MODEL_TEXTURE_SIZE * 2) >> lvl,
                    org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, source);
            source += (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE * 3 * 2 * 4) >> (lvl << 1);
        }
    }

    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }
}
