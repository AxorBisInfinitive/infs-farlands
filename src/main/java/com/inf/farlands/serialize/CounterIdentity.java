package com.inf.farlands.serialize;

/**
 * 记账身份的取值入口，由 ChunkAccessMixin 注入到 ChunkAccess。
 *
 * <p>身份在 chunk 构造末尾分配，只给服务端的 LevelChunk。0 表示未分配，也是 ProtoChunk 与客户端
 * chunk 的值：前者的段由它变成 LevelChunk 时那次统一扫补记，后者不参与记账。
 */
public interface CounterIdentity {

    /** 本 chunk 的记账身份；0 表示未分配。 */
    long counterId();
}
