package com.inf.farlands.simulation.serialize;

/**
 * ssa 映射表的键与代码约定不符。
 *
 * <p>
 * 这是表内容错，不是可重试的瞬时失败：文件在、读得动，但键不是三段定宽十六进制。它必须中止，不能按未命中重建成
 * 新段，因为重建会把盘上那一份数据永久覆盖掉。
 *
 * <p>
 * 因此包住 {@code findPath}、{@code pathForWrite}、{@code slotAt} 的宽 catch 要放行本类型，见
 * {@code SimulationLifecycle} 的三处。放行之后它会一路落进服务端 tick 的异常处理，产生一条崩溃报告。
 *
 * <p>
 * 触发点在打开世界时：{@code ServerLevelMixin} 建完系统后读一次映射表，让世界打不开，而不是先进去再崩。
 */
public final class SsaMappingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SsaMappingException(String message) {
        super(message);
    }

    public SsaMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
