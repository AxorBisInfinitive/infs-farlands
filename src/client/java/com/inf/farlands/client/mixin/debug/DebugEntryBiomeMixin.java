package com.inf.farlands.client.mixin.debug;

import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationHolder;
import com.inf.farlands.client.simulation.SimulationSection;
import com.inf.farlands.simulation.model.SimulationConfig;
import com.inf.farlands.util.pos.ExactPosition;
import com.inf.farlands.util.world.WorldBounds;
import love.crysh.long2.ints.Int1024;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryBiome;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * biome 条目在模拟之地显示模拟段里的群系，其余位置原样交给 vanilla。
 *
 * <p>
 * 版本绑定，升级要重核：{@code display} 的描述符是
 * {@code (DebugScreenDisplayer, Level, LevelChunk, LevelChunk)V}，签名一改这里就找不到。
 *
 * <p>
 * 整段接管而不是只换取值，因为状态有三种：归地形层、归模拟层且段在表里、归模拟层但段还没收到。取值那一层只能
 * 交出一个 {@code Holder<Biome>}，没有「缺」这个值，第三种状态无处表达。接管也只发生在普通支，服务器调试值那一
 * 支是开发者路径，模拟层的数据只在客户端，不在那里做任何事。
 *
 * <p>
 * 域判用段号加 {@link WorldBounds#inSection(Int1024, Int1024, Int1024)}，与 {@code SimulationSection} 的
 * {@code eligible} 同宽：可玩界与可玩段界逐格对齐，所以「这一段归谁」与「这一格归谁」是同一个答案。
 *
 * <p>
 * 取值只读段容器里那一格，不做 {@code BiomeManager} 的八角投票：投票的角坐标是 int、比较值是 long，1024 位进不
 * 去那套算式。格值是段容器里真实存的那一份，与客户端混合取样读的是同一处。
 */
@Mixin(DebugEntryBiome.class)
public class DebugEntryBiomeMixin {

    /** quart 的段内掩码，三轴各取低两位。 */
    @Unique
    private static final Int1024 FARLANDS_QUART_MASK = Int1024.of(3L);

    @Inject(method = "display", at = @At("HEAD"), cancellable = true)
    private void farlands$displaySimBiome(DebugScreenDisplayer displayer, Level serverOrClientLevel,
            LevelChunk clientChunk, LevelChunk serverChunk, CallbackInfo ci) {
        if (!SimulationConfig.enableSimulation) {
            return;
        }
        // 开发者路径让给 vanilla：那一支的第二行取的是服务端群系，模拟层没有对应来源。
        if (SharedConstants.DEBUG_SHOW_SERVER_DEBUG_VALUES && serverOrClientLevel instanceof ServerLevel) {
            return;
        }
        Entity entity = Minecraft.getInstance().getCameraEntity();
        if (entity == null || !(entity instanceof ExactPosition frame)) {
            return;
        }
        Int1024 quartX = frame.farlands$cellCoord1024(ExactPosition.POS_X).shift(-2);
        Int1024 quartY = frame.farlands$cellCoord1024(ExactPosition.POS_Y).shift(-2);
        Int1024 quartZ = frame.farlands$cellCoord1024(ExactPosition.POS_Z).shift(-2);
        Int1024 sectionX = quartX.shift(-2);
        Int1024 sectionY = quartY.shift(-2);
        Int1024 sectionZ = quartZ.shift(-2);
        if (WorldBounds.inSection(sectionX, sectionY, sectionZ)) {
            return;
        }
        Simulation simulation = ((SimulationHolder) Minecraft.getInstance()).simulation();
        SimulationSection section = simulation.sectionOrNull(sectionX, sectionY, sectionZ);
        if (section == null) {
            displayer.addLine("Biome: not received");
            ci.cancel();
            return;
        }
        Holder<Biome> biome = section.getNoiseBiome(quartX.and(FARLANDS_QUART_MASK).toInt(),
                quartY.and(FARLANDS_QUART_MASK).toInt(), quartZ.and(FARLANDS_QUART_MASK).toInt());
        displayer.addLine("Biome: " + farlands$printBiome(biome));
        ci.cancel();
    }

    /** 与 vanilla 私有的 {@code printBiome} 同形：已注册的给 id，未注册的给方括号加值。 */
    @Unique
    private static String farlands$printBiome(Holder<Biome> holder) {
        return holder.unwrap().map(key -> key.identifier().toString(),
                unregistered -> "[unregistered " + unregistered + "]");
    }
}
