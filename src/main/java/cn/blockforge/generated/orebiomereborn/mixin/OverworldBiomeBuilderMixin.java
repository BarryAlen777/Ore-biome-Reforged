package cn.blockforge.generated.orebiomereborn.mixin;

import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomeOverworldPlacement;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 把矿石生态群系塞进主世界的群系表。
 *
 * <p>为什么非要这么做：1.20.1 里「主世界会刷哪些群系」是原版写死在
 * {@code OverworldBiomeBuilder} 里的，数据包 JSON 只能改群系本身长什么样，
 * 不能往主世界群系表里添新成员（这也是 TerraBlender 这类库存在的原因）。
 * 这里在 {@code addBiomes} 的最开头插一段，把自己那段气候区间交给收集器，
 * 它就会被原封不动收进主世界的多噪音群系表，跟原版群系完全平级。</p>
 *
 * <p>因为是在最前面插入，所以在这段气候区间里矿石群系优先于原版群系；
 * 区间之外一点影响都没有，原版地形照旧。</p>
 */
@Mixin(OverworldBiomeBuilder.class)
public abstract class OverworldBiomeBuilderMixin {

    /**
     * {@code require = 0}：万一将来原版改了方法名、注入没生效，游戏也只是
     * 照常启动（矿石群系不出现），而不是直接崩掉。
     */
    @Inject(method = "addBiomes", at = @At("HEAD"), require = 0)
    private void orebiomereborn$addOreBiome(
            Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> consumer, CallbackInfo ci) {
        OreBiomeOverworldPlacement.addTo(consumer);
    }
}
