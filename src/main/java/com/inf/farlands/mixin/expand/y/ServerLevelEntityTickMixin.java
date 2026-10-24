package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.util.window.EntitySectionWindow;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 实体刻的位置门：窗口外的非恒刻实体不 tick。
 *
 * <p>段跟着玩家窗口并集物化，窗口移动后 SectionLifecycle 会释放窗口外的段，而实体的刻门只看水平
 * 票级，于是留在旧 Y 带的实体一边读到空气一边吃重力，表现为下陷。这里照方块刻已有的做法给它加
 * 同一道位置门：窗口外的实体原样冻结，窗口回来后恢复。
 *
 * <p>注入 TickRateManager.isEntityFrozen 而不是 guardEntityTick：后者声明在父类 Level 上，
 * @Shadow 不解析继承来的成员，本仓库已有先例。isEntityFrozen 全仓只有 lambda$tick$0 一处调用，
 * 作用域天然就是这个 tick 循环。返回真会让整个分支跳过，checkDespawn 与载具处理也一并跳过，
 * 这是「完全冻结」的语义。
 *
 * <p>豁免面用 isAlwaysTicking 判，与 PersistentEntitySectionManager 的可见性判定同源，玩家不受影响。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityTickMixin {

    @Redirect(method = "lambda$tick$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/TickRateManager;isEntityFrozen(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean farlands$freezeOutsideWindow(TickRateManager tickRateManager, Entity entity) {
        if (tickRateManager.isEntityFrozen(entity)) {
            return true;
        }
        return !entity.isAlwaysTicking() && !EntitySectionWindow.inAnyWindow(entity.level().dimension(),
                entity.getBlockY() >> 4);
    }
}
