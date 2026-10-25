package cn.blockforge.generated.orebiomereborn.mixin;

import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomeLandFilter;
import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomePatchMask;
import cn.blockforge.generated.orebiomereborn.worldgen.SpawnGuard;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让「群系源」这一层也认得矿石群系，和区块里改过的群系表保持一致。
 *
 * <p><b>为什么需要这一个。</b>区块的群系表是由 {@code OreBiomeChunkPatcher} 事后改的，
 * 但服务端还有一条独立的查询路：{@code Level#getBiome} → {@code BiomeManager} →
 * {@code ServerLevel#getUncachedNoiseBiome}，最终问的是群系源（气候表），
 * 而群系源里根本没有矿石群系。结果就是「区块里是矿石群系，可生物生成、刷怪、
 * 天气、草地颜色、{@code /orebiome status} 里『你脚下的群系』都还按隔壁的原版群系算」。
 * 最直观的表现是：站在矿石群系中间，生物照样按平原刷，玩家也会觉得群系没有真的换过来。</p>
 *
 * <p>所以这里在返回值处补一刀：只要这个坐标在圆斑遮罩内、在出生点保护圈外、
 * 而且原版群系是能换的陆地（不是水域/恶地），就返回矿石群系。判断顺序和
 * {@link OreBiomeChunkPatcher} 完全一致，两边不会打架。</p>
 *
 * <p><b>为什么不做客户端。</b>客户端的 {@code ClientLevel} 也有同名方法，但客户端
 * 拿不到世界种子（只拿到哈希过的种子），圆斑遮罩在客户端算出来和真实存档对不上，
 * 硬算只会显示错的群系，所以只改服务端。F3 里显示的群系因此可能还是原版值，
 * 这是有意的取舍。</p>
 *
 * <p>{@code require = 0}：原版以后改了这个方法名时游戏照常启动，只是退回原来的行为，
 * 不会崩。</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(
            method = "getUncachedNoiseBiome",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private void orebiomereborn$patchBiomeSource(int quartX, int quartY, int quartZ,
            CallbackInfoReturnable<Holder<Biome>> cir) {
        try {
            // 下界、末地的 ServerLevel 也走这个方法，圆斑不能长到那些维度去。
            if (!Level.OVERWORLD.equals(((ServerLevel) (Object) this).dimension())) {
                return;
            }
            Holder<Biome> original = cir.getReturnValue();
            if (original == null || SpawnGuard.isOreBiome(original)) {
                return;
            }
            int blockX = QuartPos.toBlock(quartX);
            int blockZ = QuartPos.toBlock(quartZ);
            if (!OreBiomePatchMask.allows(blockX, blockZ)) {
                return;
            }
            if (SpawnGuard.withinProtectedRadius(blockX, blockZ)) {
                return;
            }
            if (!OreBiomeLandFilter.isReplaceableLand(original)) {
                return;
            }
            Holder<Biome> ore = SpawnGuard.oreBiomeHolder();
            if (ore != null) {
                cir.setReturnValue(ore);
            }
        } catch (Throwable ignored) {
            // 查群系是高频路径，出任何意外都退回原版结果，绝不能把服务端线程带崩
        }
    }
}
