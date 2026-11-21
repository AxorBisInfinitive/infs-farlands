package com.inf.farlands.simulation.int1024.system.biome.misc.Void;

import com.inf.farlands.simulation.int1024.SimulationBiomeSystem;
import com.inf.farlands.simulation.int1024.noise.Router;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import love.crysh.long2.ints.Int1024;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * VOID 群系系统：整个模拟层的 biome 恒为 the_void。
 *
 * <p>
 * 与建段的默认同值，所以选到它时行为与没有群系系统时逐字相同，段容器的群系不会因为这一族存在而改变。
 *
 * <p>
 * 不吃参数，声明写 {@link SystemParams#EMPTY}：注册期要求每个实现类都有声明字段，没有声明不是一种状态。
 *
 * <p>
 * 无状态：群系 holder 每次现取，纯方法多线程安全。
 */
public final class VoidSimulationBiomeSystem implements SimulationBiomeSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    /** 统一构造签名，本系统不读任何参数。 */
    public VoidSimulationBiomeSystem(SystemArgs args) {
    }

    @Override
    public Holder<Biome> computeBiome(ServerLevel level, Router router, Int1024 quartX, Int1024 quartY,
            Int1024 quartZ) {
        return level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
    }
}
