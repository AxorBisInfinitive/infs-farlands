package com.inf.farlands.client.render;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.simulation.SimulationConfig;
import com.inf.farlands.util.pos.ExactPosition;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;

import org.joml.Matrix4f;

/**
 * 位置含 {@code NaN} 时，往世界画面上盖一层随机彩色噪点。
 *
 * <p>
 * 画在世界而不是 HUD 上：钩子挂在 {@code ScreenEffectRenderer.renderScreenEffect} 的末尾，那一段由
 * {@code GameRenderer} 在 HUD 之前调用，且 {@code hideGui} 只作为实参传进去、只决定物品激活动画那一支，
 * 所以在那个门之外绘制不受 F1 影响。
 *
 * <p>
 * 噪点是一张 256 乘 256 的 {@link DynamicTexture}，按屏幕拉伸，采样是最近邻。每秒重随机一次整张图并上传一次，
 * 图案因此是每秒一跳，不是连续动画。
 *
 * <p>
 * 触发条件是任一轴为 {@code NaN}，且 {@code infs-farlands-simulation.json} 里的 {@code nanStaticOverlay} 为真。
 * 判据必须用 {@code farlands$exactValue(...).isNaN()}：{@code cellCoord}
 * 在 {@code NaN} 上给 0，与原点不可区分。
 *
 * <p>
 * 纹理按标识符注册进 {@code TextureManager}，而资源重载会关掉并清空那张表，所以每帧先用 {@code getTexture}
 * 核对一次拿到的还是不是我们建的那个实例，不是就重建并重新注册。重载后第一次核对的代价是管理器先造一个缺图
 * 占位并留一条警告，之后恢复正常。
 */
public final class NanStaticOverlay {

    /** 噪点纹理边长。采样是最近邻，拉伸到全屏后每格约是「屏宽除 256」个像素。 */
    private static final int SIZE = 256;

    /** 重随机间隔，毫秒。一秒一跳。 */
    private static final long REFRESH_MILLIS = 1000L;

    /** 不透明随机色的掩码。 */
    private static final int ALPHA_MASK = 0xFF000000;

    private static final Identifier TEXTURE_ID = InfsFarlands.id("nan_static");

    private static DynamicTexture texture;

    private static long lastRefresh;

    private static final RandomSource RANDOM = RandomSource.create();

    private NanStaticOverlay() {
    }

    /** 每帧调一次。任一轴为 NaN 时按一秒一跳把噪点盖在世界画面上，开关见 {@code SimulationConfig}。 */
    public static void render(MultiBufferSource bufferSource) {
        if (!SimulationConfig.nanStaticOverlay) {
            reset();
            return;
        }
        Entity camera = Minecraft.getInstance().getCameraEntity();
        if (!(camera instanceof ExactPosition exact)) {
            reset();
            return;
        }
        if (!exact.farlands$exactValue(ExactPosition.POS_X).isNaN()
                && !exact.farlands$exactValue(ExactPosition.POS_Y).isNaN()
                && !exact.farlands$exactValue(ExactPosition.POS_Z).isNaN()) {
            reset();
            return;
        }
        DynamicTexture current = textureOrCreate();
        long now = Util.getMillis();
        if (now - lastRefresh >= REFRESH_MILLIS) {
            lastRefresh = now;
            NativeImage pixels = current.getPixels();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    pixels.setPixel(x, y, ALPHA_MASK | RANDOM.nextInt(0x1000000));
                }
            }
            current.upload();
        }
        draw(bufferSource);
    }

    /** 全屏四边形，形状与顶点属性照 {@code ScreenEffectRenderer.renderTex} 抄。 */
    private static void draw(MultiBufferSource bufferSource) {
        PoseStack poseStack = new PoseStack();
        Matrix4f pose = poseStack.last().pose();
        VertexConsumer builder = bufferSource.getBuffer(RenderTypes.blockScreenEffect(TEXTURE_ID));
        int color = -1;
        builder.addVertex(pose, -1.0F, -1.0F, -0.5F).setUv(0.0F, 1.0F).setColor(color);
        builder.addVertex(pose, 1.0F, -1.0F, -0.5F).setUv(1.0F, 1.0F).setColor(color);
        builder.addVertex(pose, 1.0F, 1.0F, -0.5F).setUv(1.0F, 0.0F).setColor(color);
        builder.addVertex(pose, -1.0F, 1.0F, -0.5F).setUv(0.0F, 0.0F).setColor(color);
    }

    /**
     * 取当前注册的纹理，不是我们建的那个就重建。
     *
     * <p>
     * 资源重载会把管理器的表清空并关掉旧纹理，静态字段因此可能指向已关闭的对象；判据取「实例同一性」而不是
     * 非空，避免拿旧句柄去绑定。
     */
    private static DynamicTexture textureOrCreate() {
        TextureManager manager = Minecraft.getInstance().getTextureManager();
        AbstractTexture existing = manager.getTexture(TEXTURE_ID);
        if (existing instanceof DynamicTexture dynamic && dynamic == texture) {
            return dynamic;
        }
        DynamicTexture created = new DynamicTexture(() -> "farlands nan static", SIZE, SIZE, true);
        manager.register(TEXTURE_ID, created);
        texture = created;
        lastRefresh = 0L;
        return created;
    }

    /** 条件不再成立时清掉节流时刻，下次触发立刻重随机一次。 */
    private static void reset() {
        lastRefresh = 0L;
    }
}
