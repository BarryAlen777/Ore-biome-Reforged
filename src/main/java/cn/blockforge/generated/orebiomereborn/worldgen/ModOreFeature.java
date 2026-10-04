package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.OreBiomeReborn;
import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import com.mojang.serialization.Codec;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模组矿脉特性：配置界面「一键扫描」记下的那些别的模组的矿石方块，
 * 由这一个特性在矿石群系里生成地下矿脉部分。
 *
 * <p>每生成一个区块，遍历一次配置里打开的模组矿石清单，每种矿按密度档位放几条矿脉。
 * 石头层换成矿石本身，深板岩层优先找该模组自己的 {@code deepslate_} 变体，
 * 没有就用同一个方块硬替换。</p>
 *
 * <p>地表的密集铺面交给 {@link DenseOreLayerFeature}，所以这里只负责往地下撒矿脉；
 * 两边读的都是同一份配置，勾选状态一致。</p>
 */
public class ModOreFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 已经警告过的方块 ID，避免刷屏。世界生成是多线程的，所以用并发集合，
     * 不能用普通 HashSet——两个区块同时写会让内部结构错乱。
     */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public ModOreFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        // 出生点保护不在这里拦：圈内根本不会长矿石群系，矿物也就没机会生成。
        // 以前在这里 return false 会让「群系在圈内露出来」时一颗矿都没有，
        // 看起来就是模组坏了。详见 ConfigurableOreFeature 里的说明。
        OreBiomeSettings settings = OreBiomeSettings.get();
        if (settings.modOres.isEmpty()) {
            return false;
        }
        OreBiomeSettings.Density density = settings.density();
        int attempts = density.pick(2, 5, 9);
        int size = density.pick(6, 9, 13);
        int minY = density == OreBiomeSettings.Density.SPARSE ? 0 : -64;
        int maxY = density == OreBiomeSettings.Density.SPARSE ? 200 : 128;
        RandomSource random = ctx.random();
        boolean placed = false;
        // 放置器给的坐标已被 in_square 在区块内随机挪过，先归回区块角再起脉，
        // 免得矿脉起点落到邻接区块、本区块反而更稀。
        int cornerX = ctx.origin().getX() & ~15;
        int cornerZ = ctx.origin().getZ() & ~15;

        for (Map.Entry<String, Boolean> entry : settings.modOres.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            String key = entry.getKey();
            Block block = OrePool.blockOf(key);
            if (block == null) {
                continue; // 整合包变了、这个方块已经不存在
            }
            try {
                OreConfiguration targets = oreConfigFor(block, ResourceLocation.tryParse(key), size);
                for (int i = 0; i < attempts; i++) {
                    BlockPos pos = new BlockPos(
                            cornerX + random.nextInt(16),
                            minY + random.nextInt(maxY - minY + 1),
                            cornerZ + random.nextInt(16));
                    placed |= Feature.ORE.place(new FeaturePlaceContext<>(
                            ctx.topFeature(), ctx.level(), ctx.chunkGenerator(), random, pos, targets));
                }
            } catch (Exception exception) {
                // 某个模组的矿石方块状态有问题时，跳过它而不是让整个特性停摆
                if (WARNED.add(key)) {
                    LOGGER.warn("[{}] 模组矿石 {} 生成失败，已跳过这种矿",
                            OreBiomeReborn.MOD_ID, key, exception);
                }
            }
        }
        return placed;
    }

    /** 给一个模组矿石方块拼出「石头层 + 深板岩层」两套替换目标。 */
    private static OreConfiguration oreConfigFor(Block block, ResourceLocation id, int size) {
        Block deepslate = OrePool.deepslateVariant(block, id);
        List<OreConfiguration.TargetBlockState> targets = new ArrayList<>(2);
        targets.add(OreConfiguration.target(new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES),
                block.defaultBlockState()));
        targets.add(OreConfiguration.target(new TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES),
                deepslate.defaultBlockState()));
        return new OreConfiguration(targets, size, 0.0F);
    }
}
