package com.inf.farlands.simulation.int64.noise;

/**
 * 按 cell 求值的密度标记，对标 vanilla 的 Marker(Interpolated, wrapped)。
 *
 * <p>存在的理由只有一条：让填充器能区分「该按 cell 采样再插值」与「必须逐块求值」。标记者由
 * CellSampler 走角点加三线性插值，未标记者维持逐块，因此新增这个接口不改变任何既有系统的取值。
 *
 * <p>语义与 vanilla 一致：标记本身不改值，也不改 compute，只描述消费侧应当如何采样它。
 */
public interface InterpolatedDensity extends Density {
}
