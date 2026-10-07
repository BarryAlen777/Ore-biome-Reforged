package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * 「离我最近的矿石群系在哪」的唯一实现，{@code /orebiome} 和原版
 * {@code /locate biome} 都走它，保证两边报出来的坐标永远一致。
 *
 * <p><b>为什么不用原版那套三维搜索。</b>原版 {@code /locate biome} 会在半径内
 * 一格一格地采样气候噪声，采样点动辄上百万个，服务端要卡上很久；而矿石群系压根
 * 不在气候表里（它是由圆斑遮罩事后改名的），就算扫完也扫不到。这里改成直接读
 * 圆斑网格：片区本来就是按格距 L 撒出来的，算出最近的几百个斑心只是纯算术，
 * 瞬间完成，而且和世界生成用的是同一套公式，报出来的坐标就是游戏里真正会变矿的地方。</p>
 *
 * <p><b>为什么绝不加载区块。</b>早先的版本为了拿到地表高度，会对几万格外的坐标调用
 * {@code getChunk}。那等于命令一执行就强制服务端主线程现生成一片全新地形（连带的
 * 矿脉、结构全都要跑一遍），几万格外那次生成能把服务器卡住一两分钟。现在地表高度
 * 改成直接问区块生成器的噪声高度（{@link #surfaceY}），不加载任何区块，也不会触发
 * 特征装饰，开销和采样一次气候差不多。</p>
 */
public final class OreBiomeLocator {

    private OreBiomeLocator() {
    }

    /**
     * 找最近的、确实长着矿石群系的一片地。
     *
     * @param searchRadius   最远搜索距离（方块）
     * @param maxCandidates  最多检查几个圆斑候选
     * @param allowFallback  {@code true}：一块可用的地都没找到时，也把最近的、不在
     *                       保护圈里的斑心报出来（{@code /orebiome} 用，宁可报个大概
     *                       位置也不说「找不到」）；{@code false}：找不到就返回
     *                       {@code null}（{@code /locate biome} 用，必须和原版一样
     *                       老实报「没找到」）
     * @return 水平坐标（Y 仅供参考，调用方再用 {@link #surfaceY} 换成地表高度）；
     *         一个都没有时按 {@code allowFallback} 决定返回斑心还是 {@code null}
     */
    public static BlockPos findNearest(ServerLevel level, int blockX, int blockZ,
            int searchRadius, int maxCandidates, boolean allowFallback) {
        List<BlockPos> candidates = OreBiomePatchMask.nearestPatchCenters(
                blockX, blockZ, searchRadius, maxCandidates);
        BlockPos fallback = null;
        for (BlockPos candidate : candidates) {
            BlockPos hit = probeCandidate(level, candidate);
            if (hit != null) {
                return hit;
            }
            // 一个可用点都没找到时（比如周围大片是海洋），至少把网格上最近的、
            // 不在出生点保护圈里的斑心留着，而不是干脆说「找不到」。
            if (allowFallback && fallback == null && !SpawnGuard.withinProtectedRadius(
                    candidate.getX(), candidate.getZ())) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /** 在候选圆斑的中心和周边几个点里，挑第一个确实能变成矿石群系的位置。 */
    private static BlockPos probeCandidate(ServerLevel level, BlockPos center) {
        if (isUsableSpot(level, center.getX(), center.getZ())) {
            return center;
        }
        int offset = Math.max(8, (int) Math.round(
                OreBiomeSettings.get().biomeSize().patchLatticeBlocks() * 0.32D));
        int[][] offsets = {
                {offset, 0}, {-offset, 0}, {0, offset}, {0, -offset},
                {offset, offset}, {-offset, offset}, {offset, -offset}, {-offset, -offset}
        };
        for (int[] step : offsets) {
            int x = center.getX() + step[0];
            int z = center.getZ() + step[1];
            if (isUsableSpot(level, x, z)) {
                return new BlockPos(x, 64, z);
            }
        }
        return null;
    }

    /**
     * 这个坐标是不是「按规则会变成矿石群系」的地方。
     *
     * <p>三道判断，全都不依赖注入是否生效：出生点保护圈外、圆斑遮罩内、原版地形不是
     * 水域/海岸。最后一道用当前群系源实际查一次——注入生效时这里返回的就是矿石群系，
     * 直接通过；注入没生效时返回原版群系，用同一套陆地规则判断，结果一样。</p>
     */
    public static boolean isUsableSpot(ServerLevel level, int blockX, int blockZ) {
        if (SpawnGuard.withinProtectedRadius(blockX, blockZ)) {
            return false;
        }
        if (!OreBiomePatchMask.allows(blockX, blockZ)) {
            return false;
        }
        Holder<Biome> here = probeBiome(level, blockX, blockZ);
        if (here == null) {
            return false;
        }
        if (SpawnGuard.isOreBiome(here)) {
            return true;
        }
        return OreBiomeLandFilter.isReplaceableLand(here);
    }

    /**
     * 直接问群系源：这个坐标生成出来的是什么群系。
     * 不加载区块，只在气候采样层问一次，所以几千格开外也能瞬间判断。
     */
    private static Holder<Biome> probeBiome(ServerLevel level, int blockX, int blockZ) {
        try {
            BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
            // 记下实际用的群系源类名：整合包里换了群系模组时，这一行是排查兼容问题的第一手信息
            OreBiomeDiagnostics.noteBiomeSource(source.getClass().getName());
            Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
            return source.getNoiseBiome(
                    QuartPos.fromBlock(blockX),
                    QuartPos.fromBlock(64),
                    QuartPos.fromBlock(blockZ),
                    sampler);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 算地表高度，<b>不加载区块、也不生成地形</b>。
     *
     * <p>直接问区块生成器的噪声列高：{@code getBaseHeight} 就是给原版高度图打底用的
     * 那套计算，和真正生成出来的地表高度一致，但只做几次噪声采样，不会顺手把矿脉、
     * 结构也跑一遍。所以哪怕坐标在几万格开外，这里也是毫秒级返回。</p>
     *
     * <p>返回值再 +1，和早先「读区块高度图再 +1」的结果保持同一口径：宁可让人落点
     * 高一格轻轻掉下来，也不要嵌进地面方块里。</p>
     */
    public static int surfaceY(ServerLevel level, int blockX, int blockZ) {
        try {
            return level.getChunkSource().getGenerator().getBaseHeight(
                    blockX, blockZ, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    level, level.getChunkSource().randomState()) + 1;
        } catch (Throwable ignored) {
            // 拿不到精确高度时退到海平面，至少传送不会把人塞进地底
            return level.getSeaLevel() + 1;
        }
    }
}
