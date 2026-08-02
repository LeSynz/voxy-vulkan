# Voxy Vulkan

A far-distance LoD rendering mod for Minecraft, running on Minecraft's Vulkan backend.

This is a Vulkan port of [Voxy](https://github.com/MCRcortex/voxy) by **MCRcortex**. The world
storage, section data, ingest and voxelization are MCRcortex's original work and are backend
agnostic; this fork replaces the OpenGL renderer with one built on raw Vulkan via LWJGL, reaching
Minecraft's `VulkanDevice` through blaze3d's `GpuDevice` facade.

## Status

Alpha. Saved LoD terrain renders on the Vulkan backend in per-block colours, occluded correctly by
vanilla terrain, out to the configured render distance.

Not yet ported: the model/texture atlas (terrain is drawn with Minecraft's map colours), hierarchical
occlusion culling, background meshing and streaming, and SSAO.

## License

Voxy is **not** open source. `LICENSE.md` is MCRcortex's, in full:

> Copyright 2025 MCRcortex. All rights reserved. Do not redistribute.

That license covers this repository too, and it grants no permission to fork, rebrand, redistribute
or publish. Nothing here is offered under any other terms.

## Building

Requires a JDK 25 toolchain (a JDK, not a JRE — point `JAVA_HOME` at it explicitly if both are
installed).

```
./gradlew build
```
