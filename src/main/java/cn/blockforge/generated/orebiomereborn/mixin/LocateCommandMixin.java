package cn.blockforge.generated.orebiomereborn.mixin;

import cn.blockforge.generated.orebiomereborn.registry.ModBiomes;
import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomeLocator;
import cn.blockforge.generated.orebiomereborn.worldgen.SpawnGuard;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.time.Duration;

/**
 * 让原版 {@code /locate biome ore_biome_reforged:ore_biome} 直接可用，而且是秒回。
 *
 * <p>为什么必须接管：原版是拿气候噪声一格格采样来找群系的，而矿石群系<b>根本不在
 * 气候表里</b>——它是区块生成完成后由 {@code OreBiomeChunkPatcher} 按圆斑遮罩改名的，
 * 群系源永远不吐这个 Holder。于是原版搜索既慢（采样点上百万个）又必然找不到。</p>
 *
 * <p>这里在 {@code locateBiome} 开头拦一刀：如果请求的<b>正好就是</b>矿石群系
 * （不是包含它的标签，标签交给原版），就改用 {@link OreBiomeLocator} 读圆斑网格算出
 * 最近的一片地，再调用原版的 {@code showLocateResult} 输出结果——聊天栏里的措辞、
 * 可点击的坐标、距离显示全都和原版一模一样，只是不再卡。</p>
 *
 * <p>{@code require = 0}：万一以后原版改了方法名导致注入没生效，游戏照常启动，
 * 只是这条捷径没了，原版搜索继续用（慢，且找不到矿石群系），而不是直接崩掉。</p>
 */
@Mixin(LocateCommand.class)
public abstract class LocateCommandMixin {

    /**
     * 搜索半径。原版 {@code MAX_BIOME_SEARCH_RADIUS} 是 6400 格，这里放大到 20000：
     * 矿石群系现在和蘑菇岛一样稀有（平均一万格才有一片），要是还守 6400，
     * 十次里有好几次什么都搜不到。而我们读的是圆斑网格、不采样气候噪声，
     * 半径翻三倍也只是多算几万个哈希，依旧是毫秒级，不会像原版那样卡住服务端。
     */
    private static final int LOCATE_RADIUS = 20000;
    /** 最多检查几个圆斑候选，够覆盖 20000 格内最近的片区。 */
    private static final int LOCATE_CANDIDATES = 256;

    @Inject(method = "locateBiome", at = @At("HEAD"), cancellable = true, require = 0)
    private static void orebiomereborn$locateOreBiome(CommandSourceStack source,
            ResourceOrTagArgument.Result<Biome> result,
            CallbackInfoReturnable<Integer> cir) {
        try {
            Holder<Biome> ore = SpawnGuard.oreBiomeHolder();
            if (ore == null) {
                return;
            }
            // 只接「按 ID 指定矿石群系」这一种；标签查询里可能还包含别的群系，
            // 最近的可能不是矿石群系，那种情况交回原版处理，不抢答。
            boolean exact = result.unwrap().left()
                    .flatMap(ref -> ref.unwrapKey())
                    .map(key -> key.equals(ModBiomes.ORE_BIOME))
                    .orElse(false);
            if (!exact) {
                return;
            }
            ServerLevel level = source.getLevel();
            BlockPos origin = origin(source);
            long started = System.nanoTime();
            BlockPos hit = OreBiomeLocator.findNearest(level, origin.getX(), origin.getZ(),
                    LOCATE_RADIUS, LOCATE_CANDIDATES, false);
            if (hit == null) {
                // 和原版一样，找不到就给一条失败消息（只是不再傻扫 6400 格）
                source.sendFailure(Component.translatable(
                        "commands.locate.biome.not_found", result.asPrintable()));
                cir.setReturnValue(0);
                return;
            }
            BlockPos surface = new BlockPos(hit.getX(),
                    OreBiomeLocator.surfaceY(level, hit.getX(), hit.getZ()), hit.getZ());
            Pair<BlockPos, Holder<Biome>> pair = Pair.of(surface, ore);
            // 第 5 个参数是「整条成功消息的翻译键」，第 6 个是「是否按三维坐标显示」。
            // 原版 locateBiome 传的就是 commands.locate.biome.success + true；
            // 早先这里误传成 "biome" + false，语言文件里查不到 "biome" 这个键，
            // 聊天栏只会把键名原样打出来（就一个词 biome），群系名和坐标全丢了。
            cir.setReturnValue(LocateCommand.showLocateResult(source, result, origin, pair,
                    "commands.locate.biome.success", true,
                    Duration.ofNanos(System.nanoTime() - started)));
        } catch (Throwable ignored) {
            // 任何意外都保持原版行为，别把 /locate 弄坏
        }
    }

    /** 命令执行点在哪儿；服务端控制台没有玩家时退到命令源坐标。 */
    private static BlockPos origin(CommandSourceStack source) {
        try {
            return source.getPlayerOrException().blockPosition();
        } catch (CommandSyntaxException e) {
            Vec3 pos = source.getPosition();
            return BlockPos.containing(pos.x, pos.y, pos.z);
        }
    }
}
