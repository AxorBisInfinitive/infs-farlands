package com.inf.farlands.client.mixin.simulation;

import java.util.function.BooleanSupplier;

import com.inf.farlands.client.simulation.SimulationHolder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把模拟层的每 tick 推进接到客户端关卡上。
 *
 * <p>
 * 绑定不再由 chunk 的进出驱动：这一层覆盖哪一片由玩家位置与渲染距离决定，与客户端有没有那些 chunk 无关，所以
 * {@code onChunkLoaded} 与 {@code unload} 两处都不再需要。{@code ClientLevel.tick} 是每客户端 tick 一次、
 * 与关卡同生共死的唯一入口。
 */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void farlands$tickSimulation(BooleanSupplier haveTime, CallbackInfo ci) {
        ((SimulationHolder) Minecraft.getInstance()).simulation().tickSimulation();
    }
}
