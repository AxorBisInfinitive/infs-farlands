package com.inf.farlands.util.network;

/**
 * chunk 包体的单次钉住：定尺寸那一次把序列化结果留在这里，写入那一次取走。
 *
 * <p>存在的理由是原版的两次取样。`ClientboundLevelChunkPacketData` 与 `ClientboundChunksBiomesPacket`
 * 都是先 `new byte[calculateChunkSize(chunk)]`、再 `extractChunkData(...)`，两条方法各自过滤一次段的
 * 集合。本 port 的过滤读的是跨线程会变的状态：`GenQueue.isGeneratingOrLighting` 背后是认领表，
 * TERRAIN 认领由 `farlands-gen` 在 `GenTask.execute` 的收尾里释放。于是定尺寸时列表可能为空、写入时
 * 非空，缓冲区按 1 字节分配，写段数就把它填满，写第一个 sectionY 即越界。
 *
 * <p>钉住字节而不是钉住列表：列表只能消掉段数错位，每段的 `getSerializedSize()` 与 `write()` 之间
 * 仍可能不一致。钉住序列化结果之后，容量与内容出自同一次取样。
 *
 * <p>线程：只在该包自己的构造器里 pin 与 take，两次调用同线程相邻，所以用 ThreadLocal 即可，不需要
 * 可见性论证。按 chunk 键匹配，别的 chunk 的残留不会被取走。`clear` 供发送入口的收尾兜底。
 */
public final class ChunkPayloadPin {

    private record Pin(long chunkKey, byte[] bytes) {
    }

    private static final ThreadLocal<Pin> PIN = new ThreadLocal<>();

    private ChunkPayloadPin() {
    }

    /** 钉住该 chunk 的包体。同线程同时只留一份，后来的覆盖先前的。 */
    public static void pin(long chunkKey, byte[] bytes) {
        PIN.set(new Pin(chunkKey, bytes));
    }

    /** 取走该 chunk 的包体并清掉；键不符或没有钉住时返回 null。 */
    public static byte[] take(long chunkKey) {
        Pin pin = PIN.get();
        if (pin == null || pin.chunkKey() != chunkKey) {
            return null;
        }
        PIN.remove();
        return pin.bytes();
    }

    /** 无条件清空。挂在发送入口的返回处，异常路径不留残余。 */
    public static void clear() {
        PIN.remove();
    }
}
