package com.inf.farlands.client.mixin.worldselection;

import java.nio.file.Path;

import com.inf.farlands.client.gui.SelectionCompat;
import com.inf.farlands.simulation.serialize.SimulationIO;
import com.inf.farlands.terrain.registry.SystemsIO;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.storage.LevelVersion;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 世界摘要带上「系统配置不兼容」这个状态，并让它在描述行与可用性判据上生效。
 *
 * <p>状态在构造时算一次。摘要由 {@code LevelStorageSource.loadLevelSummaries} 在后台执行器上产出，
 * 所以这里读盘不落在渲染线程；搜索过滤会重建列表项，但摘要对象复用，不重算。
 *
 * <p>可用性只接管 {@code isDisabled}：{@code primaryActionActive}、{@code canEdit}、{@code canRecreate}
 * 三个 getter 全读它，而它全树只有这三个读者。因此进入选中的世界、重建、编辑三个按钮一起变灰，
 * 双击行、回车与点缩略图也一并被拒，因为 {@code canInteract} 与 {@code joinWorld} 同样读它。不去动
 * {@code isCompatible}：那个方法还会被 {@code createInfo} 用来把整行描述换成版本不兼容文案，且专用
 * 服务器的启动判据也读它。
 *
 * <p>描述行的红字追加在 {@code getInfo} 的返回处，不动它的缓存；追加段自己的样式是红的，列表那边
 * {@code mergeStyles} 的灰色只作用于根节点，压不掉它。
 */
@Mixin(LevelSummary.class)
public abstract class LevelSummaryMixin implements SelectionCompat {

    /** 描述行追加的那段文案的键。 */
    @Unique
    private static final String FARLANDS_INCOMPATIBLE_KEY = "selectWorld.infs-farlands.incompatible.systems";

    /** 世界目录下的图标路径，构造器入参。它的父目录就是世界根目录。 */
    @Shadow
    @Final
    private Path icon;

    /** 该世界的 systems.dat 存在但读不出来。 */
    @Unique
    private boolean farlands$systemsIncompatible;

    /** 该世界的 simulation.dat 存在但读不出来。 */
    @Unique
    private boolean farlands$simulationIncompatible;

    /** 模拟层那段红字的键。 */
    @Unique
    private static final String SIMULATION_INCOMPATIBLE_KEY =
            "selectWorld.infs-farlands.incompatible.simulation";

    /**
     * 构造末尾探测一次。此处 {@code icon} 已赋值，而 {@code isDisabled} 与 {@code getInfo} 都在构造之后
     * 才会被调用，所以这个字段不依赖初始化器。
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void farlands$probeSystems(LevelSettings settings, LevelVersion levelVersion, String levelId,
            boolean requiresManualConversion, boolean requiresFileFixing, boolean locked, boolean experimental,
            Path icon, CallbackInfo ci) {
        Path worldRoot = icon == null ? null : icon.getParent();
        this.farlands$systemsIncompatible = worldRoot != null && !SystemsIO.compatible(worldRoot);
        // 两层各探一次。两份档案都是「已初始化却缺档即不兼容」，所以缺 simulation.dat 的旧世界也会被判不兼容，
        // 这与 resolve 在加载时抛是同一件事的两个表现。
        this.farlands$simulationIncompatible = worldRoot != null
                && !SimulationIO.compatible(worldRoot);
    }

    @Inject(method = "isDisabled", at = @At("RETURN"), cancellable = true)
    private void farlands$disableIncompatible(CallbackInfoReturnable<Boolean> cir) {
        if (this.farlands$systemsIncompatible || this.farlands$simulationIncompatible) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 两层各追加一段红字，顺序固定为 farlands 在前。
     *
     * <p>
     * 两段必须在同一个注入器里顺序追加：两个 {@code @Inject} 在同一 RETURN 处的执行顺序不保证，而第二段
     * 要基于第一段的结果。
     */
    @Inject(method = "getInfo", at = @At("RETURN"), cancellable = true)
    private void farlands$markInfo(CallbackInfoReturnable<Component> cir) {
        Component info = cir.getReturnValue();
        if (this.farlands$systemsIncompatible) {
            info = info.copy().append(CommonComponents.SPACE)
                    .append(Component.translatable(FARLANDS_INCOMPATIBLE_KEY).withStyle(ChatFormatting.RED));
        }
        if (this.farlands$simulationIncompatible) {
            info = info.copy().append(CommonComponents.SPACE)
                    .append(Component.translatable(SIMULATION_INCOMPATIBLE_KEY).withStyle(ChatFormatting.RED));
        }
        cir.setReturnValue(info);
    }

    @Override
    public boolean systemsIncompatible() {
        return this.farlands$systemsIncompatible;
    }

    @Override
    public boolean simulationIncompatible() {
        return this.farlands$simulationIncompatible;
    }
}
