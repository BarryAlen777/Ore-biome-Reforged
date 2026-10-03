package cn.blockforge.generated.orebiomereborn.mixin;

import cn.blockforge.generated.orebiomereborn.worldgen.SpawnGuard;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 出生点保护的第二半：把矿石群系本身挡在保护圈外。
 *
 * <p>1.20.1 里「这块地是什么群系」是 {@link MultiNoiseBiomeSource} 按六根气候轴
 * 从一张参数表里挑出来的。这张表是纯气候的，跟坐标没有关系，所以数据包层面
 * 没办法写「离出生点 1000 格以内别用这个群系」。这里在它挑完之后再看一眼：
 * 挑中的是矿石群系、而位置又在保护圈里，就换成原版自己会挑的那个群系。</p>
 *
 * <p>「原版自己会挑哪个」不用猜：把参数表里矿石群系那两条摘掉，剩下的就是
 * 一张纯原版表，用同样的气候采样再查一次即可。摘出来的表只建一次、缓存在
 * 字段里，所以正常跑图（保护圈外）几乎不花时间。</p>
 *
 * <p>{@code require = 0}：万一将来原版改了方法名、注入没生效，游戏照常启动，
 * 只是退回「只有矿被挡住」的老行为，而不是直接崩掉。</p>
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {

    /** 原版那张参数表（私有方法，靠 Mixin 生成访问器）。 */
    @Invoker("parameters")
    protected abstract Climate.ParameterList<Holder<Biome>> orebiomereborn$parameters();

    /** 摘掉矿石群系之后剩下的纯原版参数表，第一次用到时才建。 */
    @Unique
    private volatile Climate.ParameterList<Holder<Biome>> orebiomereborn$vanillaOnly;

    /**
     * 坐标是「群系格」（每格 4 方块），先换算成方块再量距离。
     */
    @Inject(
            method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private void orebiomereborn$keepOreBiomeAwayFromSpawn(int quartX, int quartY, int quartZ,
            Climate.Sampler sampler, CallbackInfoReturnable<Holder<Biome>> cir) {
        if (!SpawnGuard.withinProtectedRadius(QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ))) {
            return;
        }
        if (!SpawnGuard.isOreBiome(cir.getReturnValue())) {
            return;
        }
        Climate.ParameterList<Holder<Biome>> vanilla = orebiomereborn$vanillaOnly();
        if (vanilla == null) {
            return;
        }
        Holder<Biome> fallback = vanilla.findValue(sampler.sample(quartX, quartY, quartZ));
        if (fallback != null) {
            cir.setReturnValue(fallback);
        }
    }

    /** 把矿石群系那两条参数摘出去，剩下的就是原版自己的群系表。 */
    @Unique
    private Climate.ParameterList<Holder<Biome>> orebiomereborn$vanillaOnly() {
        Climate.ParameterList<Holder<Biome>> cached = this.orebiomereborn$vanillaOnly;
        if (cached != null) {
            return cached;
        }
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> values = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, Holder<Biome>> pair : orebiomereborn$parameters().values()) {
            if (!SpawnGuard.isOreBiome(pair.getSecond())) {
                values.add(pair);
            }
        }
        if (values.isEmpty()) {
            return null; // 理论上不会发生：原版群系铺满整张表
        }
        cached = new Climate.ParameterList<>(values);
        this.orebiomereborn$vanillaOnly = cached;
        return cached;
    }
}
