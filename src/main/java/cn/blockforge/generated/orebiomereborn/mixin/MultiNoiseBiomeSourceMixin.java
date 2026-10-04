package cn.blockforge.generated.orebiomereborn.mixin;

import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomeDiagnostics;
import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomePatchMask;
import cn.blockforge.generated.orebiomereborn.worldgen.SpawnGuard;
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

import java.util.Set;
import java.util.stream.Stream;

/**
 * 群系大小的总开关：按坐标决定这一块格子要不要变成矿石群系。
 *
 * <p><b>为什么不再靠气候表登记群系。</b>上一版的做法是把矿石群系插进主世界
 * 气候表（OverworldBiomeBuilderMixin + OreBiomeOverworldPlacement，本轮已删除），
 * 让它凭一段气候区间去「赢」原版群系。反编译 1.20.1 的
 * {@code Climate$ParameterList.findValue} 后发现这条路走不通：原版选群系不是
 * 「落在区间里就算」，而是六维气候空间里的<b>最近邻竞争</b>——每条参数按
 * {@code Parameter.distance} 量到区间边缘的距离（区间内记 0），取总分最小的
 * 一条。原版表把内陆气候切得很密，矿石群系那条区间只可能在原版点位的缝隙里
 * 胜出，于是片区永远被气候噪声切成几百格的碎块，档位标多大都没用。
 * 「选了超级还是一小片」的根源就在这。</p>
 *
 * <p><b>这一版反过来做：</b>群系表保持纯原版，矿石群系完全不参与气候竞争；
 * 原版查完群系后这里当场改名——出生点保护圈内、圆斑遮罩外一律不动；
 * 圆斑内且原版选中的是普通主世界陆地群系（不是海洋 / 河流 / 沙滩 / 恶地）
 * 就直接换成矿石群系。片区尺度从此<b>只由 {@link OreBiomePatchMask} 的圆斑
 * 决定</b>，直径就是圆斑直径，和档位标称的 160 / 320 / 640 / 2560 格一一对应；
 * 气候噪声再也切不碎它。</p>
 *
 * <p>成本：每个群系格先过一次圆斑遮罩（九次整数哈希加一次距离比较，绝大多数
 * 格子第一步就被否掉），只有圆斑内约 12% 的格子额外做一次原版查表加四个标签
 * 判断，和原版自己查一次表的开销相当。查表结果只用来判断「这里本来是什么
 * 地形」，所以海洋、河流、沙滩、恶地照旧不会出现矿石片区。</p>
 *
 * <p>{@code require = 0}：万一将来原版改了方法名、注入没生效，游戏照常启动，
 * 只是矿石群系不再出现，而不是直接崩掉。</p>
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {

    /** 只允许替换原版主世界陆地；海洋、河流、海滩、恶地及其他维度永远不动。 */
    @Unique
    private static final Set<String> orebiomereborn$replaceableLand = Set.of(
            "bamboo_jungle", "birch_forest", "cherry_grove", "dark_forest", "deep_dark",
            "desert", "dripstone_caves", "flower_forest", "forest", "frozen_peaks",
            "grove", "ice_spikes", "jagged_peaks", "jungle", "lush_caves",
            "mangrove_swamp", "meadow", "mushroom_fields", "old_growth_birch_forest",
            "old_growth_pine_taiga", "old_growth_spruce_taiga", "plains", "savanna",
            "savanna_plateau", "snowy_plains", "snowy_slopes", "snowy_taiga", "sparse_jungle",
            "stony_peaks", "sunflower_plains", "swamp", "taiga", "windswept_forest",
            "windswept_gravelly_hills", "windswept_hills", "windswept_savanna");

    /** 原版那张纯气候参数表（私有方法，靠 Mixin 生成访问器），只用来查「这里本来是什么群系」。 */
    @Invoker("parameters")
    protected abstract Climate.ParameterList<Holder<Biome>> orebiomereborn$parameters();

    /** 矿石群系的注册表句柄，第一次用到时才查，之后缓存。 */
    @Unique
    private volatile Holder<Biome> orebiomereborn$oreHolder;

    /**
     * 坐标是「群系格」（每格 4 方块），先换算成方块再量距离和保护圈。
     */
    @Inject(
            method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void orebiomereborn$forceOreBiomeInsidePatch(int quartX, int quartY, int quartZ,
            Climate.Sampler sampler, CallbackInfoReturnable<Holder<Biome>> cir) {
        OreBiomeDiagnostics.call();
        try {
            int blockX = QuartPos.toBlock(quartX);
            int blockZ = QuartPos.toBlock(quartZ);
            // 出生点保护圈优先：圈内整片留原版，不会出现「名字是矿石群系、里面没矿」的死地。
            if (SpawnGuard.withinProtectedRadius(blockX, blockZ)) {
                OreBiomeDiagnostics.protectedArea();
                return;
            }
            // 便宜的第一道闸：圆斑遮罩不通过就是普通原版地形，绝大多数格子走到这里就返回。
            if (!OreBiomePatchMask.allows(blockX, blockZ)) {
                OreBiomeDiagnostics.maskRejected();
                return;
            }
            // 斑内只接受明确的原版主世界陆地名单，避免动态标签状态把整片地图误判掉。
            Holder<Biome> vanilla = orebiomereborn$parameters()
                    .findValue(sampler.sample(quartX, quartY, quartZ));
            if (!orebiomereborn$isReplaceableLand(vanilla)) {
                OreBiomeDiagnostics.nonLand();
                return;
            }
            Holder<Biome> ore = orebiomereborn$oreHolder();
            if (ore == null) {
                OreBiomeDiagnostics.holderMissing();
                return;
            }
            OreBiomeDiagnostics.replaced();
            cir.setReturnValue(ore);
        } catch (Throwable ignored) {
            // 世界生成跑在多个工作线程上，这里哪怕抛出一个意外也不许冒泡：
            // 宁可这一格维持原版群系，也不能让区块生成线程整个崩掉、把存档带崩。
        }
    }

    /**
     * 把矿石群系登记进「这个群系源可能出现的群系」集合。
     *
     * <p><b>这一步是整个搜索功能的关键。</b>原版查群系分两步：先问
     * {@code MultiNoiseBiomeSource#collectPossibleBiomes}「你这里有哪些群系」，
     * 再在候选里逐个采样比较。矿石群系不参加气候表竞争，原版这张候选表里
     * 就没有它，于是：
     * <ul>
     *   <li>原版 {@code /locate biome}、自然指南针等搜索在第一步就拿到空集合，
     *       直接返回「找不到」——哪怕它就站在矿石群系正中间；</li>
     *   <li>区块装饰阶段会从区块的群系集合里剔除「不在候选表里的群系」，
     *       结果就是看着是矿石群系、地下却一颗矿都不长。</li>
     * </ul>
     * 所以这里把矿石群系追加进候选表。它依旧不参与气候竞争（气候表本身没动），
     * 只是让原版知道「这个群系源会产出它」。</p>
     */
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

    /** 用注册键判断陆地，命名空间不是 minecraft 的群系也不会被本模组抢走。 */
    @Unique
    private static boolean orebiomereborn$isReplaceableLand(Holder<Biome> biome) {
        return biome != null && biome.unwrapKey().map(key ->
                "minecraft".equals(key.location().getNamespace())
                        && orebiomereborn$replaceableLand.contains(key.location().getPath()))
                .orElse(false);
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
