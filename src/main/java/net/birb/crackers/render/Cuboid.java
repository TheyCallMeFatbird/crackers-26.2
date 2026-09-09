package net.birb.crackers.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Lightweight marker describing "something interesting was found here".
 * <p>
 * Crackers does not draw anything in the world (world rendering APIs change
 * frequently between Minecraft versions and are a common source of crashes),
 * so this is a pure data holder. Finders still create Cuboids because the
 * finder framework uses a non-empty cuboid list as its "this finder produced a
 * hit" signal ({@link net.birb.crackers.finder.Finder#isUseless()}).
 */
public class Cuboid {
    private final BlockPos centerPos;
    private final int argb;

    public Cuboid(AABB box, int argb) {
        this.argb = argb;
        this.centerPos = BlockPos.containing(box.getCenter());
    }

    public Cuboid(BoundingBox boundingBox, int argb) {
        this(AABB.of(boundingBox), argb);
    }

    public Cuboid(BlockPos pos, int argb) {
        this.argb = argb;
        this.centerPos = pos;
    }

    public Cuboid(BlockPos pos, Vec3i size, int argb) {
        this.argb = argb;
        this.centerPos = pos.offset(size.getX() / 2, size.getY() / 2, size.getZ() / 2);
    }

    public BlockPos getCenterPos() {
        return this.centerPos;
    }
}
