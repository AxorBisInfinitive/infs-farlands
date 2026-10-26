package com.inf.farlands.terrain.system.terrain.section.misc.Library;

import com.inf.farlands.terrain.SectionContext;
import com.inf.farlands.terrain.SectionSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.util.hash.HashMath;

import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 图书馆段级系统：每段一层木板地板，再按段坐标的哈希分出一间屋子的两种摆法。
 *
 * <p>
 * 底面层是通则：{@code y = 0} 的 16 乘 16 全放橡木木板，除空腔段之外的各支都有它。
 *
 * <p>
 * A 分支是楼梯：只沿一个方向走、每格升一格，横向全占。方向由段坐标哈希的商对 4 取模决定，见 {@link Facing}。
 * 行程坐标 {@code t} 从 1 到 15，所以起飞端那一行留在木板上；楼梯朝行进方向、半个贴底。
 *
 * <p>
 * B 分支是书架墙：局部 {@code x} 取 0、4、11、15，{@code z} 避开 0、7、8、15 四条走道，{@code y} 从 1 到
 * 15，给底面的地板让出第一层。
 *
 * <p>
 * 一个楼梯是四段的联动，四段各由一条规则认领，规则都以「某个邻居是 A 且朝向为 d」为判据：
 *
 * <ol>
 * <li>起飞段：某段自己是 A，斜带朝它的 d 爬升到段顶。</li>
 * <li>落点段：位于起飞段 d 方向再上一层，铺地板，并在迎着起飞段的那条边上放一行半砖，把段顶与这一层地板接成
 * 两级半砖。</li>
 * <li>空腔段：起飞段的正上方，什么都不放。</li>
 * <li>退台段：起飞段 d 的反方向同一层，只有地板。</li>
 * </ol>
 *
 * <p>
 * 每段的判据按下列次序短路，先命中者生效：
 *
 * <ol>
 * <li>自己是 A：起飞段。</li>
 * <li>否则下方是 A：空腔段，一个方块都不写。这条与朝向无关。</li>
 * <li>否则自己是否是某个 A 的落点段，四个朝向各查一次。</li>
 * <li>否则自己是否是某个 A 的退台段，四个朝向各查一次。</li>
 * <li>否则照 B 走。</li>
 * </ol>
 *
 * <p>
 * 四个朝向的查询只算坐标与哈希，不读邻段内容，所以不依赖邻段是否已生成，也不受邻段被改写成空之后的影响。
 *
 * <p>
 * 收尾一笔：段角 {@code (0, 0, 0)} 若仍是木板就换成海晶灯。各支都照此办理，所以空腔段不亮，落点段是否亮取决于
 * 那一行台阶有没有盖住段角。
 *
 * <p>
 * 无状态：实例随 level 走，被多个 chunk 的 fill 并发共享，只持有各朝向的不可变方块态与两张常量表。
 */
public final class LibrarySectionSystem implements SectionSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    /** A 分支的判据模数，同时是朝向所用的商的除数。 */
    private static final long BRANCH_MODULUS = 137L;

    /** B 分支的书架列，段内局部 x。 */
    private static final int[] SHELF_COLUMNS = { 0, 4, 11, 15 };

    /** 四个朝向，声明顺序即哈希商取模后的取值顺序。 */
    private enum Facing {
        EAST(1, 0, Direction.EAST),
        SOUTH(0, 1, Direction.SOUTH),
        WEST(-1, 0, Direction.WEST),
        NORTH(0, -1, Direction.NORTH);

        /** 行进方向在段坐标上的单位向量。 */
        final int dx;

        final int dz;

        /** 楼梯的高侧朝向，与行进方向一致。 */
        final Direction stairFacing;

        Facing(int dx, int dz, Direction stairFacing) {
            this.dx = dx;
            this.dz = dz;
            this.stairFacing = stairFacing;
        }

        /** 行程坐标 t 与横向坐标 c 到段内局部 x。 */
        int localX(int t, int c) {
            return switch (this) {
                case EAST -> t;
                case SOUTH -> c;
                case WEST -> 15 - t;
                case NORTH -> c;
            };
        }

        /** 行程坐标 t 与横向坐标 c 到段内局部 z。 */
        int localZ(int t, int c) {
            return switch (this) {
                case EAST -> c;
                case SOUTH -> t;
                case WEST -> c;
                case NORTH -> 15 - t;
            };
        }

        /** 落点段那一行台阶的局部 x：贴着迎着起飞段的那条边。 */
        int entryX(int c) {
            return switch (this) {
                case EAST -> 0;
                case SOUTH -> c;
                case WEST -> 15;
                case NORTH -> c;
            };
        }

        /** 落点段那一行台阶的局部 z。 */
        int entryZ(int c) {
            return switch (this) {
                case EAST -> c;
                case SOUTH -> 0;
                case WEST -> c;
                case NORTH -> 15;
            };
        }
    }

    private static final Facing[] FACINGS = Facing.values();

    private final BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
    private final BlockState bookshelf = Blocks.BOOKSHELF.defaultBlockState();
    private final BlockState seaLantern = Blocks.SEA_LANTERN.defaultBlockState();

    /** 各朝向的楼梯态，下标与 {@link Facing#ordinal()} 同序。 */
    private final BlockState[] stairsByFacing = new BlockState[FACINGS.length];

    /** 统一构造签名，本系统不读任何参数。 */
    public LibrarySectionSystem(SystemArgs args) {
        for (Facing face : FACINGS) {
            this.stairsByFacing[face.ordinal()] = Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, face.stairFacing)
                    .setValue(StairBlock.HALF, Half.BOTTOM);
        }
    }

    @Override
    public void fillSection(SectionContext ctx) {
        LevelChunkSection section = ctx.section();
        // 段坐标的 XZ 就是 chunk 坐标，Y 是段号。用 chunk 的坐标而不是 baseX、baseZ：那两个在本 port 会被
        // 饱和到 int 边界一侧，拿来当哈希输入会让边缘一段的取值与真实段坐标不同。
        ChunkPos cpos = ctx.chunk().getPos();
        int sectionX = cpos.x();
        int sectionZ = cpos.z();
        int sectionY = ctx.sectionY();
        if (isBranchA(sectionX, sectionY, sectionZ)) {
            Facing face = facingOf(sectionX, sectionY, sectionZ);
            this.fillFloor(section);
            this.fillRamp(section, face);
        } else if (isBranchA(sectionX, sectionY - 1, sectionZ)) {
            // 空腔段：段已经按空气建出来，这里不写任何方块。
        } else {
            Facing landing = landingFor(sectionX, sectionY, sectionZ);
            if (landing != null) {
                this.fillFloor(section);
                this.fillEntryRow(section, landing);
            } else if (behindFor(sectionX, sectionY, sectionZ) != null) {
                // 退台段：只有地板。
                this.fillFloor(section);
            } else {
                this.fillFloor(section);
                this.fillShelves(section);
            }
        }
        // 段角的灯：判据落在方块类型上，不逐字比状态实例。
        if (section.getBlockState(0, 0, 0).is(Blocks.OAK_PLANKS)) {
            section.setBlockState(0, 0, 0, this.seaLantern, false);
        }
    }

    /** 该段坐标是否落在 A 分支。邻段判据走同一条哈希，只算坐标，不读邻段。 */
    private static boolean isBranchA(int sectionX, int sectionY, int sectionZ) {
        return HashMath.hash(sectionX, sectionY, sectionZ) % BRANCH_MODULUS == 0L;
    }

    /** A 段的朝向：哈希除以模数取商，再对 4 取模。A 段的哈希被 137 整除，所以商是精确值。 */
    private static Facing facingOf(int sectionX, int sectionY, int sectionZ) {
        long quotient = HashMath.hash(sectionX, sectionY, sectionZ) / BRANCH_MODULUS;
        return FACINGS[(int) Math.floorMod(quotient, (long) FACINGS.length)];
    }

    /** 本段是否某个 A 的落点段：起飞段在 {@code -d} 再下一层。四个朝向按声明序取第一个命中。 */
    private static Facing landingFor(int sectionX, int sectionY, int sectionZ) {
        for (Facing face : FACINGS) {
            int qx = sectionX - face.dx;
            int qy = sectionY - 1;
            int qz = sectionZ - face.dz;
            if (isBranchA(qx, qy, qz) && facingOf(qx, qy, qz) == face) {
                return face;
            }
        }
        return null;
    }

    /** 本段是否某个 A 的退台段：起飞段在 {@code +d} 方向的同一层。四个朝向按声明序取第一个命中。 */
    private static Facing behindFor(int sectionX, int sectionY, int sectionZ) {
        for (Facing face : FACINGS) {
            int qx = sectionX + face.dx;
            int qz = sectionZ + face.dz;
            if (isBranchA(qx, sectionY, qz) && facingOf(qx, sectionY, qz) == face) {
                return face;
            }
        }
        return null;
    }

    /** 底面层：y=0 的 16 乘 16 橡木木板。 */
    private void fillFloor(LevelChunkSection section) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                section.setBlockState(x, 0, z, this.planks, false);
            }
        }
    }

    /** 起飞段的斜带：行程 t 从 1 到 15，高度等于 t，横向全占，朝行进方向。 */
    private void fillRamp(LevelChunkSection section, Facing face) {
        BlockState stairs = this.stairsByFacing[face.ordinal()];
        for (int t = 1; t < 16; t++) {
            for (int c = 0; c < 16; c++) {
                section.setBlockState(face.localX(t, c), t, face.localZ(t, c), stairs, false);
            }
        }
    }

    /** 落点段那一行台阶：迎着起飞段的边，横贯整条边。 */
    private void fillEntryRow(LevelChunkSection section, Facing face) {
        BlockState stairs = this.stairsByFacing[face.ordinal()];
        for (int c = 0; c < 16; c++) {
            section.setBlockState(face.entryX(c), 0, face.entryZ(c), stairs, false);
        }
    }

    /** B 分支：四条书架列，避开 z 上的四条走道，y 从 1 起。 */
    private void fillShelves(LevelChunkSection section) {
        for (int x : SHELF_COLUMNS) {
            for (int z = 0; z < 16; z++) {
                if (z == 0 || z == 7 || z == 8 || z == 15) {
                    continue;
                }
                for (int y = 1; y < 16; y++) {
                    section.setBlockState(x, y, z, this.bookshelf, false);
                }
            }
        }
    }
}
