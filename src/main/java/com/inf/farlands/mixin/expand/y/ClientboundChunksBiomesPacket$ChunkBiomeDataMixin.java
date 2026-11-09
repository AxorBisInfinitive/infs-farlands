package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.serialize.SectionSerializer;
import com.inf.farlands.util.window.WindowSendState;

import java.util.List;
import java.util.Map;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * biomes 包的窗口化线格式：只发发送窗口内的段，且逐段带绝对 sectionY。
 *
 * vanilla 遍历 chunk.getSections()，而本 port 的 getSections() 返回窗口视图：服务端窗口是构造
 * 默认的死状态，客户端窗口每帧跟着相机滑动，两侧段集不同，读侧按自己的数组顺序解就会错位。
 * 带上绝对 sectionY 后，读侧不再依赖两侧窗口一致。
 *
 * 容量与写入必须共用同一取集入口：一个定 buffer 大小，一个往里写，两次段集不一致会在 26.1.2
 * 末尾的写满断言上抛。取集走 sendableSections，它照发纯空气段：/fillbiome 在没有方块的地方
 * 只改群系，段不发给客户端就永远收不到。尺寸逐字段用 VarInt.getByteSize 精确算，不能按量级
 * 估：负数的 varint 恒占 5 字节，极端 Y 下正侧也要 4 字节。
 */
@Mixin(ClientboundChunksBiomesPacket.ChunkBiomeData.class)
public abstract class ClientboundChunksBiomesPacket$ChunkBiomeDataMixin {

    @Overwrite
    private static int calculateChunkSize(LevelChunk chunk) {
        List<Map.Entry<Integer, LevelChunkSection>> toSend = WindowSendState.sendableSections(chunk);
        int size = VarInt.getByteSize(toSend.size());
        for (Map.Entry<Integer, LevelChunkSection> e : toSend) {
            size += VarInt.getByteSize(e.getKey()) + e.getValue().getBiomes().getSerializedSize();
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
                e.getValue().getBiomes().write(buffer);
            }
        }
    }
}
