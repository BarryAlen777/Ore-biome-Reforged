package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import com.mojang.serialization.Codec;

import java.util.Map;

/**
 * 受配置控制的矿脉特性：在原版 {@link OreFeature} 外面包一层，
 * 每次生成一个区块时先查配置，再决定「放不放、放几条、多大、放在哪个高度」。
 *
 * <p>这样矿脉的开关和密度就是运行时读 config/ore_biome_reforged.json，
 * 改完配置只需让新区块生成即可生效，不用改数据包重载世界。</p>
 *
 * <p>地下矿脉（surface=false）：按档位决定条数和高度范围——
 * 稀疏完全复刻 r7（条数少、只撒在 0 层以上），稠密完全复刻 r8（条数多、-64 到 128），
 * 适中在两者之间。地表铺面（surface=true）：贴着放置器给的地表坐标小幅抖动放矿，
 * 稀疏档直接不放（r7 没有地表铺面）。</p>
 */
public class ConfigurableOreFeature extends OreFeature {

    /** 每种矿在三个档位下的（条数, 矿脉大小）与地表铺面条数。 */
    private record Params(int countSparse, int countModerate, int countDense,
                          int sizeSparse, int sizeModerate, int sizeDense,
                          int surfaceSparse, int surfaceModerate, int surfaceDense) {
    }

    private static final Params FALLBACK = new Params(30, 60, 100, 8, 8, 8, 0, 1, 2);

    /** 数值来自 r7（稀疏）与 r8（稠密）两轮实测，适中取中间偏上。 */
    private static final Map<String, Params> TABLE = Map.of(
            "coal", new Params(60, 110, 160, 24, 24, 24, 0, 2, 4),
            "iron", new Params(60, 100, 140, 12, 16, 16, 0, 2, 3),
            "copper", new Params(48, 80, 110, 14, 16, 16, 0, 2, 3),
            "gold", new Params(24, 50, 80, 9, 12, 12, 0, 1, 2),
            "redstone", new Params(32, 70, 110, 8, 10, 10, 0, 1, 2),
            "lapis", new Params(24, 50, 80, 7, 10, 10, 0, 1, 2),
            "diamond", new Params(16, 40, 70, 8, 8, 8, 0, 0, 1),
            "emerald", new Params(12, 30, 50, 6, 8, 8, 0, 0, 1));

    private final boolean surface;

    public ConfigurableOreFeature(Codec<OreConfiguration> codec, boolean surface) {
        super(codec);
        this.surface = surface;
    }

    @Override
    public boolean place(FeaturePlaceContext<OreConfiguration> ctx) {
        OreConfiguration config = ctx.config();
        String ore = oreKey(config);
        // 出生点保护：半径以内的区块一条矿都不放
        if (SpawnGuard.tooCloseToSpawn(ctx.level(), ctx.origin())) {
            return false;
        }
        OreBiomeSettings settings = OreBiomeSettings.get();
        // 关掉的矿石一条都不放（表里没有的矿物默认放行）
        if (ore != null && !settings.isVanillaOreEnabled(ore)) {
            return false;
        }
        OreBiomeSettings.Density density = settings.density();
        Params p = ore == null ? FALLBACK : TABLE.getOrDefault(ore, FALLBACK);
        RandomSource random = ctx.random();
        boolean placed = false;

        if (surface) {
            int count = density.pick(p.surfaceSparse(), p.surfaceModerate(), p.surfaceDense());
            for (int i = 0; i < count; i++) {
                BlockPos pos = ctx.origin().offset(
                        random.nextInt(9) - 4, random.nextInt(5) - 2, random.nextInt(9) - 4);
                placed |= super.place(rebind(ctx, pos, density.pick(p.sizeSparse(), p.sizeModerate(), p.sizeDense())));
            }
        } else {
            int count = density.pick(p.countSparse(), p.countModerate(), p.countDense());
            int minY = density == OreBiomeSettings.Density.SPARSE ? 0 : -64;
            int maxY = density == OreBiomeSettings.Density.SPARSE ? 256 : 128;
            int size = density.pick(p.sizeSparse(), p.sizeModerate(), p.sizeDense());
            // 放置器给的坐标已经被 in_square 在本区块内随机挪过一次，必须先归回区块角
            // 再重新起脉，否则起点会落到邻接区块，本区块反而显得更稀。
            int cornerX = ctx.origin().getX() & ~15;
            int cornerZ = ctx.origin().getZ() & ~15;
            for (int i = 0; i < count; i++) {
                BlockPos pos = new BlockPos(
                        cornerX + random.nextInt(16),
                        minY + random.nextInt(maxY - minY + 1),
                        cornerZ + random.nextInt(16));
                placed |= super.place(rebind(ctx, pos, size));
            }
        }
        return placed;
    }

    /** 用同一个上下文、新的坐标和矿脉大小再走一遍原版矿脉算法。 */
    private static FeaturePlaceContext<OreConfiguration> rebind(
            FeaturePlaceContext<OreConfiguration> ctx, BlockPos pos, int size) {
        OreConfiguration old = ctx.config();
        OreConfiguration scaled = new OreConfiguration(old.targetStates, size, old.discardChanceOnAirExposure);
        return new FeaturePlaceContext<>(ctx.topFeature(), ctx.level(), ctx.chunkGenerator(), ctx.random(), pos, scaled);
    }

    /** 从第一个目标方块反推矿物键名：minecraft:coal_ore / deepslate_coal_ore 都得到 "coal"。 */
    private static String oreKey(OreConfiguration config) {
        if (config.targetStates.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(config.targetStates.get(0).state.getBlock());
        if (id == null || !"minecraft".equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        if (path.startsWith("deepslate_")) {
            path = path.substring("deepslate_".length());
        }
        if (path.endsWith("_ore")) {
            path = path.substring(0, path.length() - 4);
        }
        return path;
    }
}
