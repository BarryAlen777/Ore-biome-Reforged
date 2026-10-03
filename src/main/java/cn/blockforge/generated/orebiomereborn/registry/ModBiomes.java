package cn.blockforge.generated.orebiomereborn.registry;

import cn.blockforge.generated.orebiomereborn.OreBiomeReborn;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

/**
 * 群系键。1.20.1 的群系已经是数据包注册表，内容写在
 * {@code data/<modid>/worldgen/biome/ore_biome.json} 里，由 {@code Biome.DIRECT_CODEC}
 * 解析；代码这边只保留按键取用的句柄。
 */
public final class ModBiomes {

    public static final ResourceKey<Biome> ORE_BIOME = ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(OreBiomeReborn.MOD_ID, "ore_biome"));

    private ModBiomes() {
    }
}
