package xyz.synz.voxyvulkan.client.core.model;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import xyz.synz.voxyvulkan.common.Logger;
import xyz.synz.voxyvulkan.common.world.other.Mapper;

import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking

    private final IModelSink storage;
    public final ModelFactory factory;
    private final Mapper mapper;

    private final Thread processingThread;
    private volatile boolean isRunning = true;
    private volatile Throwable processingThreadException;

    public ModelBakerySubsystem(Mapper mapper) {
        this(mapper, new ModelStore());
    }

    /**
     * @param storage where finished bakes go. The OpenGL renderer passes a {@link ModelStore}; the
     *                Vulkan one passes its own, because {@code ModelStore}'s constructor allocates
     *                OpenGL objects and there is no OpenGL context to allocate them in.
     */
    public ModelBakerySubsystem(Mapper mapper, IModelSink storage) {
        this.mapper = mapper;
        this.storage = storage;
        this.factory = new ModelFactory(mapper, this.storage);
        this.processingThread = new Thread(()->{//TODO replace this with something good/integrate it into the async processor so that we just have less threads overall
            while (this.isRunning) {
                while (this.factory.processAllThings());
                LockSupport.park();
            }
        }, "Model factory processor");
        this.processingThread.setUncaughtExceptionHandler((t,e)->{
            this.isRunning = false;
            if (e == null) {
                e = new RuntimeException("unhandled excpetion not added");
            }
            this.processingThreadException = e;
        });
        this.processingThread.start();
    }

    public void tick(long totalBudget) {
        if (this.processingThreadException != null) {
            throw new RuntimeException(this.processingThreadException);
        }
        this.factory.processUploads();
    }

    public void shutdown() {
        this.isRunning = false;
        LockSupport.unpark(this.processingThread);
        try {
            this.processingThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        this.factory.free();
        if (this.storage instanceof ModelStore store) {
            store.free();
        }
    }

    //This is on this side only and done like this as only worker threads call this code
    private final ReentrantLock seenIdsLock = new ReentrantLock();
    private final ReentrantLock enqueueLock = new ReentrantLock();
    private final IntOpenHashSet seenIds = new IntOpenHashSet(6000);//TODO: move to a lock free concurrent hashmap
    public void requestBlockBake(int blockId) {
        if (this.mapper.getBlockStateCount() <= blockId) {
            Logger.error("Error, got bakeing request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception());
            return;
        }
        this.seenIdsLock.lock();
        if (!this.seenIds.add(blockId)) {
            this.seenIdsLock.unlock();
            return;
        }
        this.seenIdsLock.unlock();
        this.enqueueLock.lock();
        this.factory.addEntry(blockId);
        this.enqueueLock.unlock();
        LockSupport.unpark(this.processingThread);
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.factory.addBiome(biomeEntry);
        LockSupport.unpark(this.processingThread);
    }

    public void addDebugData(List<String> debug) {
        debug.add(String.format("IF/MC: %03d, %04d", this.factory.getInflightCount(),  this.factory.getBakedCount()));//Model bake queue/in flight/model baked count
    }

    public ModelStore getStore() {
        return (ModelStore) this.storage;
    }

    public IModelSink getSink() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.factory.getInflightCount() == 0;
    }

    public int getProcessingCount() {
        return this.factory.getInflightCount();
    }
}
