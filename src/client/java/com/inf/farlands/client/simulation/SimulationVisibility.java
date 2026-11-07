package com.inf.farlands.client.simulation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Direction;

/**
 * 模拟层的遮挡剔除：从相机所在的 Section 起，按每段的面可见性做广度优先传播，产出本帧要画的 Section 列表。
 *
 * <p>
 * 输入是每段的 36 位掩码，由 {@link SimulationMesher} 在烘制期用 {@code VisGraph} 算出，含义与原版
 * {@code VisibilitySet} 相同，即「从某一个面进、能不能从另一个面出去」。传播照抄原版
 * {@code SectionOcclusionGraph}：节点记着来路的行进方向，向六个邻段推进时用当前段的掩码判能不能穿过那个面，
 * 能穿过的邻段才进列表。
 *
 * <p>
 * 三条按开处理的规则，方向一律是少剔：掩码缺省 {@link #OPEN}，空段在 {@code applyAir} 里被置成全开，缺段则
 * 只跳过它自己不往下传，因为缺段本来就没有几何可画，等它到达时调用方会把可见集置脏重算。
 *
 * <p>
 * 只在客户端主线程调。段表是普通 HashMap，而客户端 tick、包分发与渲染同线程。
 */
final class SimulationVisibility {

    /** 全开掩码。编译期常量，{@link SimulationSection} 引用它不触发类初始化。 */
    static final long OPEN = -1L;

    /** 六面，与原版 {@code VisibilitySet} 的 6 乘 6 位矩阵同宽。 */
    private static final int FACINGS = 6;

    private static final Direction[] DIRECTIONS = Direction.values();

    /** 没有来路方向：种子节点用，向六个邻段推进时不判穿面。 */
    private static final int NO_SOURCE = -1;

    /**
     * 被门判掉的记号，写在 {@code sources} 里。
     *
     * <p>
     * 用它与「到达过的位集」区分开：到达过的节点值必为非负的位集，记号是负数，所以直线校验与再入队都能认出它，
     * 同一个节点也只会被判一次。
     */
    private static final int REJECTED = -1;

    /** 超过这个段数差才做直线校验，取 60 格折成段数，与原版 {@code SectionOcclusionGraph} 同阈值。 */
    private static final long RAY_MIN_SECTIONS = 3;

    /** 直线校验的步长：一段的体对角线向上取整，保证每步至少跨过一段，不会原地打转。 */
    private static final double RAY_STEP = Math.ceil(Math.sqrt(3.0) * 16.0);

    /** 60 格的平方。距相机这个距离以内不做直线校验，近处本来也不会被遮住多少。 */
    private static final double RAY_MIN_DISTANCE_SQ = 3600.0;

    /** 直线校验的步数上限。正常相机几十步内就收敛，这段只为让非有限坐标也能终止。 */
    private static final int RAY_MAX_STEPS = 4096;

    /** 一个待处理的 Section 与它的来路行进方向，取值为 {@code Direction.ordinal()} 或 {@link #NO_SOURCE}。 */
    private record Node(Simulation.SectionKey key, int source) {
    }

    /** 上一次是否算过，以及是不是以该相机 Section 为基准。 */
    private boolean built;

    private long baseX;

    private long baseY;

    private long baseZ;

    /** 上一次算出的可见集，按传播的出队顺序，即近似由近到远。 */
    private List<Simulation.SnapSection> sections = List.of();

    /**
     * 上一次算出的可见集。顺序即 draw 顺序，近的在前对早期深度测试有利。
     *
     * <p>
     * 作废之后返回空表而不是旧表：换关卡时 {@code invalidate} 只清标志，字段里还留着上一个关卡的键，
     * 而烘制那一侧会拿这张表去挑候选。
     */
    List<Simulation.SnapSection> sections() {
        return this.built ? this.sections : List.of();
    }

    /** 上一次的结果是不是以该相机 Section 为基准。 */
    boolean matches(long sectionX, long sectionY, long sectionZ) {
        return this.built && this.baseX == sectionX && this.baseY == sectionY && this.baseZ == sectionZ;
    }

    /** 作废：换关卡或清空段表时调，下一次取用必然重算。 */
    void invalidate() {
        this.built = false;
    }

    /**
     * 重算可见集。
     *
     * <p>
     * 相机所在 Section 存在就从它出发，来路方向取 {@link #NO_SOURCE}，向六个邻段推进时不判穿面；不存在则从它
     * 的六个邻段出发，来路方向取从相机 Section 指向该邻段的那一方向，于是该邻段自己的掩码照常参与判定。
     *
     * <p>
     * 每个「Section 加来路方向」的组合最多入队一次，所以单个 Section 最多处理六次，边数是六向。
     *
     * <p>
     * 直线校验是传播途中的门，见 {@link #farlands$rayClear}：远处节点在全开父节点这一条边上、入队之前先跑这条路，
     * 不通过就不入图。门与后置过滤用同一个判据，强度差在时序：门问的是「路上每一段是否已经在建到一半的图里」，
     * 过滤问的是全图建完之后，那时路上每一段当然都在，等于没判。
     *
     * <p>
     * 门每个节点只判一次：被判掉的节点在 {@code sources} 里留一个 {@link #REJECTED} 记号，后续出口直接跳过，于是
     * 成本是「节点数乘步进」，不是「边数乘步进」。
     */
    void rebuild(Simulation simulation, long sectionX, long sectionY, long sectionZ,
            double cameraX, double cameraY, double cameraZ) {
        Map<Simulation.SectionKey, SimulationSection> store = simulation.sectionMap();
        Map<Simulation.SectionKey, Integer> sources = new HashMap<>();
        List<Simulation.SectionKey> order = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();

        Simulation.SectionKey base = new Simulation.SectionKey(sectionX, sectionY, sectionZ);
        if (store.containsKey(base)) {
            sources.put(base, 0);
            order.add(base);
            queue.add(new Node(base, NO_SOURCE));
        } else {
            for (Direction direction : DIRECTIONS) {
                Simulation.SectionKey seed = farlands$offset(base, direction);
                if (!store.containsKey(seed)) {
                    continue;
                }
                sources.put(seed, 1 << direction.ordinal());
                order.add(seed);
                queue.add(new Node(seed, direction.ordinal()));
            }
        }

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            SimulationSection section = store.get(node.key());
            if (section == null) {
                continue;
            }
            long mask = section.visibility();
            for (Direction exit : DIRECTIONS) {
                if (node.source() != NO_SOURCE && !farlands$passes(mask, node.source(), exit.ordinal())) {
                    continue;
                }
                Simulation.SectionKey next = farlands$offset(node.key(), exit);
                if (!store.containsKey(next)) {
                    continue;
                }
                int bit = 1 << exit.ordinal();
                Integer have = sources.get(next);
                if (have == null) {
                    if (mask == OPEN
                            && !farlands$rayClear(next, base, store, sources, cameraX, cameraY, cameraZ)) {
                        sources.put(next, REJECTED);
                        continue;
                    }
                    sources.put(next, bit);
                    order.add(next);
                    queue.add(new Node(next, exit.ordinal()));
                } else if (have != REJECTED && (have & bit) == 0) {
                    sources.put(next, have | bit);
                    queue.add(new Node(next, exit.ordinal()));
                }
            }
        }

        List<Simulation.SnapSection> visible = new ArrayList<>(order.size());
        for (Simulation.SectionKey key : order) {
            SimulationSection section = store.get(key);
            if (section == null) {
                continue;
            }
            Simulation.SnapSection snap = section.snap();
            if (snap != null) {
                visible.add(snap);
            }
        }
        this.sections = List.copyOf(visible);
        this.built = true;
        this.baseX = sectionX;
        this.baseY = sectionY;
        this.baseZ = sectionZ;
    }

    /**
     * 来路是 {@code source} 这一行进方向时，能不能从 {@code exit} 这个面穿出去。
     *
     * <p>
     * 来路方向是「从上一段走进本段」的方向，所以入口面是它的反面；这与原版 {@code SectionOcclusionGraph} 传
     * {@code DIRECTION[source].getOpposite()} 的做法一致。
     */
    private static boolean farlands$passes(long mask, int source, int exit) {
        Direction entry = DIRECTIONS[source].getOpposite();
        return (mask & (1L << (entry.ordinal() + exit * FACINGS))) != 0;
    }

    /**
     * 直线校验：从该 Section 离相机最近的一点朝相机走一条直线，路上若穿过「存在但没被传播到」的 Section，判这一段
     * 是传播漏出来的，不入图。
     *
     * <p>
     * 掩码只答「这个 Section 能不能从某个面穿到某个面」，不答开口在哪，所以一串开口错开的 Section 也能连成通路，
     * 表现为大片本来被挡住的东西被算成可见。这一遍用直线把这类通路掐掉，与原版对 60 格之外的 Section 做的那次步进
     * 同源。它必须跑在入队之前：一路上「已经到达」的那批节点，正是这个时序给出的约束。
     *
     * <p>
     * 缺段不算证据：它还没到达，判不了，按穿过处理。带 {@link #REJECTED} 记号的节点按「没到达」算，因为它已经被
     * 别的边判掉过一次。坐标全部用「相对相机所在 Section 原点」的量，量级只有几十格，double 在 int 之外也精确。
     */
    private static boolean farlands$rayClear(Simulation.SectionKey key, Simulation.SectionKey base,
            Map<Simulation.SectionKey, SimulationSection> store, Map<Simulation.SectionKey, Integer> reached,
            double cameraX, double cameraY, double cameraZ) {
        long offsetX = key.x() - base.x();
        long offsetY = key.y() - base.y();
        long offsetZ = key.z() - base.z();
        long span = Math.max(Math.abs(offsetX), Math.max(Math.abs(offsetY), Math.abs(offsetZ)));
        if (span <= RAY_MIN_SECTIONS) {
            return true;
        }
        double originX = offsetX * 16.0;
        double originY = offsetY * 16.0;
        double originZ = offsetZ * 16.0;
        double nearX = Math.clamp(cameraX, originX, originX + 16.0);
        double nearY = Math.clamp(cameraY, originY, originY + 16.0);
        double nearZ = Math.clamp(cameraZ, originZ, originZ + 16.0);
        double directionX = cameraX - nearX;
        double directionY = cameraY - nearY;
        double directionZ = cameraZ - nearZ;
        double distanceSq = directionX * directionX + directionY * directionY + directionZ * directionZ;
        if (distanceSq <= RAY_MIN_DISTANCE_SQ) {
            return true;
        }
        double length = Math.sqrt(distanceSq);
        double stepX = directionX / length * RAY_STEP;
        double stepY = directionY / length * RAY_STEP;
        double stepZ = directionZ / length * RAY_STEP;
        double probeX = nearX;
        double probeY = nearY;
        double probeZ = nearZ;
        for (int step = 0; step < RAY_MAX_STEPS; step++) {
            double remainX = cameraX - probeX;
            double remainY = cameraY - probeY;
            double remainZ = cameraZ - probeZ;
            if (remainX * remainX + remainY * remainY + remainZ * remainZ <= RAY_MIN_DISTANCE_SQ) {
                return true;
            }
            probeX += stepX;
            probeY += stepY;
            probeZ += stepZ;
            Simulation.SectionKey probed = new Simulation.SectionKey(
                    base.x() + Math.floorDiv((long) Math.floor(probeX), 16L),
                    base.y() + Math.floorDiv((long) Math.floor(probeY), 16L),
                    base.z() + Math.floorDiv((long) Math.floor(probeZ), 16L));
            Integer seen = reached.get(probed);
            if (store.get(probed) != null && (seen == null || seen == REJECTED)) {
                return false;
            }
        }
        return true;
    }

    private static Simulation.SectionKey farlands$offset(Simulation.SectionKey key, Direction direction) {
        return new Simulation.SectionKey(key.x() + direction.getStepX(), key.y() + direction.getStepY(),
                key.z() + direction.getStepZ());
    }
}
