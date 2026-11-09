package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.serialize.SectionSerializer;
import com.inf.farlands.util.network.ChunkPayloadPin;
import com.inf.farlands.util.window.WindowSendState;

import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import java.lang.reflect.Field;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * chunk 包只写窗口内的 section，且按绝对 sectionY 显式带坐标，与客户端读侧配套。
 *
 * <p>两次取样必须出自一次序列化。原版先按 `calculateChunkSize` 分配、再 `extractChunkData` 写入，
 * 而本 port 的段集合过滤读的是跨线程会变的状态：`GenQueue.isGeneratingOrLighting` 背后是认领表，
 * TERRAIN 认领由 `farlands-gen` 在生成任务的收尾里释放。两次各自过滤就可能在定尺寸之后、写入之前
 * 由空变非空，缓冲区按 1 字节分配、写段数即填满、写 sectionY 越界。
 *
 * <p>所以定尺寸那一步直接序列化一次、把字节钉在 `ChunkPayloadPin` 上，写入那一步只拷贝。钉字节而不是
 * 钉列表：列表只能消掉段数错位，每段的 `getSerializedSize()` 与 `write()` 之间仍可能不一致。
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public class ClientboundLevelChunkPacketDataMixin {

    @Overwrite
    private static int calculateChunkSize(LevelChunk chunk) {
        List<Map.Entry<Integer, LevelChunkSection>> sections = WindowSendState.sendableSections(chunk);
        // 估算只用于给临时缓冲一个初值，写入过程中不够时由 ByteBuf 自己扩容。
        int estimate = VarInt.getByteSize(sections.size());
        for (Map.Entry<Integer, LevelChunkSection> e : sections) {
            estimate += VarInt.getByteSize(e.getKey()) + e.getValue().getSerializedSize();
        }
        FriendlyByteBuf scratch = new FriendlyByteBuf(Unpooled.buffer(estimate));
        try {
            farlands$write(sections, chunk, scratch);
            byte[] bytes = new byte[scratch.readableBytes()];
            scratch.readBytes(bytes);
            ChunkPayloadPin.pin(chunk.getPos().pack(), bytes);
            return bytes.length;
        } finally {
            scratch.release();
        }
    }

    @Overwrite
    public static void extractChunkData(FriendlyByteBuf buffer, LevelChunk chunk) {
        byte[] bytes = ChunkPayloadPin.take(chunk.getPos().pack());
        if (bytes != null) {
            buffer.writeBytes(bytes);
            return;
        }
        // 退路：没有钉住的包体，说明构造顺序被改了。按原口径现算一次，容量可能不够而抛，与改动前一致。
        farlands$write(WindowSendState.sendableSections(chunk), chunk, buffer);
    }

    /** 一次序列化：段数与逐段的绝对 sectionY，段体在包锁内写。 */
    @Unique
    private static void farlands$write(List<Map.Entry<Integer, LevelChunkSection>> sections, LevelChunk chunk,
            FriendlyByteBuf buffer) {
        buffer.writeVarInt(sections.size());
        for (Map.Entry<Integer, LevelChunkSection> e : sections) {
            buffer.writeVarInt(e.getKey());
            // 与编码池的 fsa 编码互斥，同 SectionSerializer.packLockFor。
            synchronized (SectionSerializer.packLockFor(chunk.getPos().pack())) {
                e.getValue().write(buffer);
            }
        }
    }

    /**
     * 重建方块实体位置时改用完整的世界 Y。
     *
     * vanilla 的 getBlockEntitiesTags 用 BlockEntityInfo.packedXZ 与那个被 short 截断过的 y
     * 拼出方块实体坐标，|Y| 超过 32767 时位置就错。完整 Y 由
     * {@code ClientboundLevelChunkPacketData$BlockEntityInfoMixin} 注入的字段携带。
     *
     * BlockEntityInfo 是私有静态嵌套类，源码里连类型都引用不到，所以列表字段与它上面每个字段
     * 一律走反射，方法体里用 Object 迭代。那个注入字段被 Mixin 加了前缀，按后缀 yCorrect 查找。
     */

    private static final Class<?> C_PACKET;
    private static final Class<?> C_BLOCK_ENTITY_INFO;
    private static final Field F_BLOCK_ENTITIES_DATA;
    private static final Field F_PACKED_XZ;
    private static final Field F_TYPE;
    private static final Field F_TAG;
    private static final Field F_Y_CORRECT;

    static {
        try {
            C_PACKET = ClientboundLevelChunkPacketData.class;
            C_BLOCK_ENTITY_INFO = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo");
            F_BLOCK_ENTITIES_DATA = accessible(C_PACKET.getDeclaredField("blockEntitiesData"));
            F_PACKED_XZ = accessible(C_BLOCK_ENTITY_INFO.getDeclaredField("packedXZ"));
            F_TYPE = accessible(C_BLOCK_ENTITY_INFO.getDeclaredField("type"));
            F_TAG = accessible(C_BLOCK_ENTITY_INFO.getDeclaredField("tag"));
            F_Y_CORRECT = findYCorrect();
        } catch (Exception e) {
            throw new RuntimeException("fail ClientboundLevelChunkPacketDataMixin", e);
        }
    }

    private static Field accessible(Field f) {
        f.setAccessible(true);
        return f;
    }

    /** Mixin 给 @Unique 字段加前缀，所以按后缀匹配。 */
    private static Field findYCorrect() throws NoSuchFieldException {
        for (Field f : C_BLOCK_ENTITY_INFO.getDeclaredFields()) {
            if (f.getName().endsWith("yCorrect")) {
                return accessible(f);
            }
        }
        throw new NoSuchFieldException("yCorrect not found on BlockEntityInfo");
    }

    @SuppressWarnings("unchecked")
    @Overwrite
    private void getBlockEntitiesTags(ClientboundLevelChunkPacketData.BlockEntityTagOutput output, int chunkX,
            int chunkZ) {
        int baseX = 16 * chunkX;
        int baseZ = 16 * chunkZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        try {
            for (Object info : (List<Object>) F_BLOCK_ENTITIES_DATA.get(this)) {
                int packedXZ = F_PACKED_XZ.getInt(info);
                int y = F_Y_CORRECT.getInt(info);
                int x = baseX + SectionPos.sectionRelative(packedXZ >> 4);
                int z = baseZ + SectionPos.sectionRelative(packedXZ);
                pos.set(x, y, z);
                output.accept(pos, (BlockEntityType<?>) F_TYPE.get(info), (CompoundTag) F_TAG.get(info));
            }
        } catch (Exception e) {
            throw new RuntimeException("farlands: getBlockEntitiesTags", e);
        }
    }
}
