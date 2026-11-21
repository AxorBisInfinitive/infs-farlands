package com.inf.farlands.simulation.serialize;

/**
 * ssa 映射表的内容与代码约定不符。
 *
 * <p>
 * 这是表内容错，不是可重试的瞬时失败：文件在、读得动，但内容不是这张表该有的形状。二进制格式之下具体是六种：
 * magic 不符、版本不符、文件长度与头部不符、条目数与压缩长度对不上、条目重复，以及编号空间耗尽。
 *
 * <p>
 * 旧档也抛本类型：{@code mapping.json} 在而 {@code mapping.dat} 不在时表示这是一份不可兼容的旧表。不能静默重建，
 * 重建会让编号从零开始，新编号撞上旧的 {@code s.<id>.ssa}，读到坐标不符即按未命中重生成，然后覆写旧文件。
 *
 * <p>
 * 它必须中止，不能按未命中重建成新段，因为重建会把盘上那一份数据永久覆盖掉。因此包住 {@code findPath}、
 * {@code pathForWrite}、{@code slotAt} 的宽 catch 要放行本类型，见 {@code SimulationLifecycle} 的三处。放行之后它会
 * 一路落进服务端 tick 的异常处理，产生一条崩溃报告。
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
