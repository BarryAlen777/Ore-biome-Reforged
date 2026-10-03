package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.registry.ModBiomes;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;

import java.util.function.Consumer;

/**
 * 矿石生态群系在主世界群系表里占的那一段「气候区间」。
 *
 * <p>1.20.1 的主世界用「温度 / 湿度 / 内陆度 / 侵蚀度 / 深度 / 奇异度」六根轴
 * 决定某块地该刷哪个群系（这套东西叫多噪音群系表）。这里给出的就是矿石群系
 * 对应的格子：落在格子里的地方刷矿石群系，格子外原版照旧。</p>
 *
 * <p>放在普通类里而不是 Mixin 类里，是因为 Mixin 会把类里的字段和方法合并进
 * 目标类，掺杂太多东西容易出意外；Mixin 那边只留一行调用最稳。</p>
 */
public final class OreBiomeOverworldPlacement {

    // 区间刻意选得比较窄：只在「不太冷也不太热的、内陆的、侵蚀中等的、
    // 湿度偏干到中等」的地带出现，大概占主世界不大的一块，
    // 既找得到、又不会把原版群系铺没。想更常见就放宽，想更稀有就收窄。
    private static final Climate.Parameter TEMPERATURE = Climate.Parameter.span(-0.45F, 0.55F);
    private static final Climate.Parameter HUMIDITY = Climate.Parameter.span(-0.35F, 0.30F);
    private static final Climate.Parameter CONTINENTALNESS = Climate.Parameter.span(0.03F, 1.0F);
    private static final Climate.Parameter EROSION = Climate.Parameter.span(-0.375F, 0.45F);
    private static final Climate.Parameter WEIRDNESS = Climate.Parameter.span(-1.0F, 1.0F);

    /**
     * 把矿石群系的参数点交给原版的收集器。调用点在
     * {@link OverworldBiomeBuilder#addBiomes} 的最前面，所以这段气候区间里
     * 矿石群系优先于原版群系。
     */
    public static void addTo(Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> consumer) {
        // 原版给地表群系登记参数时会写「深度 = 0」和「深度 = 1」两条，这里照做，
        // 免得群系只在某一种深度上出现。
        consumer.accept(Pair.of(point(0.0F), ModBiomes.ORE_BIOME));
        consumer.accept(Pair.of(point(1.0F), ModBiomes.ORE_BIOME));
    }

    private static Climate.ParameterPoint point(float depth) {
        return Climate.parameters(
                TEMPERATURE, HUMIDITY, CONTINENTALNESS, EROSION,
                Climate.Parameter.point(depth), WEIRDNESS, 0.0F);
    }

    private OreBiomeOverworldPlacement() {
    }
}
