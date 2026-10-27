package com.inf.farlands.terrain.system.biome.misc.Void;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * VOID 群系系统：整个世界的 biome 恒为 the_void。
 *
 * XZ quart 用 QuartPos.fromSection(chunkX) 而非 fromBlock(getMinBlockX)。getMinBlockX 被本
 * port 的饱和 Overwrite 覆盖，负极端会饱和到 MIN_VALUE + 15，quart 会差 3，即 12 格布局错位。
 *
 * 无状态：biome holder 每次 fill 现取，纯方法多线程安全。
 */
public final class VoidBiomeSystem implements BiomeSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    /** 统一构造签名，本系统不读任何参数。 */
    public VoidBiomeSystem(SystemArgs args) {
    }

    @Override
    public void fillBiomes(ServerLevel level, ChunkAccess chunk, int minSectionY, int maxSectionY) {
        Holder<Biome> theVoid = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
        BiomeResolver resolver = (x, y, z, sampler) -> theVoid;
        Climate.Sampler empty = Climate.empty();
        int qx = QuartPos.fromSection(chunk.getPos().x());
        int qz = QuartPos.fromSection(chunk.getPos().z());
        for (int sy = minSectionY; sy <= maxSectionY; sy++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
            section.fillBiomesFromNoise(resolver, empty, qx, QuartPos.fromSection(sy), qz);
        }
    }

    /** 恒为 the_void，与 fillBiomes 填进网格的是同一份，所以两条分支不会分叉。 */
    @Override
    public Holder<Biome> computeBiome(ServerLevel level, int quartX, int quartY, int quartZ) {
        return level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
    }

    /** 没有交界可言，取世界种子只为让缩放种子是确定的。 */
    @Override
    public long biomeSeed(ServerLevel level) {
        return level.getSeed();
    }
}
