package com.inf.farlands.mixin.expand.xyz.pos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Unique;

import com.inf.farlands.util.pos.IntSectionPos;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;

@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {

    @Unique
    private final Long2IntMap sectionXByKey = new Long2IntOpenHashMap();
    @Unique
    private final Int2ObjectMap<LongSet> sectionsByX = new Int2ObjectOpenHashMap<>();

    // 按 X 轴索引，提升效率
    @Inject(method = "createSection", at = @At("TAIL"))
    private void onCreateSection(long sectionPos, CallbackInfoReturnable<EntitySection<T>> cir) {
        int sx = IntSectionPos.getSectionPos(sectionPos).x;
        sectionXByKey.put(sectionPos, sx);
        sectionsByX.computeIfAbsent(sx, k -> new LongOpenHashSet()).add(sectionPos);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void onRemove(long sectionId, CallbackInfo ci) {
        int sx = sectionXByKey.remove(sectionId);
        int defaultX = sectionXByKey.defaultReturnValue();

        if (sx == defaultX) {
            return;
        }

        LongSet set = sectionsByX.get(sx);
        if (set == null) {
            return;
        }

        set.remove(sectionId);
        if (set.isEmpty()) {
            sectionsByX.remove(sx);
        }
    }

    private final Long2ObjectMap<EntitySection<T>> sections = new Long2ObjectOpenHashMap<>();

    @Overwrite
    public void forEachAccessibleNonEmptySection(AABB bounds, AbortableIterationConsumer<EntitySection<T>> consumer) {
        int minSecX = SectionPos.posToSectionCoord(bounds.minX - 2.0);
        int maxSecX = SectionPos.posToSectionCoord(bounds.maxX + 2.0);
        int minSecY = SectionPos.posToSectionCoord(bounds.minY - 4.0);
        int maxSecY = SectionPos.posToSectionCoord(bounds.maxY + 0.0);
        int minSecZ = SectionPos.posToSectionCoord(bounds.minZ - 2.0);
        int maxSecZ = SectionPos.posToSectionCoord(bounds.maxZ + 2.0);

        // 遍历已存在的桶再按范围筛，不按 minSecX 到 maxSecX 枚举。坐标非有限时两个端点会饱和到 int 两端，
        // 区间宽度是两亿六千八百万，逐桶枚举等于卡死；已存在的桶数只与实体量有关。
        for (Int2ObjectMap.Entry<LongSet> bucket : this.sectionsByX.int2ObjectEntrySet()) {
            int sx = bucket.getIntKey();
            if (sx < minSecX || sx > maxSecX) {
                continue;
            }
            for (long key : bucket.getValue()) {
                IntSectionPos pos = IntSectionPos.getSectionPos(key);
                if (pos.y < minSecY || pos.y > maxSecY || pos.z < minSecZ || pos.z > maxSecZ) {
                    continue;
                }
                EntitySection<T> section = this.sections.get(key);
                if (section != null &&
                        !section.isEmpty() &&
                        section.getStatus().isAccessible() &&
                        consumer.accept(section).shouldAbort()) {
                    return;
                }
            }
        }
    }

    @Shadow
    private LongSortedSet sectionIds;

    @Overwrite
    private LongSortedSet getChunkSections(int cx, int cz) {
        LongAVLTreeSet result = new LongAVLTreeSet();
        LongIterator it = this.sectionIds.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            IntSectionPos sp = IntSectionPos.getSectionPos(key);
            if (sp.x == cx && sp.z == cz) {
                result.add(key);
            }
        }
        return result;
    }

    @Overwrite
    private static long getChunkKeyFromSectionKey(long pos) {
        IntSectionPos sp = IntSectionPos.getSectionPos(pos);
        return ChunkPos.pack(sp.x, sp.z);
    }
}
