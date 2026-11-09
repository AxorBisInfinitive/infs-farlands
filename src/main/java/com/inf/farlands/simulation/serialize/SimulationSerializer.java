package com.inf.farlands.simulation.serialize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Optional;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import com.inf.farlands.network.expand.y.ChunkDataPacket;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionStage;

/**
 * ssa 条目编解码。条目字节与 {@link LevelChunkSection}、两层光照、形状模式与阶段互转。
 *
 * <p>
 * 条目格式与 fsa 的 {@code SectionSerializer} 同构：先 int 长度加一语义，用来区分存在但空与不存在，再一
 * 字节压缩版本，取值 4 即 lz4，随后是 lz4 压缩的 NBT。
 *
 * <p>
 * NBT 键：DataVersion、X、Y、Z、block_states、biomes、BlockLight、SkyLight、shapeMode、stage。与 fsa
 * 的差异有三处，都是刻意的：
 *
 * <ul>
 * <li>多了 X 与 Z。fsa 只存 Y，坐标由文件名与槽位隐含，写错一格就是把数据搬到另一段且没有任何检测。ssa
 * 是自己设计的格式，八字节换一个当场炸出来的自检。</li>
 * <li>多了 shapeMode 与 stage。形状模式是段级的，容器里没有它；阶段要随数据一起落盘，读回时按盘上值恢复。</li>
 * <li>光照的省略判据是 {@code isEmpty} 而不是 {@code isDefinitelyHomogenous}。同质非零的层，例如全 15 的
 * 天光，在后者下也会被省略，而省掉的键解码时只能还原成全 0，值就此静默丢掉。</li>
 * </ul>
 *
 * <p>
 * 不接 vanilla datafix：mod 锁定 26.1.2，不跨版本，DataVersion 只作诊断与未来迁移用。
 *
 * <p>
 * 门在调用方。{@link #encode} 只允许对 {@link SimulationSectionStage#isTerminal} 的段调用，因为此刻内容
 * 已定；{@link #decode} 若读到 {@code UNPROCESSED} 的 stage，调用方应按未命中处理，重跑生产者。
 *
 * <p>
 * 不需要 fsa 那把 {@code packLockFor}：仿真的段是独立容器，写方在池线程、编码方在落盘路径，两者由 stage
 * 门分开，不存在同一个容器被两个线程同时碰。
 *
 * <p>
 * 当前无调用方：ssa 的 IO 层与落盘接线在后续切片里接。
 */
public final class SimulationSerializer {

    private SimulationSerializer() {
    }

    /**
     * 编码：段容器、两层光照、形状模式与阶段，产出含长度头的 ssa 条目字节。
     *
     * <p>
     * 坐标三段都写进去，供解码自检。chunkKey 不需要：本类不取打包锁。
     */
    public static byte[] encode(LevelChunkSection section, DataLayer blockLight, DataLayer skyLight,
            int shapeMode, int stage, PalettedContainerFactory containerFactory, long sectionX, long sectionY,
            long sectionZ) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        // 三段用 long：段号可以超出 int，方块位置在这个 port 里本来就能到十的十八次方量级
        tag.putLong("X", sectionX);
        tag.putLong("Y", sectionY);
        tag.putLong("Z", sectionZ);
        tag.put("block_states",
                containerFactory.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, section.getStates())
                        .getOrThrow());
        tag.put("biomes",
                containerFactory.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, section.getBiomes())
                        .getOrThrow());
        if (blockLight != null && !blockLight.isEmpty()) {
            tag.putByteArray("BlockLight", ChunkDataPacket.rawLightBytes(blockLight));
        }
        if (skyLight != null && !skyLight.isEmpty()) {
            tag.putByteArray("SkyLight", ChunkDataPacket.rawLightBytes(skyLight));
        }
        tag.putInt("shapeMode", shapeMode);
        tag.putInt("stage", stage);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            LZ4BlockOutputStream lz4 = new LZ4BlockOutputStream(baos);
            DataOutputStream out = new DataOutputStream(lz4);
            NbtIo.write(tag, out);
            out.flush();
            lz4.close();
            byte[] payload = baos.toByteArray();

            ByteArrayOutputStream entry = new ByteArrayOutputStream(payload.length + 5);
            DataOutputStream entryOut = new DataOutputStream(entry);
            entryOut.writeInt(payload.length + 1); // 加一语义，vanilla 惯例
            entryOut.writeByte(4); // lz4 版本 ID，对齐 RegionFileVersion.VERSION_LZ4
            entryOut.write(payload);
            entryOut.flush();
            return entry.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(
                    "ssa encode section=" + sectionX + "," + sectionY + "," + sectionZ, e);
        }
    }

    /** 解码结果。两层光照可以是 null，表示盘上没有那一层，与全 0 同值。 */
    public record DecodedSection(LevelChunkSection section, DataLayer blockLight, DataLayer skyLight,
            int shapeMode, int stage) {
    }

    /**
     * 解码。三段坐标必须与条目里的 X、Y、Z 逐一相等，不等即判为错位并抛异常，由调用方按损坏处理，走重生成。
     *
     * <p>
     * 数据损坏一律抛，不返回半个结果。调用方的语义是「读不到就当盘上没有」。
     */
    @SuppressWarnings("deprecation")
    public static DecodedSection decode(byte[] entry, PalettedContainerFactory containerFactory, long sectionX,
            long sectionY, long sectionZ) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(entry));
            int len = in.readInt();
            in.readByte(); // 版本，lz4，不校验，格式版本由文件级 magic 保证
            int payloadLen = len - 1;
            if (payloadLen < 0 || payloadLen > entry.length - 5) {
                throw new IllegalStateException("ssa entry length corrupt: " + len);
            }
            byte[] payload = new byte[payloadLen];
            in.readFully(payload);

            LZ4BlockInputStream lz4 = new LZ4BlockInputStream(new ByteArrayInputStream(payload));
            DataInputStream nbtIn = new DataInputStream(lz4);
            CompoundTag tag = NbtIo.read(nbtIn);
            nbtIn.close();

            long haveX = tag.getLongOr("X", Long.MIN_VALUE);
            long haveY = tag.getLongOr("Y", Long.MIN_VALUE);
            long haveZ = tag.getLongOr("Z", Long.MIN_VALUE);
            if (haveX != sectionX || haveY != sectionY || haveZ != sectionZ) {
                throw new IllegalStateException("ssa entry coordinate mismatch: asked " + sectionX + ","
                        + sectionY + "," + sectionZ + " but entry holds " + haveX + "," + haveY + ","
                        + haveZ);
            }

            Optional<CompoundTag> blockStates = tag.getCompound("block_states");
            Optional<CompoundTag> biomes = tag.getCompound("biomes");
            if (blockStates.isEmpty() || biomes.isEmpty()) {
                throw new IllegalStateException("ssa entry missing block_states/biomes");
            }
            PalettedContainer<BlockState> states = containerFactory.blockStatesContainerCodec()
                    .parse(NbtOps.INSTANCE, blockStates.get())
                    .getOrThrow();
            PalettedContainerRO<Holder<Biome>> bios = containerFactory.biomeContainerCodec()
                    .parse(NbtOps.INSTANCE, biomes.get())
                    .getOrThrow();
            LevelChunkSection section = new LevelChunkSection(states, bios);
            DataLayer bl = tag.getByteArray("BlockLight").map(DataLayer::new)
                    .map(ChunkDataPacket::foldUniform).orElse(null);
            DataLayer sl = tag.getByteArray("SkyLight").map(DataLayer::new)
                    .map(ChunkDataPacket::foldUniform).orElse(null);
            int shapeMode = tag.getIntOr("shapeMode", SimulationSectionData.SHAPE_DERIVED);
            int stage = tag.getIntOr("stage", SimulationSectionStage.UNPROCESSED);
            return new DecodedSection(section, bl, sl, shapeMode, stage);
        } catch (Exception e) {
            throw new RuntimeException(
                    "ssa decode section=" + sectionX + "," + sectionY + "," + sectionZ, e);
        }
    }
}
