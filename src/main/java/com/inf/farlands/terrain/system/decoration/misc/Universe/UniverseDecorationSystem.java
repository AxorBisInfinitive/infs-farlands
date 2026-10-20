package com.inf.farlands.terrain.system.decoration.misc.Universe;

import java.util.concurrent.ThreadLocalRandom;

import com.inf.farlands.terrain.DecorationSystem;
import com.inf.farlands.terrain.decorationFiller.DecorationRegion;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemsData.Arg;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 宇宙告示牌装饰系统：在世界坐标 x y z 处放一块橡木告示牌，正面两行一句问句，反面不动。
 *
 * <p>
 * 三个坐标各自声明一条空串关键字，默认从 int 全域随机抽一个。所以落点几乎必然不可达，这个系统的意义
 * 不在被找到，而在于它确实存在于那份存档里。
 *
 * <p>
 * 只在目标坐标所属的 chunk 上落牌：写到别处会出写域被拒并打越域日志。文本没有在装饰体里写，登记给收尾
 * 的主线程，原因见 {@link #applyDecoration}。
 */
public final class UniverseDecorationSystem implements DecorationSystem {

    /** 三个坐标的声明：各一条空串关键字，随机抽 int 全域。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            coordinate("x"),
            coordinate("y"),
            coordinate("z"));

    /** 正面两行的字样。 */
    private static final String FRONT_LINE_0 = "探索宇宙文明";
    private static final String FRONT_LINE_1 = "到底是为了什么？";

    private final int x;
    private final int y;
    private final int z;

    public UniverseDecorationSystem(SystemArgs args) {
        this.x = args.getInt("x");
        this.y = args.getInt("y");
        this.z = args.getInt("z");
    }

    /** 一个坐标参数的声明。空框命中空串关键字，取值器抽一次 int。 */
    private static SystemParamSpec coordinate(String key) {
        return SystemParamSpec.ofInt(key)
                .keyword("", () -> Arg.ofInt(ThreadLocalRandom.current().nextInt()),
                        "createWorld.tab.infs-farlands.param.random_int")
                .build();
    }

    @Override
    public void applyDecoration(ServerLevel level, LevelChunk center, DecorationRegion region) {
        // 只在本 chunk 内落。越出中心 chunk 就到写域之外，写入会被 ensureCanWrite 拒掉并打日志。
        if (SectionPos.blockToSectionCoord(this.x) != center.getPos().x()
                || SectionPos.blockToSectionCoord(this.z) != center.getPos().z()) {
            return;
        }
        BlockPos pos = new BlockPos(this.x, this.y, this.z);
        if (!region.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState(), 3)) {
            // 该 Y 的段没物化，这次写没有落点。装饰是一次性事件，所以这块牌子不会再出现。
            return;
        }
        // 文本不能在装饰体里写：setText 内部的 markUpdated 会读 level 发方块更新，而装饰期 level 被置空。
        // 登记到收尾，那时方块实体已装好、level 是活的，写入才安全。
        region.onBlockEntityInstalled(pos, blockEntity -> {
            if (blockEntity instanceof SignBlockEntity sign) {
                SignText front = new SignText()
                        .setMessage(0, Component.literal(FRONT_LINE_0))
                        .setMessage(1, Component.literal(FRONT_LINE_1));
                sign.setText(front, true);
            }
        });
    }
}
