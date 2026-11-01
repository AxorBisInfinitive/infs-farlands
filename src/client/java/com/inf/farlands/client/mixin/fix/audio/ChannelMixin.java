package com.inf.farlands.client.mixin.fix.audio;

import com.mojang.blaze3d.audio.Channel;

import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 非有限的音源位置不交给 OpenAL。
 *
 * <p>
 * {@code Channel.setSelfPosition} 的方法体只有一件事：把三个分量经 {@code d2f} 收进一个 float 数组，
 * 交给 {@code alSourcefv}。位置非有限时那三个 float 也是非有限，OpenAL 回 {@code AL_INVALID_VALUE}，
 * 而这个错是粘性的：谁下一个读 {@code alGetError} 谁把它当成本次失败。读它的地方有六处，分别是分配源、
 * 建缓冲、写缓冲数据、回收流缓冲、停源、删源，其中分配源与建缓冲是所有声音共用的入口，于是不依赖坐标的
 * UI 声音也会被连累成「这次没播出来」或「这个声音加载失败」。
 *
 * <p>
 * 判据在 float 域上：{@code (float)} 之后的有限性就是 OpenAL 的参数域。在上游按 {@code double} 判会放过
 * {@code 1e300} 这类有限 double，它在窄化后仍是无穷，等于没挡。
 *
 * <p>
 * 挡掉之后音源停在最后一次有限位置，新建的源停在 OpenAL 默认的 {@code 0,0,0}，即本仓库坐标系的锚点处。
 * 世界声音因此会错位，这是接受的代价；UI 声音不受影响，因为它们的坐标本来就是听者空间的零点，恒有限，
 * 判据对它们恒放行。
 */
@Mixin(Channel.class)
public class ChannelMixin {

    @Inject(method = "setSelfPosition", at = @At("HEAD"), cancellable = true)
    private void farlands$skipNonFinitePosition(Vec3 position, CallbackInfo ci) {
        if (!Float.isFinite((float) position.x)
                || !Float.isFinite((float) position.y)
                || !Float.isFinite((float) position.z)) {
            ci.cancel();
        }
    }
}
