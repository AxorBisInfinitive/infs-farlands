package com.inf.farlands.simulation;

import com.inf.farlands.simulation.int64.SimulationNoiseSystem;
import com.inf.farlands.simulation.int64.system.terrain.noise.misc.Void.VoidSimulationNoiseSystem;

/**
 * 模拟层的系统选择，硬编码，不做参数。
 *
 * <p>换系统只改 {@link #SELECTED} 一处。系统实例在首次取用时建，之后复用：系统是无状态的，构造里只有常量。
 * 填充器不认识这个枚举，它在填充时按 {@link #SELECTED} 现取。
 */
public enum SimulationSystem {

    /** 全空气，与 terrain 的 VoidNoiseSystem 同形。 */
    VOID_NOISE;

    /** 当前选中的系统。 */
    public static final SimulationSystem SELECTED = VOID_NOISE;

    private SimulationNoiseSystem system;

    public SimulationNoiseSystem system() {
        SimulationNoiseSystem cached = this.system;
        if (cached == null) {
            cached = this.create();
            this.system = cached;
        }
        return cached;
    }

    private SimulationNoiseSystem create() {
        return switch (this) {
            case VOID_NOISE -> new VoidSimulationNoiseSystem();
        };
    }
}
