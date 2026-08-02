package xyz.synz.voxyvulkan.common.config.section;

import xyz.synz.voxyvulkan.common.config.IMappingStorage;
import xyz.synz.voxyvulkan.common.config.IStoredSectionPositionIterator;
import xyz.synz.voxyvulkan.common.world.WorldSection;

public abstract class SectionStorage implements IMappingStorage, IStoredSectionPositionIterator {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);
}
