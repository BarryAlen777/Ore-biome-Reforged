package cn.blockforge.generated.orebiomereborn.mixin;

import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomeChunkPatcher;
import cn.blockforge.generated.orebiomereborn.worldgen.SpawnGuard;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/**
 * 把矿石群系登记进「这个群系源可能产出的群系」集合。
 *
 * <p><b>这一步是矿能不能长出来的关键。</b>1.20.1 的区块装饰阶段会先问群系源
 * {@code collectPossibleBiomes}「你这儿有哪些群系」，再只给这些群系铺特征
 * （{@code ChunkGenerator#applyBiomeDecoration} 用的就是 {@code possibleBiomes()}）。
 * 矿石群系不参加原版气候表竞争，这张候选表里本来没有它，于是：</p>
 * <ul>
 *   <li>区块装饰阶段会把「不在候选表里的群系」整片跳过——表现就是看着是矿石群系、
 *       地下却一颗矿都不长；</li>
 *   <li>原版 {@code /locate biome}、自然指南针之类的搜索在第一步就拿到空集合，
 *       直接返回「找不到」。</li>
 * </ul>
 * <p>所以这里把矿石群系追加进候选表。气候表本身没动，它依旧不参与气候竞争——
 * 「哪个坐标变成矿石群系」完全由 {@link OreBiomeChunkPatcher} 按圆斑遮罩事后重写。</p>
 *
 * <p><b>为什么这里不再注入 {@code getNoiseBiome}。</b>那个方法被 TerraBlender 在
 * {@code HEAD} 处无条件取消并返回自己的结果，挂在它后面的处理器一次都不会被调用，
 * 而且不报错。现在改成区块生成完成后重写群系容器，与谁负责挑群系无关，
 * 细节见 {@link OreBiomeChunkPatcher} 的类注释。</p>
 *
 * <p>{@code require = 0}：万一以后原版改了方法名、注入没生效，游戏照常启动，
 * 只是候选表里少了矿石群系，而不是直接崩掉。</p>
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {

    /** 矿石群系的注册表句柄，第一次用到时才查，之后缓存。 */
    @Unique
    private volatile Holder<Biome> orebiomereborn$oreHolder;

    @Inject(
            method = "collectPossibleBiomes",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private void orebiomereborn$includeOreBiome(CallbackInfoReturnable<Stream<Holder<Biome>>> cir) {
        try {
            Stream<Holder<Biome>> biomes = cir.getReturnValue();
            Holder<Biome> ore = orebiomereborn$oreHolder();
            if (biomes != null && ore != null) {
                cir.setReturnValue(Stream.concat(biomes, Stream.of(ore)));
            }
        } catch (Throwable ignored) {
            // 注册表尚未就绪时维持原版集合；绝不能因为这里出错而崩掉世界加载
        }
    }

    /** 从主世界注册表里取矿石群系的句柄；世界还没加载好时返回 null（本轮按原版处理）。 */
    @Unique
    private Holder<Biome> orebiomereborn$oreHolder() {
        Holder<Biome> cached = this.orebiomereborn$oreHolder;
        if (cached == null) {
            cached = SpawnGuard.oreBiomeHolder();
            if (cached != null) {
                this.orebiomereborn$oreHolder = cached;
            }
        }
        return cached;
    }
}
