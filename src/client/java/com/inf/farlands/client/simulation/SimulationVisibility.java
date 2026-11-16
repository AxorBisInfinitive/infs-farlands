package com.inf.farlands.client.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import com.inf.farlands.client.render.AnchoredFrustum;
import com.inf.farlands.util.world.WorldBounds;
import com.inf.long2.ints.Int1024;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;

/**
 * 模拟层的遮挡剔除，按原版 {@code SectionOcclusionGraph} 的形态组织：传播在后台线程、由图输入驱动，可见集是视锥
 * 裁剪的产物，裁剪只在图更新或相机转动越过 2 度时重跑。
 *
 * <p>
 * 传播的输入由主线程拍成快照：段的三轴、掩码与 snap 拷进任务自己的数组，后台只读这份数组。原版的
 * {@code GraphState} 是同一个做法，理由也一样：{@code SimulationSection} 的字段由主线程写，后台直接读就是跨线程读。
 *
 * <p>
 * 一次只允许一个任务在途。在途期间主线程可以继续推高请求计数，任务结束时按「请求计数是否还等于自己抓的那个」决定要
 * 不要再跑一轮，所以脏位不会丢，也不会堆出多个任务。
 *
 * <p>
 * 传播内部用「相对任务 base」的打包键：每轴 8 位、偏置 128，三轴拼进一个 long。绝对段号是 long 全域，打包不进去；
 * 相对量的上界只由窗口决定，八位足够。越界用 {@link #PACK_OUT_OF_RANGE} 表达，不截断也不回绕。
 *
 * <p>
 * 主线程读写本类的字段，后台任务只碰自己的 {@link Job} 与局部容器；两侧通过 volatile 的 {@link #current}、
 * {@link #submitted}、{@link #inFlight} 交接。
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
     * 远景门：当前段离相机段超过这个段数就逐候选做直线校验。
     *
     * <p>
     * 判据取自原版 {@code SectionOcclusionGraph.runUpdates} 的 {@code distantFromCamera}，与父段是否全开无关。原版
     * 这一支受 {@code smartCull} 开关控制，本类同源：开关为假时整支跳过，见 {@link #farlands$smartCull}。
     */
    private static final long RAY_GATE_SECTIONS = 4L;

    /** 超过这个段数差才做直线校验，取 60 格折成段数，与原版同阈值。 */
    private static final long RAY_MIN_SECTIONS = 3;

    /** 直线校验的步长：一段的体对角线向上取整，保证每步至少跨过一段，不会原地打转。 */
    private static final double RAY_STEP = Math.ceil(Math.sqrt(3.0) * 16.0);

    /** 60 格的平方。距相机这个距离以内不做直线校验。 */
    private static final double RAY_MIN_DISTANCE_SQ = 3600.0;

    /** 直线校验的步数上限，只为让非有限坐标也能终止。 */
    private static final int RAY_MAX_STEPS = 4096;

    /** 打包键每轴的位宽，偏置之后落在 0 到 255。 */
    private static final int PACK_BITS = 8;

    /** 打包键每轴的偏置。 */
    private static final long PACK_BIAS = 128L;

    /** 打包键的掩码，低 24 位。 */
    private static final long PACK_MASK = (1L << (PACK_BITS * 3)) - 1L;

    /** 越界哨兵。合法键恒在 0 到 2^24-1。 */
    private static final long PACK_OUT_OF_RANGE = -1L;

    /** 相机段自身的打包键。 */
    private static final long BASE_KEY = (PACK_BIAS << 16) | (PACK_BIAS << 8) | PACK_BIAS;

    /**
     * {@code sources} 的缺省值，即「未到达」。
     *
     * <p>
     * 存储值是方向集加一，所以 0 只表示缺省：根节点的方向集本身就是 0，用 0 当缺省就分不开「在表里且来路为空」与
     * 「不在表里」。
     */
    private static final int SOURCES_ABSENT = 0;

    /** 队列条目里方向所占的位移。低 24 位是打包键，第 32 位起放方向加一。 */
    private static final int QUEUE_SOURCE_SHIFT = 32;

    /** 裁剪粒度：相机转动按 2 度取整比较，取自原版 {@code LevelRenderer} 的同一判据。 */
    private static final float ROTATION_STEP = 2.0F;

    /** 相机后退出 8 格立方的粒度。与 vanilla 的 {@code LevelRenderer.offsetFrustum} 同值。 */
    private static final int CAMERA_CUBE = 8;

    /** 一次调用要用的相机与视锥，全部由主线程给出。 */
    record View(Int1024 sectionX, Int1024 sectionY, Int1024 sectionZ, double inSectionX, double inSectionY,
            double inSectionZ, Int1024 cameraBlockX, Int1024 cameraBlockY, Int1024 cameraBlockZ, float xRot,
            float yRot, AnchoredFrustum frustum) {
    }

    /**
     * 一次传播的产物，不可变。
     *
     * <p>
     * {@code keys} 是按图自己的 base 打包的「到达过」的全集，供 {@link #containsVisible} 用；{@code snaps} 只留 snap
     * 非空的段，供裁剪用。两组都按同一个 base 打包，所以相机跨段之后仍能与图里的键对上。
     */
    private record Graph(Int1024 baseX, Int1024 baseY, Int1024 baseZ, LongOpenHashSet keys,
            Simulation.SnapSection[] snaps) {
    }

    /** 一次传播的输入。主线程填，后台只读。 */
    private record Job(Int1024 baseX, Int1024 baseY, Int1024 baseZ, double cameraX, double cameraY, double cameraZ,
            int generation, int request, boolean smartCull, long[] packed, long[] masks,
            Simulation.SnapSection[] snaps, long[] seedKeys, int[] seedDirections) {
    }

    /** 已发布的图。后台写，主线程读。 */
    private volatile Graph current;

    /** 主线程推上去的请求计数；每次图输入或相机段变化都加一。 */
    private volatile int requested;

    /** 后台任务抓走的那一个请求计数。与 {@link #requested} 不等说明该再跑一轮。 */
    private volatile int submitted;

    /** 是否有任务在途。 */
    private volatile boolean inFlight;

    /** 关卡代次。换关卡时加一，在途任务的结果按它丢弃。 */
    private volatile int generation;

    /** 裁剪集：传播结果经视锥过滤后的产物，只主线程读写。 */
    private List<Simulation.SnapSection> culled = List.of();

    private int culledVersion;

    /** 图更新之后还没重裁过。 */
    private boolean needsCull = true;

    /** 上次裁剪时的相机转动角，用于判断要不要重裁。 */
    private float lastXRot = Float.NaN;

    private float lastYRot = Float.NaN;

    /** 上次请求时看到的图输入版本与相机段，用来决定要不要推高请求计数。 */
    private int lastGraphVersion = -1;

    /** 上次请求时用的剔除开关。它变了要整图重算，与 vanilla 的 {@code invalidate} 同义。 */
    private boolean lastSmartCull = true;

    private Int1024 lastSectionX;

    private Int1024 lastSectionY;

    private Int1024 lastSectionZ;

    /** 每帧调一次：按需提交后台传播、按需重裁，返回裁剪集。 */
    List<Simulation.SnapSection> update(Simulation simulation, View view) {
        boolean smartCull = farlands$smartCull(simulation, view);
        boolean moved = this.lastSectionX == null || !view.sectionX().equals(this.lastSectionX)
                || !view.sectionY().equals(this.lastSectionY) || !view.sectionZ().equals(this.lastSectionZ);
        int version = simulation.graphVersion();
        if (version != this.lastGraphVersion || moved || smartCull != this.lastSmartCull) {
            this.lastGraphVersion = version;
            this.lastSmartCull = smartCull;
            this.lastSectionX = view.sectionX();
            this.lastSectionY = view.sectionY();
            this.lastSectionZ = view.sectionZ();
            this.requested++;
        }
        if (!this.inFlight && this.submitted != this.requested) {
            this.farlands$submit(simulation, view);
        }
        this.farlands$cull(view);
        return this.culled;
    }

    /**
     * 该不该做聪明剔除，判据照抄 vanilla 的 {@code LevelRenderer.cullTerrain}：取 {@code Minecraft.smartCull}
     * 这个开关，旁观者且相机所在方块是实心渲染时强制关掉。
     *
     * <p>
     * 关掉之后面掩码与远景直线校验都不做，整窗可达。相机穿在实心方块里时相机段的面掩码是全闭的，照开着的判据
     * 推不出任何邻段，整片就是黑的；vanilla 那个 {@code isSolidRender} 分支正是为此写的。
     */
    private static boolean farlands$smartCull(Simulation simulation, View view) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean smartCull = minecraft.smartCull;
        Player player = minecraft.player;
        ClientLevel level = simulation.level();
        if (!smartCull || player == null || level == null || !player.isSpectator()) {
            return smartCull;
        }
        Int1024 x = view.cameraBlockX();
        Int1024 y = view.cameraBlockY();
        Int1024 z = view.cameraBlockZ();
        if (!WorldBounds.inBlockAbsolute(x, y, z)) {
            return smartCull;
        }
        return !level.getBlockState(new BlockPos(x.toInt(), y.toInt(), z.toInt())).isSolidRender();
    }

    /** 裁剪集内容的版本，供渲染批次缓存判重。 */
    int culledVersion() {
        return this.culledVersion;
    }

    /**
     * 该段此刻是否在传播结果里，即从相机段出发、经可穿过的面能不能走到它。
     *
     * <p>
     * 按图自带的 base 打包，不用当前相机段：图在途期间相机可能已经跨段，用当前段打包会与图里的键对不上。图还没发布时
     * 返回假，调用方据此退回「不区分可达性」的顺序。
     */

    boolean containsVisible(Int1024 sectionX, Int1024 sectionY, Int1024 sectionZ) {
        Graph graph = this.current;
        if (graph == null) {
            return false;
        }
        long key = farlands$pack(sectionX.subtract(graph.baseX()).toLong(),
                sectionY.subtract(graph.baseY()).toLong(), sectionZ.subtract(graph.baseZ()).toLong());
        return key != PACK_OUT_OF_RANGE && graph.keys().contains(key);
    }

    /** 作废：换关卡或清空段表时调。在途任务的结果按代次丢弃。 */
    void invalidate() {
        this.generation++;
        this.current = null;
        this.culled = List.of();
        this.culledVersion++;
        this.needsCull = true;
        this.lastGraphVersion = -1;
        this.lastSectionX = null;
        this.lastSectionY = null;
        this.lastSectionZ = null;
        this.lastXRot = Float.NaN;
        this.lastYRot = Float.NaN;
    }

    /** 拍快照并交给后台。主线程调。 */
    private void farlands$submit(Simulation simulation, View view) {
        int captured = this.requested;
        int capturedGeneration = this.generation;
        boolean absent = simulation.sectionOrNull(view.sectionX(), view.sectionY(), view.sectionZ()) == null;
        int faceX = absent ? farlands$face(view.sectionX()) : 0;
        int faceY = absent ? farlands$face(view.sectionY()) : 0;
        int faceZ = absent ? farlands$face(view.sectionZ()) : 0;
        boolean seeding = faceX != 0 || faceY != 0 || faceZ != 0;
        int count = simulation.sectionMap().size();
        long[] packed = new long[count];
        long[] masks = new long[count];
        Simulation.SnapSection[] snaps = new Simulation.SnapSection[count];
        int[] seedDirections = new int[count];
        int at = 0;
        for (SimulationSection section : simulation.sectionMap().values()) {
            // 相对任务的基段：窗口内差不超过几十，收窄无损。
            packed[at] = farlands$pack(section.sectionX().subtract(view.sectionX()).toLong(),
                    section.sectionY().subtract(view.sectionY()).toLong(),
                    section.sectionZ().subtract(view.sectionZ()).toLong());
            masks[at] = section.visibility();
            snaps[at] = section.snap();
            if (seeding && packed[at] != PACK_OUT_OF_RANGE && section.eligible()) {
                seedDirections[at] = farlands$seedDirections(faceX, faceY, faceZ, section, view);
            }
            at++;
        }
        int seeded = 0;
        for (int ordinal = 0; ordinal < count; ordinal++) {
            if (seedDirections[ordinal] != 0) {
                seeded++;
            }
        }
        long[] seedKeys = new long[seeded];
        int[] seeds = new int[seeded];
        int write = 0;
        for (int ordinal = 0; ordinal < count; ordinal++) {
            if (seedDirections[ordinal] != 0) {
                seedKeys[write] = packed[ordinal];
                seeds[write] = seedDirections[ordinal];
                write++;
            }
        }
        Job job = new Job(view.sectionX(), view.sectionY(), view.sectionZ(), view.inSectionX(), view.inSectionY(),
                view.inSectionZ(), capturedGeneration, captured, this.lastSmartCull, packed, masks, snaps, seedKeys,
                seeds);
        this.inFlight = true;
        CompletableFuture.runAsync(() -> this.farlands$propagate(job), Util.backgroundExecutor());
    }

    /** 相机段贴哪一个面：返回 +1 表示贴着正界，-1 贴着负界，0 不贴。 */
    private static int farlands$face(Int1024 section) {
        if (section.compareTo(Int1024.of((long) WorldBounds.MAX_PLAYABLE_SECTION)) == 0) {
            return 1;
        }
        if (section.compareTo(Int1024.of((long) WorldBounds.MIN_PLAYABLE_SECTION)) == 0) {
            return -1;
        }
        return 0;
    }

    /**
     * 该段作为种子时要带的源方向位；0 表示不是种子。
     *
     * <p>
     * 判据照 vanilla {@code initializeQueueForFullUpdate} 的平面播种，推广到三轴：贴面那一轴取「离开相机」的方向，
     * 另两轴按种子相对相机的符号补横向方向。vanilla 的世界只有 Y 有界，所以它只播 UP 或 DOWN 那一层；本 port 的
     * 可玩界是三轴盒子，贴面的轴可以是一到三根。
     */
    private static int farlands$seedDirections(int faceX, int faceY, int faceZ, SimulationSection section, View view) {
        int[] signs = { faceX, faceY, faceZ };
        Int1024[] camera = { view.sectionX(), view.sectionY(), view.sectionZ() };
        Int1024[] own = { section.sectionX(), section.sectionY(), section.sectionZ() };
        boolean[] onFaceAxis = new boolean[3];
        int directions = 0;
        for (int axis = 0; axis < 3; axis++) {
            if (signs[axis] == 0) {
                continue;
            }
            if (own[axis].compareTo(camera[axis].add(Int1024.of((long) signs[axis]))) != 0) {
                continue;
            }
            onFaceAxis[axis] = true;
            directions |= 1 << farlands$directionBit(axis, signs[axis]);
        }
        if (directions == 0) {
            return 0;
        }
        for (int axis = 0; axis < 3; axis++) {
            if (onFaceAxis[axis]) {
                continue;
            }
            int compared = own[axis].compareTo(camera[axis]);
            if (compared != 0) {
                directions |= 1 << farlands$directionBit(axis, compared > 0 ? 1 : -1);
            }
        }
        return directions;
    }

    /** 第 axis 轴朝 sign 那一侧的方向在 {@code Direction} 里的序数。轴序是 X、Y、Z。 */
    private static int farlands$directionBit(int axis, int sign) {
        Direction.Axis directionAxis = Direction.Axis.values()[axis];
        return Direction.fromAxisAndDirection(directionAxis,
                sign > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE).ordinal();
    }

    /** 后台：从快照算出一张图并发布。只碰 {@code job} 与局部容器。 */
    private void farlands$propagate(Job job) {
        Long2IntOpenHashMap indexOf = new Long2IntOpenHashMap(job.packed().length);
        for (int i = 0; i < job.packed().length; i++) {
            if (job.packed()[i] != PACK_OUT_OF_RANGE) {
                indexOf.put(job.packed()[i], i);
            }
        }
        Long2IntOpenHashMap sources = new Long2IntOpenHashMap();
        LongArrayList order = new LongArrayList();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

        if (indexOf.getOrDefault(BASE_KEY, -1) >= 0) {
            sources.put(BASE_KEY, 1);
            order.add(BASE_KEY);
            queue.enqueue(BASE_KEY | ((long) (NO_SOURCE + 1) << QUEUE_SOURCE_SHIFT));
        } else if (job.seedKeys().length > 0) {
            // 相机段缺席：按 vanilla 的平面播种。每个种子带一组源方向，逐位各入队一次。
            for (int index = 0; index < job.seedKeys().length; index++) {
                long seed = job.seedKeys()[index];
                int directions = job.seedDirections()[index];
                if (directions == 0 || indexOf.getOrDefault(seed, -1) < 0) {
                    continue;
                }
                int have = sources.getOrDefault(seed, SOURCES_ABSENT);
                int bits = (have == SOURCES_ABSENT ? 0 : have - 1) | directions;
                sources.put(seed, bits + 1);
                if (have == SOURCES_ABSENT) {
                    order.add(seed);
                }
                for (int ordinal = 0; ordinal < FACINGS; ordinal++) {
                    if ((directions & (1 << ordinal)) != 0) {
                        queue.enqueue(seed | ((long) (ordinal + 1) << QUEUE_SOURCE_SHIFT));
                    }
                }
            }
        }

        while (queue.size() > 0) {
            long entry = queue.dequeueLong();
            long key = entry & PACK_MASK;
            int source = (int) (entry >>> QUEUE_SOURCE_SHIFT) - 1;
            int at = indexOf.getOrDefault(key, -1);
            if (at < 0) {
                continue;
            }
            long mask = job.masks()[at];
            // 远景门按原版：看当前段离相机段的段数差，与父段是否全开无关。
            boolean distant = Math.abs(farlands$unpackX(key)) > RAY_GATE_SECTIONS
                    || Math.abs(farlands$unpackY(key)) > RAY_GATE_SECTIONS
                    || Math.abs(farlands$unpackZ(key)) > RAY_GATE_SECTIONS;
            for (Direction exit : DIRECTIONS) {
                // 开关为假时面掩码不参与，与 vanilla 的 {@code !smartCull ||} 同义。
                if (job.smartCull() && source != NO_SOURCE && !farlands$passes(mask, source, exit.ordinal())) {
                    continue;
                }
                long next = farlands$step(key, exit);
                if (next == PACK_OUT_OF_RANGE || indexOf.getOrDefault(next, -1) < 0) {
                    continue;
                }
                int bit = 1 << exit.ordinal();
                int have = sources.getOrDefault(next, SOURCES_ABSENT);
                if (have == SOURCES_ABSENT) {
                    if (job.smartCull() && distant
                            && !farlands$rayClear(next, indexOf, sources, job.cameraX(), job.cameraY(),
                                    job.cameraZ())) {
                        continue;
                    }
                    sources.put(next, bit + 1);
                    order.add(next);
                    queue.enqueue(next | ((long) (exit.ordinal() + 1) << QUEUE_SOURCE_SHIFT));
                } else if (((have - 1) & bit) == 0) {
                    sources.put(next, ((have - 1) | bit) + 1);
                    queue.enqueue(next | ((long) (exit.ordinal() + 1) << QUEUE_SOURCE_SHIFT));
                }
            }
        }

        int size = order.size();
        LongOpenHashSet keys = new LongOpenHashSet(size);
        Simulation.SnapSection[] snapsOut = new Simulation.SnapSection[size];
        int kept = 0;
        for (int i = 0; i < size; i++) {
            long key = order.getLong(i);
            keys.add(key);
            int at = indexOf.getOrDefault(key, -1);
            if (at < 0) {
                continue;
            }
            Simulation.SnapSection snap = job.snaps()[at];
            if (snap != null) {
                snapsOut[kept] = snap;
                kept++;
            }
        }
        Graph graph = new Graph(job.baseX(), job.baseY(), job.baseZ(), keys, Arrays.copyOf(snapsOut, kept));
        this.inFlight = false;
        this.submitted = job.request();
        if (job.generation() == this.generation) {
            this.current = graph;
            this.needsCull = true;
        }
    }

    /**
     * 主线程：按需重裁。
     *
     * <p>
     * 重裁条件是「图发布过但还没裁」或「相机转动跨过 2 度」。平移不触发：图本身随相机段变化重算，段内平移对裁剪的
     * 影响小于一次裁剪的成本。这两条与 vanilla 的 {@code applyFrustum} 判据同源。
     *
     * <p>
     * 判定用后退视锥：先拷一份，再让相机沿视线后退到 8 格立方完全落进锥内，与 vanilla 的
     * {@code offsetFrustum} 同法。近平面因此退到相机后面若干格，相机附近与相机所在段不会被近平面剔掉。
     */
    private void farlands$cull(View view) {
        Graph graph = this.current;
        if (graph == null) {
            return;
        }
        boolean rotationMoved = Math.floor(view.xRot() / ROTATION_STEP) != Math.floor(this.lastXRot / ROTATION_STEP)
                || Math.floor(view.yRot() / ROTATION_STEP) != Math.floor(this.lastYRot / ROTATION_STEP);
        if (!this.needsCull && !rotationMoved) {
            return;
        }
        this.lastXRot = view.xRot();
        this.lastYRot = view.yRot();
        this.needsCull = false;
        AnchoredFrustum frustum = view.frustum() == null ? null
                : (AnchoredFrustum) new Frustum((Frustum) view.frustum()).offsetToFullyIncludeCameraCube(CAMERA_CUBE);
        Simulation.SnapSection[] snaps = graph.snaps();
        List<Simulation.SnapSection> next = new ArrayList<>(snaps.length);
        for (Simulation.SnapSection snap : snaps) {
            if (frustum == null || frustum.farlands$isVisibleSection(snap.originX(), snap.originY(), snap.originZ(),
                    SimulationSection.SIZE)) {
                next.add(snap);
            }
        }
        this.culled = List.copyOf(next);
        this.culledVersion++;
    }

    /** 相对任务 base 的三轴偏移打包成一个 long，越界返回 {@link #PACK_OUT_OF_RANGE}。 */
    private static long farlands$pack(long dx, long dy, long dz) {
        if (((dx + PACK_BIAS) >>> PACK_BITS) != 0L || ((dy + PACK_BIAS) >>> PACK_BITS) != 0L
                || ((dz + PACK_BIAS) >>> PACK_BITS) != 0L) {
            return PACK_OUT_OF_RANGE;
        }
        return ((dx + PACK_BIAS) << 16) | ((dy + PACK_BIAS) << 8) | (dz + PACK_BIAS);
    }

    private static long farlands$unpackX(long key) {
        return ((key >> 16) & 0xFFL) - PACK_BIAS;
    }

    private static long farlands$unpackY(long key) {
        return ((key >> 8) & 0xFFL) - PACK_BIAS;
    }

    private static long farlands$unpackZ(long key) {
        return (key & 0xFFL) - PACK_BIAS;
    }

    /** 沿该方向走一段。必须解包再打包：直接对打包值加偏移会让字段串位。 */
    private static long farlands$step(long key, Direction direction) {
        return farlands$pack(farlands$unpackX(key) + direction.getStepX(),
                farlands$unpackY(key) + direction.getStepY(),
                farlands$unpackZ(key) + direction.getStepZ());
    }

    /**
     * 来路是 {@code source} 这一行进方向时，能不能从 {@code exit} 这个面穿出去。
     *
     * <p>
     * 来路方向是「从上一段走进本段」的方向，所以入口面是它的反面，与原版传 {@code getOpposite()} 的做法一致。
     */
    private static boolean farlands$passes(long mask, int source, int exit) {
        Direction entry = DIRECTIONS[source].getOpposite();
        return (mask & (1L << (entry.ordinal() + exit * FACINGS))) != 0;
    }

    /**
     * 直线校验：从该段离相机最近的一点朝相机走一条直线，路上若穿过「存在但没被传播到」的段，判这一段是漏出来的。
     *
     * <p>
     * 掩码只答「能不能从某个面穿到某个面」，不答开口在哪，所以一串开口错开的段也能连成通路。它必须跑在入队之前：
     * 一路上「已经到达」的那批节点，正是这个时序给出的约束。缺段不算证据，按穿过处理。
     *
     * <p>
     * 判否只作用于当前这一条边：调用方直接跳过，不写任何记号，所以别的边还能从更近的父段重新尝试并放行。这与原版
     * 一致，代价是同一节点可能被多条边各判一次，上界是边数乘步进。
     */
    private static boolean farlands$rayClear(long key, Long2IntOpenHashMap indexOf, Long2IntOpenHashMap sources,
            double cameraX, double cameraY, double cameraZ) {
        long offsetX = farlands$unpackX(key);
        long offsetY = farlands$unpackY(key);
        long offsetZ = farlands$unpackZ(key);
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
            long probed = farlands$pack(Math.floorDiv((long) Math.floor(probeX), 16L),
                    Math.floorDiv((long) Math.floor(probeY), 16L),
                    Math.floorDiv((long) Math.floor(probeZ), 16L));
            if (probed == PACK_OUT_OF_RANGE) {
                continue;
            }
            int seen = sources.getOrDefault(probed, SOURCES_ABSENT);
            if (indexOf.getOrDefault(probed, -1) >= 0 && seen == SOURCES_ABSENT) {
                return false;
            }
        }
        return true;
    }
}
