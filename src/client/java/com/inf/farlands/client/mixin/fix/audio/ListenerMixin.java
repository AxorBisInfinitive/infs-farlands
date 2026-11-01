package com.inf.farlands.client.mixin.fix.audio;

import com.inf.farlands.client.audio.AudioOrigin;
import com.mojang.blaze3d.audio.Listener;
import com.mojang.blaze3d.audio.ListenerTransform;

import net.minecraft.world.phys.Vec3;

import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 听者位置改成锚点相对量，朝向保留。
 *
 * <p>
 * {@code Listener.setTransform} 把位置经 {@code d2f} 交给 {@code alListener3f}。绝对值到 2^31 时
 * ulp 是 128 格，听者与音源各自被舍入到最近的 128 格倍数，16 格内的源与听者落到同一个 float 上，
 * OpenAL 算出的距离恒为 0，声音失去方位与衰减。减掉锚点后交给 OpenAL 的只剩格内偏移。
 *
 * <p>
 * 锚点只从位置里算，不读任何共享变量。音源那一侧用同一帧的相机位置算同一个函数，见
 * {@link SoundEngineMixin}。两者缺一不可：只改一侧会让等效偏移整体平移一个格边长。
 *
 * <p>
 * 这里只改交给 OpenAL 的那三个 float。字节码里 {@code Listener.setTransform} 先把 transform 存进
 * 字段再取位置，所以字段里仍是世界坐标，读它的字幕叠加层不受影响。
 *
 * <p>
 * 同一方法里还有一条重定向，挡的是偏移 39 那次 {@code alListener3f}：位置非有限时那三个 float 也非有限，
 * OpenAL 会置 {@code AL_INVALID_VALUE}，而那个错是粘性的，会被下一个读 {@code alGetError} 的检查点当成
 * 自己的失败，连不依赖坐标的 UI 声音一起连累。挡掉之后听者停在最后一次有限位置，世界声音错位，UI 不受影响。
 *
 * <p>
 * 不取消整个方法：它还要把 transform 写进字段、还要写朝向那一次 {@code alListenerfv}，两者对字幕与方位
 * 都有用，非有限位置与它们无关。
 */
@Mixin(Listener.class)
public abstract class ListenerMixin {

    @Redirect(method = "setTransform", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/ListenerTransform;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 farlands$anchoredListenerPosition(ListenerTransform transform) {
        return AudioOrigin.shift(transform.position());
    }

    @Redirect(method = "setTransform", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alListener3f(IFFF)V"))
    private static void farlands$skipNonFiniteListener(int parameter, float x, float y, float z) {
        if (Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)) {
            AL10.alListener3f(parameter, x, y, z);
        }
    }
}
