package com.inf.farlands.terrain.terrainFiller;

import net.minecraft.server.level.ServerLevel;

/**
 * 构造 NoiseChunk 时是哪一个 level，ThreadLocal，genPool 线程隔离。
 *
 * <p>NoiseChunk 的构造器既没有 level 也没有 ChunkAccess，而它的构造过程要按当前 level 的地形系统替换
 * router 与 aquifer、按当前 level 的群系系统取群系，所以构造点先把 level 放进来、构造完成后清掉。
 *
 * <p>载荷是 level 而不是某一个系统：五族都挂在 level 上，放进 level 就够取到全部，也不必为「哪个系统
 * 需要哪一份上下文」另开钩子。
 *
 * <p>「不替换」用一枚标志表达，不用一个假系统：结构基座自己算 Y 锚时要用传入的 router 与默认 aquifer，
 * 那一次的 level 仍然是真的，被问到的群系也仍然是真的，只是不换件。
 *
 * <p>两条边界：一是必须由构造点 set，漏了就会在 {@link #get()} 抛；二是 set 与构造必须同线程，构造调用
 * 内联执行，ThreadLocal 无需可见性论证。
 */
public final class TerrainSystemContext {

    private static final ThreadLocal<ServerLevel> LEVEL = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> NO_REPLACE = new ThreadLocal<>();

    private TerrainSystemContext() {
    }

    /** 构造点前调用：本次构造按该 level 的地形系统替换 router 与 aquifer。 */
    public static void set(ServerLevel level) {
        LEVEL.set(level);
        NO_REPLACE.remove();
    }

    /** 构造点前调用：本次构造不替换 router 与 aquifer，按传入的那一份走。 */
    public static void setVanilla(ServerLevel level) {
        LEVEL.set(level);
        NO_REPLACE.set(Boolean.TRUE);
    }

    public static void clear() {
        LEVEL.remove();
        NO_REPLACE.remove();
    }

    /** 本次构造是否要求不替换 router 与 aquifer。 */
    public static boolean noReplace() {
        return NO_REPLACE.get() != null;
    }

    /**
     * 当前 level，未设置返回 null。给上下文可以缺席的调用点用，例如调试屏那次用系统 router 求值；
     * 构造路径仍走 {@link #get()}，那条路要求漏设立刻炸出来。
     */
    public static ServerLevel getOrNull() {
        return LEVEL.get();
    }

    /** 当前正在构造 NoiseChunk 的 level。未设置即抛：只有构造路径会读它。 */
    public static ServerLevel get() {
        ServerLevel level = LEVEL.get();
        if (level == null) {
            throw new IllegalStateException(
                    "TerrainSystemContext not set: the NoiseChunk construction site missed set");
        }
        return level;
    }
}
