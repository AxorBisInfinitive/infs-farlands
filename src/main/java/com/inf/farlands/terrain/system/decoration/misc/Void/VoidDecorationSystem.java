package com.inf.farlands.terrain.system.decoration.misc.Void;

import com.inf.farlands.terrain.DecorationSystem;
import com.inf.farlands.terrain.decorationFiller.DecorationRegion;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** VOID 装饰系统：不放任何地物与结构。状态推进与标脏由 DecorationFiller 负责。 */
public final class VoidDecorationSystem implements DecorationSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    /** 统一构造签名，本系统不读任何参数。 */
    public VoidDecorationSystem(SystemArgs args) {
    }

    @Override
    public void applyDecoration(ServerLevel level, LevelChunk center, DecorationRegion region) {
    }

    /**
     * 不建结构起点。起点是结构的第一产物，跳过它之后引用表为空、Beardifier 为 EMPTY，地形不会在结构
     * 位置按 default_block 鼓出壳。
     */
    @Override
    public boolean createStructures(ServerLevel level, LevelChunk chunk) {
        return false;
    }
}
