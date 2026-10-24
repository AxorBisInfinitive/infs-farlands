package com.inf.farlands.register.command;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.command.CommandRegistrationEvent;
import com.inf.farlands.serialize.ChunkCounters;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.registry.SystemsData;
import com.inf.farlands.terrain.registry.SystemsHolder;
import com.inf.farlands.util.window.WindowedChunk;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 服务端诊断命令族。{@code /farlands section ...} 把玩家当前 section 的光照层、群系、
 * 方块数据与地形管线状态 dump 到日志，用于极端坐标下的对账；{@code /farlands system args dump}
 * 把当前维度的系统选择按磁盘那套 codec 编成 SNBT 写进日志。
 *
 * <p>
 * 本类不引用任何客户端类，专用服务器也能加载；客户端侧同名命令见
 * {@code ClientFarLandsCommands}，两者输出同一格式以便逐格对比。
 *
 * <p>
 * 移植自上一版本（NeoForge 1.21.1），按下述 26.1.2 / 本仓库差异适配：
 * <ul>
 * <li>{@code RegisterCommandsEvent} → {@link CommandRegistrationEvent}；</li>
 * <li>{@code InfFarlands} → {@link InfsFarlands}（类名改过）；</li>
 * <li>{@code WindowedChunk} 由 {@code com.inf.farlands.window} 移到
 * {@code com.inf.farlands.util.window}；</li>
 * <li>{@code FarLandsGenState.forEachStage} → 本仓库的状态机承载是 {@link SectionStage}，
 * 它只提供单点读取，故这里自行遍历窗口内的 section；</li>
 * <li>{@code player.serverLevel()} → {@code (ServerLevel) player.level()}；</li>
 * <li>{@code source.hasPermission(2)} →
 * {@code Commands.hasPermission(LEVEL_GAMEMASTERS)}；</li>
 * <li>{@code ChunkPos.x/z} 字段 → {@code x()/z()} accessor。</li>
 * </ul>
 */
public class FarLandsCommands {

    public static void register(CommandRegistrationEvent event) {
        event.getDispatcher().register(
                Commands.literal("farlands")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("section")
                                .then(Commands.literal("light")
                                        .then(Commands.literal("dump")
                                                .executes(ctx -> dump(ctx.getSource()))))
                                .then(Commands.literal("biome")
                                        .then(Commands.literal("dump")
                                                .executes(ctx -> dumpBiomeCmd(ctx.getSource())))
                                        .then(Commands.literal("compute")
                                                .executes(ctx -> computeBiomeCmd(ctx.getSource()))))
                                .then(Commands.literal("block")
                                        .then(Commands.literal("dump")
                                                .executes(ctx -> dumpBlocksCmd(ctx.getSource()))))
                                .then(Commands.literal("pipeline")
                                        .then(Commands.literal("state")
                                                .then(Commands.literal("dump")
                                                        .executes(ctx -> dumpPipelineState(ctx.getSource())))))
                                .then(Commands.literal("size")
                                        .then(Commands.literal("dump")
                                                .executes(ctx -> dumpSectionSize(ctx.getSource())))))
                        .then(Commands.literal("system")
                                .then(Commands.literal("args")
                                        .then(Commands.literal("dump")
                                                .executes(ctx -> dumpSystemArgs(ctx.getSource())))))
                        .then(Commands.literal("random")
                                .then(Commands.literal("tp")
                                        .executes(ctx -> randomTeleport(ctx.getSource())))));
    }

    private static int dump(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = (ServerLevel) player.level();
            BlockPos pos = player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            LevelLightEngine le = level.getChunkSource().getLightEngine();

            InfsFarlands.LOGGER.info("FLDUMP server pos={},{},{} sec={},{},{}",
                    pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());
            dumpLayer(le, LightLayer.SKY, sec);
            dumpLayer(le, LightLayer.BLOCK, sec);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.section.light.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("FLDUMP server err", e);
        }
        return 1;
    }

    private static int dumpBiomeCmd(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = (ServerLevel) player.level();
            BlockPos pos = player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            InfsFarlands.LOGGER.info("BIODUMP server pos={},{},{} sec={},{},{}",
                    pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());
            dumpBiomes(level, sec);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.section.biome.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("BIODUMP server err", e);
        }
        return 1;
    }

    /** 方块数据独立命令：/farlands section block dump——light dump 不再包含方块数据。 */
    private static int dumpBlocksCmd(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = (ServerLevel) player.level();
            BlockPos pos = player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            InfsFarlands.LOGGER.info("BLOCKDUMP server pos={},{},{} sec={},{},{}",
                    pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());
            dumpBlocks(level, sec);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.section.block.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("BLOCKDUMP server err", e);
        }
        return 1;
    }

    /** dump 玩家当前 section 的生成状态，即 PLSTATE。 */
    private static int dumpPipelineState(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = (ServerLevel) player.level();
            SectionPos sec = SectionPos.of(player.blockPosition());
            LevelChunk lc = SectionLifecycle.latestChunk(level, sec.x(), sec.z());
            if (lc != null) {
                int stage = SectionStage.getStage(lc, sec.y());
                InfsFarlands.LOGGER.info("PLSTATE secY={} stage={}", sec.y(), stage);
                source.sendSuccess(() -> Component.translatable("commands.infs-farlands.section.pipeline.state"), false);
                source.sendSuccess(() -> Component.literal("stage=" + stage), false);
            } else {
                InfsFarlands.LOGGER.info("PLSTATE secY={} chunk null", sec.y());
                source.sendSuccess(() -> Component.translatable("commands.infs-farlands.section.pipeline.state"), false);
            }
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("PLSTATE err", e);
        }
        return 1;
    }

    /**
     * 全部已加载 chunk 的 section 总数，即 Σ windowedAllSections().size()。
     *
     * <p>
     * 取 ChunkMap.visibleChunkMap 的全部 holder：它只在 tick 时整体换引用，不做原地增删，命令跑在主
     * 线程，读一次字段再遍历是稳定快照。chunk 取 getLatestChunk，因此含生成中的 ProtoChunk，不只
     * ticking 那批。ImposterProtoChunk 必须解包到 wrapped 的 LevelChunk：它没有覆写
     * windowedAllSections，混入注入的表是它自己那份，直接读会漏算。
     */
    private static int dumpSectionSize(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = (ServerLevel) player.level();
            ServerChunkCache chunkSource = level.getChunkSource();
            Long2ObjectMap<ChunkHolder> holders = farlands$visibleChunkMap(chunkSource.chunkMap);
            int chunks = 0;
            long sections = 0L;
            for (ChunkHolder holder : holders.values()) {
                ChunkAccess ca = holder.getLatestChunk();
                if (ca == null) {
                    continue;
                }
                if (ca instanceof ImposterProtoChunk imposter) {
                    ca = imposter.getWrapped();
                }
                chunks++;
                sections += ((WindowedChunk) ca).windowedAllSections().size();
            }
            int chunkCount = chunks;
            long sectionCount = sections;
            InfsFarlands.LOGGER.info("SIZEDUMP server dim={} chunks={} sections={}",
                    level.dimension().identifier(), chunkCount, sectionCount);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.section.size.dump",
                    chunkCount, sectionCount), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("SIZEDUMP server err", e);
        }
        return 1;
    }

    /**
     * 把当前维度的系统选择按磁盘那套 codec 编成 SNBT 写进日志，供对账与手改配置取用。
     *
     * <p>只取当前维度那一条：整份 SystemsData 是维度到选择的映射，把这一条包成只含它的 SystemsData
     * 再编码，形状与 systems.dat 里 data 载荷逐字一致。文件里没有该维度的条目时只记 absent，不改用
     * 兜底值，因为这里要暴露的正是文件里到底有没有。
     */
    private static int dumpSystemArgs(CommandSourceStack source) {
        try {
            ServerLevel level = source.getLevel();
            Identifier dimension = level.dimension().identifier();
            SystemsData data = ((SystemsHolder) level).systems();
            SystemsData.LevelSelection selection = data.selection(dimension);
            if (selection == null) {
                InfsFarlands.LOGGER.info("SYSDATA dim={} absent", dimension);
            } else {
                String snbt = SystemsData.CODEC
                        .encodeStart(NbtOps.INSTANCE, new SystemsData(Map.of(dimension, selection)))
                        .getOrThrow()
                        .toString();
                InfsFarlands.LOGGER.info("SYSDATA dim={}", dimension);
                InfsFarlands.LOGGER.info("SYSDATA sel={}", snbt);
            }
            source.sendSuccess(
                    () -> Component.translatable("commands.infs-farlands.system.args.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("SYSDATA err", e);
        }
        return 1;
    }

    /**
     * 取三个 int 全域随机整数，按这三个坐标把 {@code tp} 派发给同一个命令源。
     *
     * <p>不自己调传送：可用范围判定、客户端位置包、传送后的速度与 onGround 处理都在原版那条命令里，
     * 自己另走一遍等于把这四处复制成第二份实现。派发经
     * {@code Commands.performPrefixedCommand}，嵌套调用复用当前命令执行上下文，命令在本方法返回后
     * 立刻执行。
     *
     * <p>取值不做筛选也不钳制，落在原版判据之外的抽取由原版那条命令自己报错。
     */
    private static int randomTeleport(CommandSourceStack source) {
        int x = ThreadLocalRandom.current().nextInt();
        int y = ThreadLocalRandom.current().nextInt();
        int z = ThreadLocalRandom.current().nextInt();
        source.getServer().getCommands().performPrefixedCommand(source, "tp " + x + " " + y + " " + z);
        return 1;
    }

    @SuppressWarnings("unchecked")
    private static Long2ObjectMap<ChunkHolder> farlands$visibleChunkMap(ChunkMap chunkMap) {
        try {
            return (Long2ObjectMap<ChunkHolder>) F_VISIBLE_CHUNK_MAP.get(chunkMap);
        } catch (Exception e) {
            throw new RuntimeException("farlands: ChunkMap.visibleChunkMap", e);
        }
    }

    /** 当前 section 的 4x4x4 biome 网格，服务端/客户端共用。 */
    public static void dumpBiomes(Level level, SectionPos sec) {
        try {
            LevelChunk lc = level instanceof ServerLevel sl
                    ? SectionLifecycle.latestChunk(sl, sec.x(), sec.z())
                    : (level.getChunk(sec.x(), sec.z()) instanceof LevelChunk c ? c : null);
            if (lc == null) {
                InfsFarlands.LOGGER.info("BIODUMP secY={} chunk null", sec.y());
                return;
            }
            LevelChunkSection s = ((WindowedChunk) lc).windowedAllSections().get(sec.y());
            if (s == null) {
                InfsFarlands.LOGGER.info("BIODUMP secY={} section null", sec.y());
                return;
            }
            for (int y = 0; y < 4; y++) {
                StringBuilder sb = new StringBuilder();
                sb.append("BIODUMP secY=").append(sec.y()).append(" y=").append(y);
                for (int z = 0; z < 4; z++) {
                    sb.append("\nBIODUMP   z=").append(z).append(' ');
                    for (int x = 0; x < 4; x++) {
                        Holder<Biome> b = s.getBiomes().get(x, y, z);
                        sb.append(b.unwrapKey().map(k -> k.identifier().getPath()).orElse("?")).append(' ');
                    }
                }
                InfsFarlands.LOGGER.info("{}", sb);
            }
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("BIODUMP err", e);
        }
    }

    /**
     * 用当前维度的 BiomeSystem 现算一遍该 section 的 biome 网格。
     *
     * <p>必须走 {@code LevelSystems.biomeSystem()}，不能用原版 sampler 代替：群系来源是每维度选定的
     * 系统，Oct 之类的系统用自己的 seed 与缩放算气候，拿原版 sampler 对照只会得出错误结论。系统类名
     * 一并打进日志，便于确认到底是谁算的。
     *
     * <p>{@code BiomeSystem.fillBiomes} 只提供「写进某个 chunk 的 section」这一种入口，所以这里临时
     * 换上一个空白 section 跑一次，finally 里把原对象换回并按窗口映射同步数组。命令跑在服务端主线程，
     * 与发包同线程，这段窗口内没有并发读者。
     */
    private static int computeBiomeCmd(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = (ServerLevel) player.level();
            SectionPos sec = SectionPos.of(player.blockPosition());
            LevelChunk lc = SectionLifecycle.latestChunk(level, sec.x(), sec.z());
            if (lc == null) {
                InfsFarlands.LOGGER.info("BIOCALC secY={} chunk null", sec.y());
                return 0;
            }
            WindowedChunk wc = (WindowedChunk) lc;
            LevelChunkSection stored = wc.windowedAllSections().get(sec.y());
            if (stored == null) {
                InfsFarlands.LOGGER.info("BIOCALC secY={} section null", sec.y());
                return 0;
            }
            var system = ((com.inf.farlands.terrain.LevelSystems) level).biomeSystem();
            int idx = lc.getSectionIndexFromSectionY(sec.y());
            InfsFarlands.LOGGER.info("BIOCALC system={} pos={},{},{} sec={},{},{}",
                    system.getClass().getSimpleName(), player.blockPosition().getX(),
                    player.blockPosition().getY(), player.blockPosition().getZ(),
                    sec.x(), sec.y(), sec.z());
            LevelChunkSection computed = new LevelChunkSection(wc.containerFactory());
            try {
                wc.windowedAllSections().put(sec.y(), computed);
                lc.getSection(idx); // 数组同步：get 内部执行 arr[idx]=s
                system.fillBiomes(level, lc, sec.y(), sec.y());
            } finally {
                wc.windowedAllSections().put(sec.y(), stored);
                lc.getSection(idx);
            }
            for (int y = 0; y < 4; y++) {
                StringBuilder sb = new StringBuilder();
                sb.append("BIOCALC secY=").append(sec.y()).append(" y=").append(y);
                for (int z = 0; z < 4; z++) {
                    sb.append("\nBIOCALC   z=").append(z).append(' ');
                    for (int x = 0; x < 4; x++) {
                        sb.append(biomeName(computed, x, y, z)).append(' ');
                    }
                }
                InfsFarlands.LOGGER.info("{}", sb);
            }
            source.sendSuccess(
                    () -> Component.translatable("commands.infs-farlands.section.biome.compute"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("BIOCALC err", e);
        }
        return 1;
    }

    private static String biomeName(LevelChunkSection s, int x, int y, int z) {
        return s.getBiomes().get(x, y, z).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
    }

    public static void dumpLayer(LevelLightEngine le, LightLayer layer, SectionPos sec) {
        DataLayer dl = le.getLayerListener(layer).getDataLayerData(sec);
        String tag = layer == LightLayer.SKY ? "SKY" : "BLK";
        if (dl == null) {
            InfsFarlands.LOGGER.info("FLDUMP {} secY={} null", tag, sec.y());
            return;
        }
        if (dl.isEmpty()) {
            InfsFarlands.LOGGER.info("FLDUMP {} secY={} empty", tag, sec.y());
            return;
        }
        for (int y = 0; y < 16; y++) {
            StringBuilder sb = new StringBuilder();
            sb.append("FLDUMP ").append(tag).append(" secY=").append(sec.y()).append(" y=").append(y);
            for (int z = 0; z < 16; z++) {
                sb.append("\nFLDUMP   z=").append(z).append(' ');
                for (int x = 0; x < 16; x++) {
                    sb.append(Character.forDigit(dl.get(x, y, z) & 0xFF, 16));
                }
            }
            InfsFarlands.LOGGER.info("{}", sb);
        }
    }

    /** 方块数据：. = 空气，# = 非空气——与光照矩阵对照，区分方块格的 0 为正常、空气格的 0 为异常。 */
    public static void dumpBlocks(Level level, SectionPos sec) {
        try {
            LevelChunk lc = level instanceof ServerLevel sl
                    ? SectionLifecycle.latestChunk(sl, sec.x(), sec.z())
                    : (level.getChunk(sec.x(), sec.z()) instanceof LevelChunk c ? c : null);
            if (lc == null) {
                InfsFarlands.LOGGER.info("FLDUMP BLOCKS secY={} null", sec.y());
                return;
            }
            LevelChunkSection s = ((WindowedChunk) lc).windowedAllSections().get(sec.y());
            if (s == null) {
                InfsFarlands.LOGGER.info("FLDUMP BLOCKS secY={} empty", sec.y());
                return;
            }
            for (int y = 0; y < 16; y++) {
                StringBuilder sb = new StringBuilder();
                sb.append("FLDUMP BLOCKS secY=").append(sec.y()).append(" y=").append(y);
                for (int z = 0; z < 16; z++) {
                    sb.append("\nFLDUMP   z=").append(z).append(' ');
                    for (int x = 0; x < 16; x++) {
                        sb.append(s.getBlockState(x, y, z).isAir() ? '.' : '#');
                    }
                }
                InfsFarlands.LOGGER.info("{}", sb);
            }
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("FLDUMP BLOCKS err", e);
        }
    }

    // ---- ChunkMap.visibleChunkMap 是 private，服务端没有公开的已加载 chunk 迭代入口 ----

    private static final Field F_VISIBLE_CHUNK_MAP;

    static {
        try {
            F_VISIBLE_CHUNK_MAP = ChunkMap.class.getDeclaredField("visibleChunkMap");
            F_VISIBLE_CHUNK_MAP.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("fail FarLandsCommands", e);
        }
    }
}
