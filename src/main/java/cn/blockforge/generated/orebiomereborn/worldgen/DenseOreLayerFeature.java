package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * 地表矿石层：把整个区块地表往下若干格里的石头/深板岩按概率换成矿石。
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
 * <p>放置器只提供「参考地表高度」（count + in_square + heightmap），具体每一列的地表
 * 由这里自己向下扫描找第一块石头，避免依赖别的模组的方块状态。同一区块的重复生成
 * 用的是同一个装饰种子，所以多算几遍结果一致，不会在 chunk 边界留下断层。</p>
 */
public class DenseOreLayerFeature extends Feature<NoneFeatureConfiguration> {

    /** 向上/向下扫描地表的容差（格）。地形起伏在这个范围内都能跟上。 */
    private static final int SCAN_UP = 20;
    private static final int SCAN_DOWN = 40;

    public DenseOreLayerFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        // 出生点保护：半径以内的区块不做地表矿石层
        if (SpawnGuard.tooCloseToSpawn(ctx.level(), ctx.origin())) {
            return false;
        }
        OreBiomeSettings settings = OreBiomeSettings.get();
        OrePool pool = OrePool.forSettings(settings);
        if (pool.isEmpty()) {
            return false;
        }
        OreBiomeSettings.Density density = settings.density();
        // 含矿率用千分比，避开浮点重载的歧义：稀疏 6%、适中 16%、稠密 34%
        int permille = density.pick(60, 160, 340);
        int depth = density.pick(6, 14, 26);

        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        // in_square 会把起点随机挪到区块内某一格（这里正好借它拿一个「参考地表高度」），
        // 所以要先把 x/z 归回区块角，才能一格不漏地铺满本区块的 16x16
        int cornerX = origin.getX() & ~15;
        int cornerZ = origin.getZ() & ~15;
        int baseY = origin.getY();
        int scanTop = Math.min(baseY + SCAN_UP, level.getMaxBuildHeight() - 1);
        int scanFloor = Math.max(baseY - SCAN_DOWN, level.getMinBuildHeight() + 1);
        int floor = level.getMinBuildHeight() + 1;
        boolean placed = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                int columnX = cornerX + x;
                int columnZ = cornerZ + z;
                cursor.set(columnX, scanTop, columnZ);
                int y = scanTop;
                while (y > scanFloor && !isTarget(level.getBlockState(cursor))) {
                    --y;
                    cursor.setY(y);
                }
                if (!isTarget(level.getBlockState(cursor))) {
                    continue; // 这一列在扫描窗口内根本没有石头
                }
                for (int i = 0; i < depth && y >= floor; ++i, --y) {
                    cursor.set(columnX, y, columnZ);
                    BlockState current = level.getBlockState(cursor);
                    if (!isTarget(current)) {
                        continue;
                    }
                    if (random.nextInt(1000) >= permille) {
                        continue;
                    }
                    OrePool.Entry entry = pool.pick(random);
                    boolean deepslate = current.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
                    setBlock(level, cursor.immutable(), deepslate ? entry.deepslate() : entry.stone());
                    placed = true;
                }
            }
        }
        return placed;
    }

    /** 只往石头 / 深板岩里换矿，不碰空气、水、矿石本身和别的模组铺的方块。 */
    private static boolean isTarget(BlockState state) {
        return state.is(BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
    }
}
