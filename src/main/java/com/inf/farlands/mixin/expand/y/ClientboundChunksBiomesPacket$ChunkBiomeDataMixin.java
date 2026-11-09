package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.serialize.SectionSerializer;
import com.inf.farlands.util.network.ChunkPayloadPin;
import com.inf.farlands.util.window.WindowSendState;

import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.Map;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * biomes 包的窗口化线格式：只发发送窗口内的段，且逐段带绝对 sectionY。
 *
 * vanilla 遍历 chunk.getSections()，而本 port 的 getSections() 返回窗口视图：服务端窗口是构造
 * 默认的死状态，客户端窗口每帧跟着相机滑动，两侧段集不同，读侧按自己的数组顺序解就会错位。
 * 带上绝对 sectionY 后，读侧不再依赖两侧窗口一致。
 *
 * <p>取集走 sendableSections，它照发纯空气段：/fillbiome 在没有方块的地方只改群系，段不发给客户端
 * 就永远收不到。
 *
 * <p>与 chunk 包同一个病：定尺寸与写入各过滤一次，而过滤读的状态跨线程会变，两次取样可能不一致。
 * 所以定尺寸那一步直接序列化一次、钉在 `ChunkPayloadPin` 上，写入那一步只拷贝。钉字节而不是钉列表：
 * 列表只能消掉段数错位，每段的群系容器 `getSerializedSize()` 与 `write()` 之间仍可能不一致。
 */
@Mixin(ClientboundChunksBiomesPacket.ChunkBiomeData.class)
public abstract class ClientboundChunksBiomesPacket$ChunkBiomeDataMixin {

    @Overwrite
    private static int calculateChunkSize(LevelChunk chunk) {
        List<Map.Entry<Integer, LevelChunkSection>> sections = WindowSendState.sendableSections(chunk);
        // 估算只用于给临时缓冲一个初值，写入过程中不够时由 ByteBuf 自己扩容。
        int estimate = VarInt.getByteSize(sections.size());
        for (Map.Entry<Integer, LevelChunkSection> e : sections) {
            estimate += VarInt.getByteSize(e.getKey()) + e.getValue().getBiomes().getSerializedSize();
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

    /** 一次序列化：段数与逐段的绝对 sectionY，群系容器在包锁内写。 */
    @Unique
    private static void farlands$write(List<Map.Entry<Integer, LevelChunkSection>> sections, LevelChunk chunk,
            FriendlyByteBuf buffer) {
        buffer.writeVarInt(sections.size());
        for (Map.Entry<Integer, LevelChunkSection> e : sections) {
            buffer.writeVarInt(e.getKey());
            // 与编码池的 fsa 编码互斥，同 SectionSerializer.packLockFor。
            synchronized (SectionSerializer.packLockFor(chunk.getPos().pack())) {
                e.getValue().getBiomes().write(buffer);
            }
        }
    }
}
