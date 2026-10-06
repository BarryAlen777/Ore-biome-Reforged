package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * 地表矿石层：把整个区块从地表往下若干格里的泥土/石头/深板岩按概率换成矿石。
 *
 * <p>为什么需要它：原版那套「矿脉（ore vein）」是一坨一坨的，一条矿脉只有一二十格，
 * 撒在 -64 到 128 这一百多层里，站在地上看当然稀稀拉拉。经典版 Ore Biome 之所以
 * 一眼望过去密密麻麻，是因为矿石几乎铺满了露出来的那一层石头。这个特性就是照那个
 * 观感做的：它不管矿脉形状，直接对整个 chunk 的 16x16 每一列，从地表往下刷一层
 * 「含矿率」。</p>
 *
 * <p>它同时读原版开关和模组矿石开关，所以配置界面里勾上的模组矿石一定会出现在地表，
 * 不用挖到地下才看得到。</p>
 *
 * <h2>这一版修的两件事</h2>
 *
 * <p><b>一是地表不再冒出孤零零的草地。</b>以前这里靠放置器给的「参考地表高度」往下扫，
 * 那个高度只取自本区块里随机一格，碰上陡坡，高的那几列就扫不到、那一列原地留着草皮；
 * 另外放置链最后的 {@code minecraft:biome} 过滤器是按随机起点那一格判群系的，
 * 群系边界上的区块只要起点落在隔壁群系，整块地就一场空，草皮全留着。现在：</p>
 * <ul>
 *   <li>每一列用自己的高度图找地表（{@link Heightmap.Types#WORLD_SURFACE_WG}），
 *       陡坡、山包上的列也照样处理；</li>
 *   <li>群系改成逐列判断，不再看随机起点——本列是矿石群系才动手，正好贴着群系真实边界走，
 *       不会再被区块网格切成直边。</li>
 * </ul>
 *
 * <p><b>二是矿石群系里不留草皮。</b>离边缘越近，含矿率按一个系数往下降（边缘石头多、
 * 矿石少），但泥土和草方块<b>一律</b>换成石头。早先这里还让一部分草皮按概率留下来，
 * 想做出「边界慢慢过渡」，结果边界那一圈会成片留下草和泥土：玩家在石头矿山中间看到
 * 一块突兀的绿地，看着就像 bug。群系边界本来由圆斑遮罩的噪声起伏决定，是自然曲线，
 * 不需要再靠留草皮来过渡。</p>
 *
 * <p><b>三是本特性故意放在装饰阶段的早期（{@code features} 数组第 2 步）。</b>
 * 原版顺序是「先放该步的结构、再放该步的特征」，所以放在早期意味着之后的结构
 * （冰火传说的龙巢、各种地牢）会把方块盖在矿石层上面，巢穴和建筑保持完整；
 * 如果放在第 6 步（矿脉那一步），这些结构的位置会被整片矿石吃掉，看起来就是
 * 「龙巢被矿石群系挤掉了」。</p>
 *
 * <p>最后还做了一件小事：多噪音气候抖动偶尔会在群系内部戳出一个很小的洞（一块
 * 隔壁群系），这种洞四周都被矿石群系包着，看着就是石头山里突兀的一块草。采样网格里
 * 会把这种「不贴边、又不大」的空洞一并按矿石群系处理（见
 * {@link BiomeGrid#fillEnclosedHoles}）。</p>
 *
 * <p>同一区块的重复生成用的是同一个装饰种子，所以多算几遍结果一致，不会在 chunk
 * 边界留下断层。</p>
 */
public class DenseOreLayerFeature extends Feature<NoneFeatureConfiguration> {

    /** 从高度图往下最多找几格，越过树叶、草叶这些盖在地面上的东西。 */
    private static final int SCAN_DOWN = 24;

    /** 群系采样精度（格）。原版的群系格就是 4x4，跟着它走才不会错位。 */
    private static final int CELL = 4;

    /** 边缘过渡带宽，单位是采样格：3 格 = 12 方块。 */
    private static final int BLEND_CELLS = 3;

    /** 内部空洞最多几个采样格才填（36 格 = 24x24 方块以内的小块草地）。 */
    private static final int HOLE_LIMIT = 36;

    public DenseOreLayerFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        // 出生点保护不在这里拦。地表矿石层是这个群系的「脸面」：一旦被跳过，
        // 露出来的就是普通草地和泥土，玩家会以为模组没生效。保护圈交给
        // 「圈内不长矿石群系」去管，详见 ConfigurableOreFeature 里的说明。
        OreBiomeSettings settings = OreBiomeSettings.get();
        OrePool pool = OrePool.forSettings(settings);
        if (pool.isEmpty()) {
            return false;
        }
        OreBiomeSettings.Density density = settings.density();
        // 含矿率用千分比，避开浮点重载的歧义：稀疏 6%、适中 16%、稠密 34%
        int permille = density.pick(60, 160, 340);
        // 表层泥土用双倍含矿率（封顶 90%），保证「稠密」在地表肉眼可见
        int soilPermille = Math.min(permille * 2, 900);
        int depth = density.pick(6, 14, 26);

        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        // 放置器给的坐标已经被 in_square 在本区块内随机挪过一次，先归回区块角，
        // 才能一格不漏地铺满本区块的 16x16
        int cornerX = ctx.origin().getX() & ~15;
        int cornerZ = ctx.origin().getZ() & ~15;

        // 本区块加上一圈邻居的群系分布：既用来逐列判断「这列是不是矿石群系」，
        // 也用来算「离边缘还有多远」。
        BiomeGrid grid = BiomeGrid.build(level, cornerX, cornerZ);
        if (!grid.hasOre()) {
            return false;
        }

        boolean placed = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int floor = level.getMinBuildHeight() + 1;

        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                int columnX = cornerX + x;
                int columnZ = cornerZ + z;
                // 0 = 正好在群系边缘，1000 = 完全在内部；-1 = 这一列根本不是矿石群系
                int blend = grid.blendAt(columnX, columnZ);
                if (blend < 0) {
                    continue;
                }
                // 每列用自己的高度图找地表：不再受「随机的参考高度 ±20」限制，
                // 陡坡和山包上的列也能正常铺矿。
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, columnX, columnZ);
                int bottom = Math.max(y - SCAN_DOWN, level.getMinBuildHeight());
                cursor.set(columnX, y, columnZ);
                BlockState found = level.getBlockState(cursor);
                // 向下找第一块「地面」：土层或石头都算。草叶、树叶、水、空气都会继续跳过，
                // 沙砾滩这类既不是土层也不是石头的方块同样跳过，不会去破坏河岸。
                while (y > bottom && !isTarget(found) && !isTopsoil(found)) {
                    --y;
                    cursor.setY(y);
                    found = level.getBlockState(cursor);
                }
                if (!isTarget(found) && !isTopsoil(found)) {
                    continue; // 这一列往下这个范围内既没有土层也没有石头
                }
                for (int i = 0; i < depth && y >= floor; ++i, --y) {
                    cursor.set(columnX, y, columnZ);
                    BlockState current = level.getBlockState(cursor);
                    boolean soil = isTopsoil(current);
                    if (!soil && !isTarget(current)) {
                        continue;
                    }
                    // 地表那一格（i == 0）不管是土还是石头都按双倍含矿率刷，
                    // 免得「地表规则铺石头」和「留草皮」两种情况下地表矿石疏密差一倍。
                    int rate = (soil || i == 0) ? soilPermille : permille;
                    // 越靠群系边缘，含矿率越低；到边上就是 0
                    rate = rate * blend / 1000;
                    if (random.nextInt(1000) >= rate) {
                        // 没被选成矿石的泥土 / 草皮：一律换成石头，矿石群系里不留草皮。
                        // 早先这里按 blend 概率只换一部分，想做出「边界逐渐过渡」，
                        // 结果边界那圈会成片留下草和泥土，看着就是石头矿山里突兀的绿地。
                        // 群系边界本身已经由圆斑遮罩的噪声起伏决定，是自然曲线，
                        // 边缘的过渡交给上面的含矿率（rate × blend）就够了。
                        if (soil) {
                            setBlock(level, cursor.immutable(), Blocks.STONE.defaultBlockState());
                            placed = true;
                        }
                        continue;
                    }
                    OrePool.Entry entry = pool.pick(random);
                    boolean deepslate = !soil && current.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
                    // 土层一律换成石头底矿石：这里还轮不到深板岩
                    setBlock(level, cursor.immutable(), deepslate ? entry.deepslate() : entry.stone());
                    placed = true;
                }
            }
        }
        return placed;
    }

    /** 石头 / 深板岩的判定：这两种方块按基础含矿率换矿，不碰空气、水、矿石本身。 */
    private static boolean isTarget(BlockState state) {
        return state.is(BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
    }

    /** 地表规则铺出来的土层：草方块和各种泥土。它们也要能换成矿石，否则矿石永远埋在土下。 */
    private static boolean isTopsoil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.DIRT);
    }

    /**
     * 本区块 ±一圈邻居的群系分布，按原版的 4x4 群系格采样。
     *
     * <p>这些东西原版没有现成的口子：{@code level.getBiome(pos)} 只能一列一列问，
     * 所以先把要用的范围一次性问出来存成小网格，后面 256 列都用它，省掉几万次坐标换算。
     * 网格比区块本身大一圈（{@link #BLEND_CELLS} + 1 个采样格），因为算边缘距离时
     * 要能看到区块外面的群系。</p>
     */
    private static final class BiomeGrid {
        private final int originX;
        private final int originZ;
        private final int sizeX;
        private final int sizeZ;
        private final boolean[] effective;

        private BiomeGrid(int originX, int originZ, int sizeX, int sizeZ, boolean[] effective) {
            this.originX = originX;
            this.originZ = originZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.effective = effective;
        }

        static BiomeGrid build(WorldGenLevel level, int cornerX, int cornerZ) {
            int margin = CELL * (BLEND_CELLS + 1);
            int qx0 = QuartPos.fromBlock(cornerX - margin);
            int qz0 = QuartPos.fromBlock(cornerZ - margin);
            int qx1 = QuartPos.fromBlock(cornerX + 15 + margin);
            int qz1 = QuartPos.fromBlock(cornerZ + 15 + margin);
            int sizeX = qx1 - qx0 + 1;
            int sizeZ = qz1 - qz0 + 1;

            boolean[] ore = new boolean[sizeX * sizeZ];
            // 采样用的 y 层：高度图给的是地表上方那一格，正常情况下落在世界内，
            // 但地形顶到建筑上限时会指到世界外，越界查群系会炸。夹回合法 quart 层。
            int minQy = level.getMinBuildHeight() >> 2;
            int maxQy = (level.getMaxBuildHeight() - 1) >> 2;
            for (int ix = 0; ix < sizeX; ix++) {
                int qx = qx0 + ix;
                for (int iz = 0; iz < sizeZ; iz++) {
                    int qz = qz0 + iz;
                    int blockY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                            QuartPos.toBlock(qx), QuartPos.toBlock(qz));
                    int qy = Math.max(minQy, Math.min(maxQy, QuartPos.fromBlock(blockY)));
                    // 直接读区块自己的群系格（quart），不要用 level.getBiome：
                    // 后者会经过 BiomeManager，把周围 8 个群系格按距离做一次模糊平均，
                    // 于是遮罩刚换过的那一格在边界上可能仍旧被判成「隔壁的普通群系」，
                    // 整列就被跳过、原地留下草皮——正是玩家截图里那种突兀的泥土。
                    // level.getNoiseBiome 是 LevelReader 的默认实现，优先读本区块已填好的
                    // 群系容器，和 OreBiomeChunkPatcher 改的是同一份数据，判定完全一致。
                    ore[ix * sizeZ + iz] = SpawnGuard.isOreBiome(
                            level.getNoiseBiome(qx, qy, qz));
                }
            }
            boolean[] effective = ore.clone();
            fillEnclosedHoles(ore, effective, sizeX, sizeZ);
            return new BiomeGrid(qx0, qz0, sizeX, sizeZ, effective);
        }

        boolean hasOre() {
            for (boolean cell : effective) {
                if (cell) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 这一列的处理强度：-1 = 不是矿石群系（跳过）；0 = 正好在边缘；
         * 1000 = 内部（离边缘 {@link #BLEND_CELLS} 个采样格以上）。
         */
        int blendAt(int blockX, int blockZ) {
            int cx = QuartPos.fromBlock(blockX) - originX;
            int cz = QuartPos.fromBlock(blockZ) - originZ;
            if (cx < 0 || cz < 0 || cx >= sizeX || cz >= sizeZ) {
                return -1;
            }
            if (!effective[cx * sizeZ + cz]) {
                return -1;
            }
            for (int r = 1; r <= BLEND_CELLS; r++) {
                if (!ringAllOre(cx, cz, r)) {
                    return (r - 1) * 1000 / BLEND_CELLS;
                }
            }
            return 1000;
        }

        /** 以 (cx, cz) 为中心、切比雪夫距离正好 r 的那一圈，是不是全是矿石群系。 */
        private boolean ringAllOre(int cx, int cz, int r) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = cx + dx;
                    int z = cz + dz;
                    if (x < 0 || z < 0 || x >= sizeX || z >= sizeZ) {
                        return false;
                    }
                    if (!effective[x * sizeZ + z]) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * 把「完全被矿石群系包住、而且不大」的空洞当成矿石群系。
         *
         * <p>做法就是从每个非矿石格出发做一次洪水填充：这一坨要是碰到了网格边缘，
         * 说明它是隔壁群系正常延伸进来的，不能动；要是没碰边、格数又不多，
         * 那就是气候抖动戳出来的小伤疤，填掉。填完的好处是地表不会在石头矿山中间
         * 突兀地冒出一小块草地。</p>
         */
        private static void fillEnclosedHoles(boolean[] ore, boolean[] effective,
                int sizeX, int sizeZ) {
            int total = sizeX * sizeZ;
            boolean[] seen = new boolean[total];
            int[] queue = new int[total];
            int[] members = new int[total];
            for (int start = 0; start < total; start++) {
                if (ore[start] || seen[start]) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                int count = 0;
                boolean touchesBorder = false;
                seen[start] = true;
                queue[tail++] = start;
                while (head < tail) {
                    int cell = queue[head++];
                    members[count++] = cell;
                    int cx = cell / sizeZ;
                    int cz = cell % sizeZ;
                    if (cx == 0 || cz == 0 || cx == sizeX - 1 || cz == sizeZ - 1) {
                        touchesBorder = true;
                    }
                    if (cx > 0 && !ore[cell - sizeZ] && !seen[cell - sizeZ]) {
                        seen[cell - sizeZ] = true;
                        queue[tail++] = cell - sizeZ;
                    }
                    if (cx < sizeX - 1 && !ore[cell + sizeZ] && !seen[cell + sizeZ]) {
                        seen[cell + sizeZ] = true;
                        queue[tail++] = cell + sizeZ;
                    }
                    if (cz > 0 && !ore[cell - 1] && !seen[cell - 1]) {
                        seen[cell - 1] = true;
                        queue[tail++] = cell - 1;
                    }
                    if (cz < sizeZ - 1 && !ore[cell + 1] && !seen[cell + 1]) {
                        seen[cell + 1] = true;
                        queue[tail++] = cell + 1;
                    }
                }
                if (!touchesBorder && count <= HOLE_LIMIT) {
                    for (int i = 0; i < count; i++) {
                        effective[members[i]] = true;
                    }
                }
            }
        }
    }
}
