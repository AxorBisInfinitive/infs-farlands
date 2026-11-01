package com.inf.farlands.mixin.position;

import com.google.common.collect.ImmutableList;
import com.inf.farlands.util.pos.ExactCollisionSource;
import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;
import com.inf.long2.binaries.Binary256;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.waypoints.WaypointTransmitter;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体位置的内部表示改成 256 位，出口仍是 double；移动与碰撞的加法在帧里做。
 *
 * <p>
 * 捕获点取 {@code setPosRaw} 的头部，它是位置唯一的写入口：正文里 {@code this.position} 的赋值只有构造器的
 * {@code Vec3.ZERO} 与这个方法两处。
 *
 * <p>
 * 帧是「锚点 long 加局部 double」。锚点在 {@code |坐标| < 1e12} 时取 0，这一段里局部等于世界，vanilla 的方块
 * 形状与本帧同源，行为与改动前逐位相同；超出后锚点取精确下取整，局部偏移落回 {@code [0, 1)}，而方块坐标是 int，
 * 那一段本来就没有 vanilla 方块，查询返回空。
 *
 * <p>
 * {@code 5e18} 处一个 ulp 是 1024，直接在 double 上加 0.1 会归零；在帧里加，局部偏移的分辨率是 1e-16 量级。
 *
 * <p>
 * 移动不覆写 {@code move}，只换掉它里面的两处位置写回。{@code collide} 与 {@code collectColliders} 则整体覆写：
 * 前者要拿局部盒做碰撞，后者要把世界盒递进 vanilla 查询，两者必须成对。
 * <p>
 * 优先级取 900，低于默认的 1000。原因是本条 `@Overwrite` 了 {@code collide}，而另有两条注入器要往这个方法里
 * 注入：{@code debug.tool.clamp.EntityCollisionMixin} 的 {@code @Redirect} 与
 * {@code expand.y.border.PlayerBoundaryCollisionMixin} 的 {@code @ModifyArg}，都在 1000。往被合并过的方法里
 * 注入要求注入方优先级严格大于合并方，所以本条必须先应用。体里保留了 {@code Level.getEntityCollisions} 与
 * {@code Entity.collideBoundingBox} 两个调用，它们才有落点。
 */
@Mixin(value = Entity.class, priority = 900)
public abstract class EntityPositionMixin implements ExactPosition {

    /** 锚点为零的上界。下界是方块坐标的 int 界，上界远低于 double 的 2^53。 */
    @Unique
    private static final long farlands$FRAME_LIMIT = 1_000_000_000_000L;

    @Shadow
    private Vec3 position;

    @Shadow
    private BlockPos blockPosition;

    @Shadow
    private ChunkPos chunkPosition;

    @Shadow
    private BlockState inBlockState;

    @Shadow
    private Level level;

    @Shadow
    private EntityInLevelCallback levelCallback;

    @Shadow
    protected boolean firstTick;

    @Shadow
    protected abstract AABB makeBoundingBox();

    @Shadow
    protected abstract AABB makeBoundingBox(Vec3 position);

    @Shadow
    private static float[] collectCandidateStepUpHeights(AABB boundingBox, List<VoxelShape> colliders,
            float maxStepHeight, float stepHeightToSkip) {
        throw new AbstractMethodError();
    }

    @Shadow
    private static Vec3 collideWithShapes(Vec3 movement, AABB boundingBox, List<VoxelShape> shapes) {
        throw new AbstractMethodError();
    }

    /**
     * 九槽 256 位存储。不写初始化器：{@code @Unique} 字段的初始化器可能被静默丢弃，见仓库既有约定。
     */
    @Unique
    private Binary256[] farlands$slots;

    @Unique
    private long farlands$anchorX;

    @Unique
    private long farlands$anchorY;

    @Unique
    private long farlands$anchorZ;

    @Unique
    private double farlands$localX;

    @Unique
    private double farlands$localY;

    @Unique
    private double farlands$localZ;

    /** 帧是否与槽位同步。外部写入会换掉槽位，这里随之作废。 */
    @Unique
    private boolean farlands$frameReady;

    /** 本次 {@code move} 的入参，用于 {@code noPhysics} 分支的位置写回。 */
    @Unique
    private Vec3 farlands$pendingDelta;

    /** 本次 {@code move} 里 {@code collide} 的返回值，非空表示走的是碰撞分支。 */
    @Unique
    private Vec3 farlands$pendingMovement;

    @Override
    public Binary256[] farlands$exactSlots() {
        Binary256[] slots = this.farlands$slots;
        if (slots == null) {
            slots = new Binary256[9];
            this.farlands$slots = slots;
        }
        return slots;
    }

    @Override
    public long farlands$anchor(int axis) {
        this.farlands$syncFrame();
        if (axis == POS_X) {
            return this.farlands$anchorX;
        }
        return axis == POS_Y ? this.farlands$anchorY : this.farlands$anchorZ;
    }

    @Override
    public double farlands$local(int axis) {
        this.farlands$syncFrame();
        if (axis == POS_X) {
            return this.farlands$localX;
        }
        return axis == POS_Y ? this.farlands$localY : this.farlands$localZ;
    }

    /**
     * 旧位置与当前位置在 {@code partial} 刻的插值，返回相对当前锚点的局部量。
     *
     * <p>
     * 两端都是 256 位值时，先把锚点差与局部差分别算出来再乘权重。若旧位置还没记过，退回当前局部量，
     * 等价于不插值。
     */
    @Override
    public double farlands$lerpLocal(int axis, float partial) {
        this.farlands$syncFrame();
        Binary256[] slots = this.farlands$exactSlots();
        Binary256 old = slots[axis == POS_X ? OLD_XO : axis == POS_Y ? OLD_YO : OLD_ZO];
        if (old == null) {
            return farlands$local(axis);
        }
        long newAnchor = farlands$anchor(axis);
        double newLocal = farlands$local(axis);
        long oldAnchor = farlands$anchorOf(old);
        double oldLocal = farlands$localOf(old, oldAnchor);
        double oldRelative = (double) (oldAnchor - newAnchor) + oldLocal;
        return oldRelative + (double) partial * (newLocal - oldRelative);
    }

    /**
     * 旧位置到当前的位移，帧内相减。
     *
     * <p>
     * 与 {@link #farlands$lerpLocal} 同源，只是不乘权重。旧位置没记过时返回 0，等价于「这一 tick 没动」。
     */
    @Override
    public double farlands$frameDelta(int axis) {
        this.farlands$syncFrame();
        Binary256[] slots = this.farlands$exactSlots();
        Binary256 old = slots[axis == POS_X ? OLD_XO : axis == POS_Y ? OLD_YO : OLD_ZO];
        if (old == null) {
            return 0.0;
        }
        long newAnchor = farlands$anchor(axis);
        double newLocal = farlands$local(axis);
        long oldAnchor = farlands$anchorOf(old);
        double oldLocal = farlands$localOf(old, oldAnchor);
        return (double) (newAnchor - oldAnchor) + (newLocal - oldLocal);
    }

    /**
     * 把这次的三个入参记进位置槽位。
     *
     * <p>
     * 逐轴幂等：入参与当前槽位的 double 投影相等时保留槽位。`reapplyPosition`、`snapTo(getX(), ...)`、
     * `teleportSetPosition` 这几条会把投影原样回填，若无条件按入参重写，256 位值就被投影替换，
     * {@code 5e18} 处一次回填最多跳 512 格。
     */
    @Inject(method = "setPosRaw(DDD)V", at = @At("HEAD"))
    private void farlands$storePosition(double x, double y, double z, CallbackInfo ci) {
        Binary256[] slots = this.farlands$exactSlots();
        slots[POS_X] = farlands$keepIfProjection(slots[POS_X], x);
        slots[POS_Y] = farlands$keepIfProjection(slots[POS_Y], y);
        slots[POS_Z] = farlands$keepIfProjection(slots[POS_Z], z);
        this.farlands$frameReady = false;
    }

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void farlands$captureDelta(MoverType moverType, Vec3 delta, CallbackInfo ci) {
        this.farlands$pendingDelta = delta;
        this.farlands$pendingMovement = null;
    }

    /** 截获 {@code collide} 的返回值，供写回时按帧加。 */
    @Redirect(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 farlands$captureMovement(Entity self, Vec3 movement) {
        Vec3 resolved = this.collide(movement);
        this.farlands$pendingMovement = resolved;
        return resolved;
    }

    /** 碰撞分支的位置写回：实参已是被 double 加过的结果，按帧重算。 */
    @Redirect(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setPos(Lnet/minecraft/world/phys/Vec3;)V"))
    private void farlands$writeVec3(Entity self, Vec3 ignored) {
        Vec3 movement = this.farlands$pendingMovement;
        if (movement != null) {
            this.farlands$moveExact(movement.x, movement.y, movement.z);
            return;
        }
        Vec3 delta = this.farlands$pendingDelta;
        if (delta != null) {
            this.farlands$moveExact(delta.x, delta.y, delta.z);
        }
    }

    /** {@code noPhysics} 分支的位置写回，实参同样是加过的 double。 */
    @Redirect(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V"))
    private void farlands$writeDirect(Entity self, double x, double y, double z) {
        Vec3 delta = this.farlands$pendingDelta;
        if (delta != null) {
            this.farlands$moveExact(delta.x, delta.y, delta.z);
        }
    }

    @Overwrite
    public final double getX() {
        Binary256 exact = this.farlands$exactSlots()[POS_X];
        return exact != null ? exact.toDouble() : this.position.x;
    }

    @Overwrite
    public final double getY() {
        Binary256 exact = this.farlands$exactSlots()[POS_Y];
        return exact != null ? exact.toDouble() : this.position.y;
    }

    @Overwrite
    public final double getZ() {
        Binary256 exact = this.farlands$exactSlots()[POS_Z];
        return exact != null ? exact.toDouble() : this.position.z;
    }

    /**
     * 碰撞改在局部帧里做：盒由局部位置生成，实体冲突仍用世界盒查询。
     *
     * <p>
     * 保住了两条既有注入的锚点：{@code Level.getEntityCollisions} 与 {@code Entity.collideBoundingBox} 都照原样调用，
     * 仅前者的盒换成世界盒。
     */
    @Overwrite
    private Vec3 collide(Vec3 movement) {
        this.farlands$syncFrame();
        Entity self = (Entity) (Object) this;
        AABB localBox = this.makeBoundingBox(new Vec3(this.farlands$localX, this.farlands$localY, this.farlands$localZ));
        AABB absoluteBox = self.getBoundingBox();
        List<VoxelShape> entityColliders = this.level.getEntityCollisions(self, absoluteBox.expandTowards(movement));
        Vec3 movementStep = movement.lengthSqr() == 0.0 ? movement
                : Entity.collideBoundingBox(self, movement, localBox, this.level, entityColliders);
        boolean xCollision = movement.x != movementStep.x;
        boolean yCollision = movement.y != movementStep.y;
        boolean zCollision = movement.z != movementStep.z;
        boolean onGroundAfterCollision = yCollision && movement.y < 0.0;
        if (self.maxUpStep() > 0.0F && (onGroundAfterCollision || self.onGround()) && (xCollision || zCollision)) {
            AABB groundedAABB = onGroundAfterCollision ? localBox.move(0.0, movementStep.y, 0.0) : localBox;
            AABB stepUpAABB = groundedAABB.expandTowards(movement.x, self.maxUpStep(), movement.z);
            if (!onGroundAfterCollision) {
                stepUpAABB = stepUpAABB.expandTowards(0.0, -1.0E-5F, 0.0);
            }
            List<VoxelShape> colliders = collectColliders(self, this.level, entityColliders, stepUpAABB);
            float stepHeightToSkip = (float) movementStep.y;
            float[] candidateStepUpHeights = collectCandidateStepUpHeights(groundedAABB, colliders,
                    self.maxUpStep(), stepHeightToSkip);
            for (float candidateStepUpHeight : candidateStepUpHeights) {
                Vec3 stepFromGround = collideWithShapes(new Vec3(movement.x, candidateStepUpHeight, movement.z),
                        groundedAABB, colliders);
                if (stepFromGround.horizontalDistanceSqr() > movementStep.horizontalDistanceSqr()) {
                    double distanceToGround = localBox.minY - groundedAABB.minY;
                    return stepFromGround.subtract(0.0, distanceToGround, 0.0);
                }
            }
        }
        return movementStep;
    }

    /**
     * 方块与边界的碰撞源：vanilla 查询收世界盒，锚点非零时那个盒会塌陷，于是返回空，正是 int64 区该有的结果。
     *
     * <p>
     * 覆写而不是注入，是因为盒的换算必须发生在查询之前，而 {@code getBlockCollisions} 的实参就是这里的盒。
     */
    @Overwrite
    private static List<VoxelShape> collectColliders(Entity source, Level level, List<VoxelShape> entityColliders,
            AABB boundingBox) {
        ImmutableList.Builder<VoxelShape> colliders = ImmutableList
                .builderWithExpectedSize(entityColliders.size() + 1);
        if (!entityColliders.isEmpty()) {
            colliders.addAll(entityColliders);
        }
        AABB absoluteBox = boundingBox;
        long ax = 0L;
        long ay = 0L;
        long az = 0L;
        if (source instanceof ExactPosition exact) {
            ax = exact.farlands$anchor(POS_X);
            ay = exact.farlands$anchor(POS_Y);
            az = exact.farlands$anchor(POS_Z);
            if (ax != 0L || ay != 0L || az != 0L) {
                absoluteBox = new AABB(boundingBox.minX + (double) ax, boundingBox.minY + (double) ay,
                        boundingBox.minZ + (double) az, boundingBox.maxX + (double) ax,
                        boundingBox.maxY + (double) ay, boundingBox.maxZ + (double) az);
            }
        }
        WorldBorder worldBorder = level.getWorldBorder();
        boolean isEntityInsideCloseToBorder = source != null && worldBorder.isInsideCloseToBorder(source, absoluteBox);
        if (isEntityInsideCloseToBorder) {
            colliders.add(worldBorder.getCollisionShape());
        }
        if ((ax != 0L || ay != 0L || az != 0L) && level instanceof ExactCollisionSource frameSource) {
            colliders.addAll(frameSource.farlands$frameCollisions(source, boundingBox));
        } else {
            colliders.addAll(level.getBlockCollisions(source, absoluteBox));
        }
        return colliders.build();
    }

    /**
     * 旧位置六轴的写点。{@code setOldPos} 与 {@code absSnapTo} 各只有一个入参来源，两条头部注入覆盖九个
     * {@code putfield}，不必逐字段 redirect。
     *
     * <p>
     * {@code absSnapTo} 的 X 与 Z 在方法体里过一道 {@code Mth.clamp}，而 {@code expand.xz.border.EntityMixin}
     * 已把这道钳制放行，入参即落值，两个来源同值。
     */
    @Inject(method = "setOldPos(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void farlands$storeOldPos(Vec3 pos, CallbackInfo ci) {
        Binary256[] slots = this.farlands$exactSlots();
        // 入参是当前位置时直接引用精确槽位，插值两端就都精确。入参是别的值时按该 double 记：
        // setOldPosAndRot 的插值路径与客户端报文路径会给到这种值，语义要跟着入参走。
        boolean current = pos.x == this.position.x && pos.y == this.position.y && pos.z == this.position.z;
        Binary256 x = current && slots[POS_X] != null ? slots[POS_X] : Binary256.of(pos.x);
        Binary256 y = current && slots[POS_Y] != null ? slots[POS_Y] : Binary256.of(pos.y);
        Binary256 z = current && slots[POS_Z] != null ? slots[POS_Z] : Binary256.of(pos.z);
        slots[OLD_XO] = x;
        slots[OLD_X] = x;
        slots[OLD_YO] = y;
        slots[OLD_Y] = y;
        slots[OLD_ZO] = z;
        slots[OLD_Z] = z;
    }

    @Inject(method = "absSnapTo(DDD)V", at = @At("HEAD"))
    private void farlands$storeSnapped(double x, double y, double z, CallbackInfo ci) {
        ExactDoubles.write(this, OLD_XO, x);
        ExactDoubles.write(this, OLD_YO, y);
        ExactDoubles.write(this, OLD_ZO, z);
    }

    @Redirect(method = "oldPosition()Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$oldX(Entity self) {
        return ExactDoubles.read(this, OLD_X, self.xOld);
    }

    @Redirect(method = "oldPosition()Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;yOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$oldY(Entity self) {
        return ExactDoubles.read(this, OLD_Y, self.yOld);
    }

    @Redirect(method = "oldPosition()Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$oldZ(Entity self) {
        return ExactDoubles.read(this, OLD_Z, self.zOld);
    }

    @Redirect(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eyeX(Entity self) {
        return ExactDoubles.read(this, OLD_XO, self.xo);
    }

    @Redirect(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eyeY(Entity self) {
        return ExactDoubles.read(this, OLD_YO, self.yo);
    }

    @Redirect(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eyeZ(Entity self) {
        return ExactDoubles.read(this, OLD_ZO, self.zo);
    }

    @Redirect(method = "getPosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$lerpX(Entity self) {
        return ExactDoubles.read(this, OLD_XO, self.xo);
    }

    @Redirect(method = "getPosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$lerpY(Entity self) {
        return ExactDoubles.read(this, OLD_YO, self.yo);
    }

    @Redirect(method = "getPosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$lerpZ(Entity self) {
        return ExactDoubles.read(this, OLD_ZO, self.zo);
    }

    /** 从槽位重建帧。锚点按阈值取零或精确下取整，局部偏移随之定。 */
    @Unique
    private void farlands$syncFrame() {
        if (this.farlands$frameReady) {
            return;
        }
        Binary256[] slots = this.farlands$exactSlots();
        Binary256 px = slots[POS_X] != null ? slots[POS_X] : Binary256.of(this.position.x);
        Binary256 py = slots[POS_Y] != null ? slots[POS_Y] : Binary256.of(this.position.y);
        Binary256 pz = slots[POS_Z] != null ? slots[POS_Z] : Binary256.of(this.position.z);
        this.farlands$anchorX = farlands$anchorOf(px);
        this.farlands$anchorY = farlands$anchorOf(py);
        this.farlands$anchorZ = farlands$anchorOf(pz);
        this.farlands$localX = farlands$localOf(px, this.farlands$anchorX);
        this.farlands$localY = farlands$localOf(py, this.farlands$anchorY);
        this.farlands$localZ = farlands$localOf(pz, this.farlands$anchorZ);
        this.farlands$frameReady = true;
    }

    /** 精确下取整。{@code toLong} 截断向零，负数带小数时补一。阈值与锚点都不参与。 */
    @Unique
    private static long farlands$exactFloor(Binary256 value) {
        long truncated = value.toLong();
        return value.signBit() && !value.equals(Binary256.of(truncated)) ? truncated - 1L : truncated;
    }

    /** 阈值内取零，阈值外取精确下取整。 */
    @Unique
    private static long farlands$anchorOf(Binary256 value) {
        long floor = farlands$exactFloor(value);
        return floor > -farlands$FRAME_LIMIT && floor < farlands$FRAME_LIMIT ? 0L : floor;
    }

    @Unique
    private static double farlands$localOf(Binary256 value, long anchor) {
        return anchor == 0L ? value.toDouble() : value.subtract(Binary256.of(anchor)).toDouble();
    }

    /** 入参等于当前槽位的 double 投影时保留槽位，否则按入参写。写侧幂等靠这一句。 */
    @Unique
    private static Binary256 farlands$keepIfProjection(Binary256 current, double value) {
        return current != null && current.toDouble() == value ? current : Binary256.of(value);
    }

    /** 当前帧下的局部包围盒，碰撞探测用。 */
    @Override
    public AABB farlands$localBox() {
        this.farlands$syncFrame();
        return this.makeBoundingBox(new Vec3(this.farlands$localX, this.farlands$localY, this.farlands$localZ));
    }

    /** 该轴自上一 tick 以来是否跨过方块格。两端各取精确下取整再比。 */
    @Override
    public boolean farlands$crossedCell(int axis) {
        this.farlands$syncFrame();
        Binary256[] slots = this.farlands$exactSlots();
        Binary256 old = slots[axis == POS_X ? OLD_XO : axis == POS_Y ? OLD_YO : OLD_ZO];
        Binary256 current = slots[axis == POS_X ? POS_X : axis == POS_Y ? POS_Y : POS_Z];
        if (old == null || current == null) {
            return false;
        }
        return farlands$exactFloor(old) != farlands$exactFloor(current);
    }

    /**
     * 在帧里加一段位移并写回。
     *
     * <p>
     * 先按槽位做 256 位加法，再交 {@link #farlands$writeExact} 重新定帧。位移量级是每 tick 一格上下，所以那次
     * 加法不改变量级，只是把小数位保住。
     */
    @Unique
    private void farlands$moveExact(double dx, double dy, double dz) {
        Binary256[] slots = this.farlands$exactSlots();
        Binary256 nx = (slots[POS_X] != null ? slots[POS_X] : Binary256.of(this.position.x))
                .add(Binary256.of(dx));
        Binary256 ny = (slots[POS_Y] != null ? slots[POS_Y] : Binary256.of(this.position.y))
                .add(Binary256.of(dy));
        Binary256 nz = (slots[POS_Z] != null ? slots[POS_Z] : Binary256.of(this.position.z))
                .add(Binary256.of(dz));
        this.farlands$writeExact(nx, ny, nz);
    }

    /**
     * 帧写回：槽位、{@code position} 投影、包围盒、方块与区块缓存、两段回调，与 {@code setPosRaw} 的体逐条对应。
     *
     * <p>
     * 不经过 {@code setPos}，那条路会把锚点量级压回 double。方块坐标仍走 {@code Mth.floor} 到 int，越界饱和，
     * 与改动前一致。
     */
    @Unique
    private void farlands$writeExact(Binary256 nx, Binary256 ny, Binary256 nz) {
        Binary256[] slots = this.farlands$exactSlots();
        slots[POS_X] = nx;
        slots[POS_Y] = ny;
        slots[POS_Z] = nz;
        this.farlands$anchorX = farlands$anchorOf(nx);
        this.farlands$anchorY = farlands$anchorOf(ny);
        this.farlands$anchorZ = farlands$anchorOf(nz);
        this.farlands$localX = farlands$localOf(nx, this.farlands$anchorX);
        this.farlands$localY = farlands$localOf(ny, this.farlands$anchorY);
        this.farlands$localZ = farlands$localOf(nz, this.farlands$anchorZ);
        this.farlands$frameReady = true;
        double x = nx.toDouble();
        double y = ny.toDouble();
        double z = nz.toDouble();
        this.position = new Vec3(x, y, z);
        Entity self = (Entity) (Object) this;
        self.setBoundingBox(this.makeBoundingBox());
        int fx = Mth.floor(x);
        int fy = Mth.floor(y);
        int fz = Mth.floor(z);
        if (fx != this.blockPosition.getX() || fy != this.blockPosition.getY() || fz != this.blockPosition.getZ()) {
            this.blockPosition = new BlockPos(fx, fy, fz);
            this.inBlockState = null;
            if (SectionPos.blockToSectionCoord(fx) != this.chunkPosition.x()
                    || SectionPos.blockToSectionCoord(fz) != this.chunkPosition.z()) {
                this.chunkPosition = ChunkPos.containing(this.blockPosition);
            }
        }
        this.levelCallback.onMove();
        if (!this.firstTick && this.level instanceof ServerLevel serverLevel && !self.isRemoved()) {
            if (self instanceof WaypointTransmitter waypoint && waypoint.isTransmittingWaypoint()) {
                serverLevel.getWaypointManager().updateWaypoint(waypoint);
            }
            if (self instanceof ServerPlayer player && player.isReceivingWaypoints() && player.connection != null) {
                serverLevel.getWaypointManager().updatePlayer(player);
            }
        }
    }
}
