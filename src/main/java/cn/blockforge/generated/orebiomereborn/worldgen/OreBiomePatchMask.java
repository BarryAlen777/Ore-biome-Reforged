package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 给矿石群系增加一层只影响水平面的片区遮罩（圆斑方案）。
 *
 * <p>做法：按档位格距 L 铺一张隐形网格，每个格子用坐标 + 世界种子哈希出
 * 「放不放一片」（出斑率 p ≈ 0.22）、斑心在格内的抖动、斑的半径
 * r ∈ [0.40, 0.53]·L。落在任何一片圆斑里的坐标就保留矿石群系，斑外由
 * {@link cn.blockforge.generated.orebiomereborn.mixin.MultiNoiseBiomeSourceMixin}
 * 维持原版群系。半径再叠两层低频平滑噪声做起伏，边界不是死板的正圆。</p>
 *
 * <p>为什么它是片区大小的唯一决定者：从这一版起，矿石群系不再登记进原版
 * 气候表参与「最近邻」竞争（那会把片区切成气候噪声尺度的碎块，档位形同虚设），
 * 而是由注入在遮罩通过后当场改名。所以直径就是半径 ×2，参数和观感一一对应
 * （184/368/736/2944 的格距对应中位直径 ≈ 160/320/640/2560 格，模拟脚本量出来的）；
 * 海洋、河流、沙滩、恶地则由注入里的原版群系标签检查单独排除。</p>
 */
public final class OreBiomePatchMask {

    /** 防止圆斑网格和世界坐标轴完全对齐。 */
    private static final double OFFSET_X = 173.0D;
    private static final double OFFSET_Z = -421.0D;

    /** 同一格子的四个哈希各用一个盐值，互不相关。 */
    private static final long SALT_PRESENT = 0L;
    private static final long SALT_JITTER_X = 0x5DEECE66DL;
    private static final long SALT_JITTER_Z = 0x0E52B9D7L;
    private static final long SALT_RADIUS = 0x2545F491L;
    /** 边界起伏用的两层低频噪声的格距系数（相对档位格距 L）。 */
    private static final double WOBBLE_CELL_A = 0.9D;
    private static final double WOBBLE_CELL_B = 2.3D;
    private static final double WOBBLE_AMP_A = 0.10D;
    private static final double WOBBLE_AMP_B = 0.06D;
    /** 半径最大可被起伏放大到的倍数，先按它做一次便宜的粗筛。 */
    private static final double WOBBLE_MAX_SCALE = 1.25D;

    private OreBiomePatchMask() {
    }

    /** 当前坐标是否落在某片矿石圆斑里。只做少量算术，适合世界生成线程调用。 */
    public static boolean allows(int blockX, int blockZ) {
        OreBiomeSettings.BiomeSize size = OreBiomeSettings.get().biomeSize();
        double lattice = size.patchLatticeBlocks();
        double probability = size.patchProbability();
        long seed = SpawnGuard.worldSeed();

        double x = blockX + OFFSET_X;
        double z = blockZ + OFFSET_Z;
        int cellX = (int) Math.floor(x / lattice);
        int cellZ = (int) Math.floor(z / lattice);

        // 圆斑最大直径 1.06·L，加上抖动和起伏也只可能盖住周围 3×3 的格子。
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int ci = cellX + dx;
                int cj = cellZ + dz;
                double present = hash(ci, cj, seed ^ SALT_PRESENT);
                if (present >= probability) {
                    continue;
                }
                double jitterX = hash(ci, cj, seed ^ SALT_JITTER_X);
                double jitterZ = hash(ci, cj, seed ^ SALT_JITTER_Z);
                double radiusHash = hash(ci, cj, seed ^ SALT_RADIUS);
                double centerX = (ci + 0.5D + (jitterX - 0.5D) * 0.55D) * lattice;
                double centerZ = (cj + 0.5D + (jitterZ - 0.5D) * 0.55D) * lattice;
                double radius = (0.40D + 0.13D * radiusHash) * lattice;

                double vecX = x - centerX;
                double vecZ = z - centerZ;
                double distSq = vecX * vecX + vecZ * vecZ;
                double maxRadius = radius * WOBBLE_MAX_SCALE;
                if (distSq > maxRadius * maxRadius) {
                    continue; // 粗筛：绝大多数邻居格子到这里就排除了
                }
                double wobble = WOBBLE_AMP_A * (valueNoise(x, z, lattice * WOBBLE_CELL_A, seed) - 0.5D) * 2.0D
                        + WOBBLE_AMP_B * (valueNoise(x, z, lattice * WOBBLE_CELL_B, seed) - 0.5D) * 2.0D;
                double edge = radius * (1.0D + wobble);
                if (distSq <= edge * edge) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 列出离 {@code (blockX, blockZ)} 最近的若干个圆斑中心，按距离由近到远。
     *
     * <p>给 {@code /orebiome} 定位用。原版那套三维搜索要在几百万个采样点上
     * 逐个查气候表，又慢又容易把服务端线程拖到看门狗报警；而片区本来就是这张
     * 网格撒出来的，直接读网格就能瞬间列出最近的几片，快且和实际生成完全一致。</p>
     *
     * @param maxSearchBlocks 最远搜索距离（方块）
     * @param limit           最多返回几个候选
     */
    public static List<BlockPos> nearestPatchCenters(int blockX, int blockZ,
            int maxSearchBlocks, int limit) {
        OreBiomeSettings.BiomeSize size = OreBiomeSettings.get().biomeSize();
        double lattice = size.patchLatticeBlocks();
        double probability = size.patchProbability();
        long seed = SpawnGuard.worldSeed();

        double x = blockX + OFFSET_X;
        double z = blockZ + OFFSET_Z;
        int baseX = (int) Math.floor(x / lattice);
        int baseZ = (int) Math.floor(z / lattice);
        int reach = (int) Math.ceil(maxSearchBlocks / lattice) + 2;

        List<double[]> spots = new ArrayList<>();
        for (int dj = -reach; dj <= reach; dj++) {
            for (int di = -reach; di <= reach; di++) {
                int ci = baseX + di;
                int cj = baseZ + dj;
                double present = hash(ci, cj, seed ^ SALT_PRESENT);
                if (present >= probability) {
                    continue;
                }
                double jitterX = hash(ci, cj, seed ^ SALT_JITTER_X);
                double jitterZ = hash(ci, cj, seed ^ SALT_JITTER_Z);
                double centerX = (ci + 0.5D + (jitterX - 0.5D) * 0.55D) * lattice;
                double centerZ = (cj + 0.5D + (jitterZ - 0.5D) * 0.55D) * lattice;
                double dx = centerX - x;
                double dz = centerZ - z;
                spots.add(new double[]{dx * dx + dz * dz, centerX, centerZ});
            }
        }
        spots.sort(Comparator.comparingDouble(spot -> spot[0]));

        List<BlockPos> result = new ArrayList<>(Math.min(limit, spots.size()));
        for (int i = 0; i < spots.size() && result.size() < limit; i++) {
            double[] spot = spots.get(i);
            result.add(new BlockPos(
                    (int) Math.round(spot[1] - OFFSET_X),
                    64,
                    (int) Math.round(spot[2] - OFFSET_Z)));
        }
        return result;
    }

    /**
     * 二维平滑值噪声：四个网格角点做双线性插值，并用 smoothstep 让边界不生硬。
     * 这里只用来给圆斑边缘加起伏，格距和档位格距成比例，所以四档观感一致。
     */
    private static double valueNoise(double x, double z, double cell, long seed) {
        double cellX = Math.floor(x / cell);
        double cellZ = Math.floor(z / cell);
        double localX = x / cell - cellX;
        double localZ = z / cell - cellZ;
        double tx = smooth(localX);
        double tz = smooth(localZ);

        double a = hash((int) cellX, (int) cellZ, seed ^ 0xB16B00B5L);
        double b = hash((int) cellX + 1, (int) cellZ, seed ^ 0xB16B00B5L);
        double c = hash((int) cellX, (int) cellZ + 1, seed ^ 0xB16B00B5L);
        double d = hash((int) cellX + 1, (int) cellZ + 1, seed ^ 0xB16B00B5L);
        double top = a + (b - a) * tx;
        double bottom = c + (d - c) * tx;
        return top + (bottom - top) * tz;
    }

    private static double smooth(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    /** 稳定的 64 位坐标 + 种子哈希，不依赖世界随机数，重启也不会变。 */
    private static double hash(int x, int z, long seed) {
        long value = ((long) x * 0x9E3779B97F4A7C15L)
                ^ ((long) z * 0xC2B2AE3D27D4EB4FL)
                ^ (seed * 0xD1B54A32D192ED03L);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * (1.0D / (1L << 53));
    }
}
