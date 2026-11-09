package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.serialize.SectionSerializer;
import com.inf.farlands.util.window.WindowSendState;

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

/**
 * chunk 包只写窗口内的 section，且按绝对 sectionY 显式带坐标，与客户端读侧配套。
 *
 * 两个方法必须共用 WindowSendState.sendableSections：一个定 buffer 大小，一个往里写，两次遍历
 * 的集合不一致就会错位。26.1.2 的 vanilla 在 extractChunkData 末尾有写满断言，所以尺寸要用
 * VarInt.getByteSize 精确算，不能像 1.21.1 那样按固定 5 字节粗算。
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public class ClientboundLevelChunkPacketDataMixin {

    @Overwrite
    private static int calculateChunkSize(LevelChunk chunk) {
        List<Map.Entry<Integer, LevelChunkSection>> sections = WindowSendState.sendableSections(chunk);
        int size = VarInt.getByteSize(sections.size());
        for (Map.Entry<Integer, LevelChunkSection> e : sections) {
            size += VarInt.getByteSize(e.getKey()) + e.getValue().getSerializedSize();
        }
        return size;
    }

    @Overwrite
    public static void extractChunkData(FriendlyByteBuf buffer, LevelChunk chunk) {
        List<Map.Entry<Integer, LevelChunkSection>> toSend = WindowSendState.sendableSections(chunk);
        buffer.writeVarInt(toSend.size());
        for (Map.Entry<Integer, LevelChunkSection> e : toSend) {
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
