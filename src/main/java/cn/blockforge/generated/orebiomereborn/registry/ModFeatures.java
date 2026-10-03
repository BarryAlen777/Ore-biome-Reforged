package cn.blockforge.generated.orebiomereborn.registry;

import cn.blockforge.generated.orebiomereborn.OreBiomeReborn;
import cn.blockforge.generated.orebiomereborn.worldgen.ConfigurableOreFeature;
import cn.blockforge.generated.orebiomereborn.worldgen.DenseOreLayerFeature;
import cn.blockforge.generated.orebiomereborn.worldgen.ModOreFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 注册三个自定义世界生成特性（feature type），供 data 里的
 * configured_feature / placed_feature JSON 以 "ore_biome_reforged:xxx" 引用：
 *
 * <ul>
 *   <li>{@code configurable_ore}：地下矿脉，开关与密度读配置文件；</li>
 *   <li>{@code configurable_ore_surface}：地表铺面，同样读配置；</li>
 *   <li>{@code mod_ores}：模组矿石清单的统一生成入口。</li>
 *   <li>{@code dense_ore_layer}：地表矿石层，负责「一眼望去密密麻麻」的效果。</li>
 * </ul>
 */
public final class ModFeatures {

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, OreBiomeReborn.MOD_ID);

    public static final RegistryObject<Feature<?>> CONFIGURABLE_ORE =
            FEATURES.register("configurable_ore",
                    () -> new ConfigurableOreFeature(OreConfiguration.CODEC, false));

    public static final RegistryObject<Feature<?>> CONFIGURABLE_ORE_SURFACE =
            FEATURES.register("configurable_ore_surface",
                    () -> new ConfigurableOreFeature(OreConfiguration.CODEC, true));

    public static final RegistryObject<Feature<?>> MOD_ORES =
            FEATURES.register("mod_ores",
                    () -> new ModOreFeature(NoneFeatureConfiguration.CODEC));

    /** 地表矿石层：把整片地表往下若干格的石头按概率换成矿石（含模组矿石）。 */
    public static final RegistryObject<Feature<?>> DENSE_ORE_LAYER =
            FEATURES.register("dense_ore_layer",
                    () -> new DenseOreLayerFeature(NoneFeatureConfiguration.CODEC));

    private ModFeatures() {
    }
}
