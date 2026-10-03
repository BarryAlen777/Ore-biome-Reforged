package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import cn.blockforge.generated.orebiomereborn.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.event.level.LevelEvent;

/**
 * 出生点保护：世界生成时判断「这个地方离出生点够不够远」。
 *
 * <p>保护圈挡住两样东西，缺一样玩家就会看到怪东西：</p>
 *
 * <ol>
 *   <li><b>群系本身</b>：{@link cn.blockforge.generated.orebiomereborn.mixin.MultiNoiseBiomeSourceMixin}
 *       在原版挑完群系之后再看一眼——如果挑中的是矿石群系、而且离出生点太近，
 *       就换成原版本来该给的那个群系。这样保护圈里是正常的草地、沙漠、恶地，
 *       而不是一大片没有矿的秃石头。</li>
 *   <li><b>矿脉等特性</b>：{@link #tooCloseToSpawn} 是第二道保险，就算因为
 *       区块边界、群系取样精度之类的原因漏进来一格，矿也一条不放。</li>
 * </ol>
 *
 * <p>出生点从 {@link ServerLevel#getSharedSpawnPos()} 拿。世界生成跑在工作线程上，
 * 这里只读一个 volatile 引用加一次字段读取，不加锁。有一个已知的无害边界：
 * 全新存档刚创建时，原版为了找出生点会先生成 (0,0) 附近几个区块，那一刻
 * 出生点还没定下来，就按 (0,0) 算——正好这些区块也都该挡。</p>
 */
public final class SpawnGuard {

    /** 主世界的服务端实例，用来随时读到最新的出生点（/setworldspawn 改了也能跟上）。 */
    private static volatile ServerLevel overworld;

    private SpawnGuard() {
    }

    /** 主世界加载时记下它；世界生成线程要靠它找出生点。 */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel server
                && Level.OVERWORLD.equals(server.dimension())) {
            overworld = server;
        }
    }

    /** 主世界卸载（退出存档）时清掉，免得握着旧世界的引用。 */
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == overworld) {
            overworld = null;
        }
    }

    /** 当前世界的出生点；世界还没加载好时按 (0,0) 算。 */
    public static BlockPos spawnPos() {
        ServerLevel level = overworld;
        return level == null ? BlockPos.ZERO : level.getSharedSpawnPos();
    }

    /**
     * 这个<b>方块坐标</b>是否落在保护圈里。群系挑选是按方块位置算的，
     * 传进来的坐标要先从「群系格」换算成方块（见调用处）。
     */
    public static boolean withinProtectedRadius(int blockX, int blockZ) {
        int minDistance = OreBiomeSettings.get().spawnDistance();
        if (minDistance <= 0) {
            return false; // 不保护
        }
        BlockPos spawn = spawnPos();
        long dx = (long) blockX - spawn.getX();
        long dz = (long) blockZ - spawn.getZ();
        return dx * dx + dz * dz < (long) minDistance * minDistance;
    }

    /** 这个群系是不是本模组的矿石群系。 */
    public static boolean isOreBiome(Holder<Biome> biome) {
        if (biome == null) {
            return false;
        }
        return biome.unwrapKey().map(key -> key.equals(ModBiomes.ORE_BIOME)).orElse(false);
    }

    /**
     * 这个区块（以 origin 所在区块的中心判断）是否离出生点太近、不该生成矿。
     *
     * @param level  世界生成上下文
     * @param origin 特性拿到的坐标（可能被 in_square 挪过，所以先归回区块角）
     */
    public static boolean tooCloseToSpawn(WorldGenLevel level, BlockPos origin) {
        int minDistance = OreBiomeSettings.get().spawnDistance();
        if (minDistance <= 0) {
            return false; // 不保护
        }
        Level world = level.getLevel();
        BlockPos spawn = world instanceof ServerLevel server ? server.getSharedSpawnPos() : BlockPos.ZERO;
        // 按区块中心算水平距离，用平方比较省一次开方
        long dx = (origin.getX() & ~15) + 8L - spawn.getX();
        long dz = (origin.getZ() & ~15) + 8L - spawn.getZ();
        return dx * dx + dz * dz < (long) minDistance * minDistance;
    }
}
