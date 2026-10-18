package com.inf.farlands.register;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.command.FarlandsCommandRegistry;
import com.inf.farlands.compat.chunky.ChunkyPregen;
import com.inf.farlands.register.command.FarLandsCommands;
import com.inf.farlands.register.packet.*;
import com.inf.farlands.terrain.registry.SystemRegistries;
import com.inf.farlands.terrain.system.biome.misc.Void.VoidBiomeSystem;
import com.inf.farlands.terrain.system.biome.overworld.Oct.OctBiomeSystem;
import com.inf.farlands.terrain.system.biome.overworld.Vanilla.VanillaBiomeSystem;
import com.inf.farlands.terrain.system.carver.misc.Void.VoidCarverSystem;
import com.inf.farlands.terrain.system.carver.overworld.Vanilla.VanillaCarverSystem;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;
import com.inf.farlands.terrain.system.decoration.misc.Void.VoidDecorationSystem;
import com.inf.farlands.terrain.system.decoration.overworld.Vanilla.VanillaDecorationSystem;
import com.inf.farlands.terrain.system.surface.misc.Void.VoidSurfaceSystem;
import com.inf.farlands.terrain.system.surface.overworld.Vanilla.VanillaSurfaceSystem;
import com.inf.farlands.terrain.system.terrain.block.misc.Sierpinski.SierpinskiPyramidBlockSystem;
import com.inf.farlands.terrain.system.terrain.block.misc.TheFourthDimension.TheFourthDimensionBlockSystem;
import com.inf.farlands.terrain.system.terrain.block.overworld.Infdev.Infdev20100226BlockSystem;
import com.inf.farlands.terrain.system.terrain.noise.misc.Hex.HexNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.misc.Void.VoidNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.misc.Weierstrass.WeierstrassNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173.Beta173NoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Cwg.CwgNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Oct.OctNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla.VanillaNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263.Vanilla263NoiseSystem;

public class FarlandsRegister {

    /** payload 类型是否已登记。模组初始化与三处 codec 构造点都会调，可能来自不同线程。 */
    private static boolean payloadTypesRegistered;

    public static void registerStatic() {
        registerPayloadTypes("mod-init");
        registerSystems();
        // 空框 seed 的会话边界是进页。登记由状态的所有者自己发起，界面只负责发那个公开钩子。
        VanillaFamilySeed.registerPageHook();
    }

    /**
     * 登记六个自定义 payload 类型，只做一次。
     *
     * <p>两个来源：模组初始化，以及 {@code ClientboundCustomPayloadPacket} 与
     * {@code ServerboundCustomPayloadPacket} 的 {@code <clinit>} 里那三处 codec 构造点。分发表在那三个点上
     * 一次性建好，内容取自 Commonbounds／Serverbounds 的当时内容，所以谁先触发那个 {@code <clinit>}，
     * 快照就是谁的时机。装了会在自己模组初始化期碰这两个类的模组（Fabric API 的 networking 模块就是）时，
     * {@code <clinit>} 就早于本模组的初始化，快照拍到空表，本模组的包全部落回 DiscardedPayload，发包抛
     * ClassCastException。故三处构造点也负责把表填好，使这件事与任何第三方模组的初始化顺序无关。
     *
     * <p>必须整体上锁：clientbound 的 {@code <clinit>} 在渲染线程，serverbound 的在 Netty 线程，两个方向的
     * 表可能并发构造，check-then-act 会双填，而重复 id 会让 CustomPacketPayload.codec 的
     * toUnmodifiableMap 抛 Duplicate key。
     *
     * @param source 触发来源，只用于日志；第一个调用者的来源会留下，便于事后判别是顺序问题还是别的。
     */
    public static void registerPayloadTypes(String source) {
        synchronized (FarlandsRegister.class) {
            if (payloadTypesRegistered) {
                return;
            }
            payloadTypesRegistered = true;
            InfsFarlands.LOGGER.info("farlands: registered custom payload types from {}", source);
            ChunkDataPacketRegister.registerType();
            ClampStatePacketRegister.registerType();
            ClampTogglePacketRegister.registerType();
            LightUpdatePacketRegister.registerType();
            SectionBlocksUpdatePacketRegister.registerType();
            SystemsPacketRegister.registerType();
        }
    }

    /** 20 个内置系统按五族登记，id 与实现类的对应关系集中在此。 */
    private static void registerSystems() {
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_MISC_VOID_NOISE_SYSTEM, VoidNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_OVERWORLD_VANILLA_NOISE_SYSTEM,
                VanillaNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_OVERWORLD_BETA_1_7_3_NOISE_SYSTEM,
                Beta173NoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_MISC_HEX_NOISE_SYSTEM, HexNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_MISC_WEIERSTRASS_NOISE_SYSTEM,
                WeierstrassNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_OVERWORLD_OCT_NOISE_SYSTEM, OctNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_OVERWORLD_INFDEV_20100226_BLOCK_SYSTEM,
                Infdev20100226BlockSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_MISC_SIERPINSKI_PYRAMID_BLOCK_SYSTEM,
                SierpinskiPyramidBlockSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_MISC_THE_FOURTH_DIMENSION_BLOCK_SYSTEM,
                TheFourthDimensionBlockSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_OVERWORLD_CWG_NOISE_SYSTEM,
                CwgNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_OVERWORLD_VANILLA_263_NOISE_SYSTEM,
                Vanilla263NoiseSystem.class);

        SystemRegistries.registerBiome(SystemRegistries.BIOME_MISC_VOID_BIOME_SYSTEM, VoidBiomeSystem.class);
        SystemRegistries.registerBiome(SystemRegistries.BIOME_OVERWORLD_VANILLA_BIOME_SYSTEM,
                VanillaBiomeSystem.class);
        SystemRegistries.registerBiome(SystemRegistries.BIOME_OVERWORLD_OCT_BIOME_SYSTEM, OctBiomeSystem.class);

        SystemRegistries.registerSurface(SystemRegistries.SURFACE_MISC_VOID_SURFACE_SYSTEM,
                VoidSurfaceSystem.class);
        SystemRegistries.registerSurface(SystemRegistries.SURFACE_OVERWORLD_VANILLA_SURFACE_SYSTEM,
                VanillaSurfaceSystem.class);

        SystemRegistries.registerCarver(SystemRegistries.CARVER_MISC_VOID_CARVER_SYSTEM,
                VoidCarverSystem.class);
        SystemRegistries.registerCarver(SystemRegistries.CARVER_OVERWORLD_VANILLA_CARVER_SYSTEM,
                VanillaCarverSystem.class);

        SystemRegistries.registerDecoration(SystemRegistries.DECORATION_MISC_VOID_DECORATION_SYSTEM,
                VoidDecorationSystem.class);
        SystemRegistries.registerDecoration(SystemRegistries.DECORATION_OVERWORLD_VANILLA_DECORATION_SYSTEM,
                VanillaDecorationSystem.class);
    }

    public static void register() {
        ClampTogglePacketRegister.registerHandler();
        // 命令监听器只在此登记一次；Commands 每次重建（含数据包 reload）时由 CommandsMixin 统一 fire。
        FarlandsCommandRegistry.registerServer(FarLandsCommands::register);
        // Chunky 兼容层：装了它才登记命令与每 tick 钩子，没装时本调用直接返回。
        ChunkyPregen.init();
    }
}
