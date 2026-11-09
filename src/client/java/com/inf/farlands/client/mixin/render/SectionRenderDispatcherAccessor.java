package com.inf.farlands.client.mixin.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code SectionRenderDispatcher.level} 的访问器。
 *
 * <p>
 * 该字段是 private，且类上没有 getter。读它的是 {@link SectionRenderDispatcher$RenderSectionMixin} 里的门，
 * 那是一个 mixin 类，跨类取私有字段只能走访问器或反射。本仓库的既有模式是生成访问器而不是反射，见
 * {@link LevelRendererAccessor}。
 */
@Mixin(SectionRenderDispatcher.class)
public interface SectionRenderDispatcherAccessor {

    /** 当前关卡。未装关卡时为 null。 */
    @Accessor("level")
    ClientLevel farlands$level();
}
