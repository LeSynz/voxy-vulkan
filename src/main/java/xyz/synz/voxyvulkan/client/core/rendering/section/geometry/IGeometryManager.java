package xyz.synz.voxyvulkan.client.core.rendering.section.geometry;

import xyz.synz.voxyvulkan.client.core.rendering.building.BuiltSection;

import java.util.function.Consumer;

public interface IGeometryManager {
    int uploadSection(BuiltSection section);
    int uploadReplaceSection(int oldId, BuiltSection section);
    void removeSection(int id);

    void downloadAndRemove(int id, Consumer<BuiltSection> callback);
}
