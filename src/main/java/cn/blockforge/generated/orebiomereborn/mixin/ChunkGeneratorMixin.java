package cn.blockforge.generated.orebiomereborn.mixin;

import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomeChunkPatcher;
import cn.blockforge.generated.orebiomereborn.worldgen.SpawnGuard;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 区块群系重写的钩子（通用版）：任何 {@code ChunkGenerator} 填完群系之后，
 * 都让 {@link OreBiomeChunkPatcher} 按圆斑遮罩改一遍这一区块的群系。
 *
 * <p>主世界用的是 {@code NoiseBasedChunkGenerator}，它自己重写了
 * {@code createBiomes}，所以真正在主世界生效的是
 * {@code NoiseBasedChunkGeneratorMixin}；这一个负责兜底——万一整合包把主世界的
 * 区块生成器换成了别的实现，只要它没有自己重写 {@code createBiomes}，这里同样生效。</p>
 *
 * <p><b>为什么挂在 {@code createBiomes} 上。</b>它返回的是
 * {@code CompletableFuture<ChunkAccess>}，群系在一个异步任务里填完，所以这里在
 * {@code RETURN} 处把返回的 future 接一段 {@code thenApply}，等群系真的填好了再改。
 * 这一步跑在 BIOMES 阶段，早于装饰阶段，按群系筛选的矿脉因此能看到矿石群系。</p>
 *
 * <p><b>为什么不在 {@code MultiNoiseBiomeSource#getNoiseBiome} 里做。</b>
 * 那个方法被 TerraBlender 在 {@code HEAD} 处无条件取消并返回自己的结果，
 * 挂在它后面的注入处理器根本不会被调用（详见 {@link OreBiomeChunkPatcher} 的类注释）。</p>
 *
 * <p>{@code require = 0}：万一原版以后改了签名、注入没生效，游戏照常启动，
 * 只是矿石群系不再出现，而不是直接崩掉。命令里的自检会如实报出来。</p>
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(
            method = "createBiomes",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private void orebiomereborn$patchBiomes(Executor executor, RandomState randomState, Blender blender,
            StructureManager structureManager, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // 只有主世界的区块才改：下界/末地也用同一个区块生成器，
        // 不拦的话圆斑会在下界和末地里照着同样的坐标长出一片矿石群系。
        if (!SpawnGuard.isOverworldGenerator((ChunkGenerator) (Object) this)) {
            return;
        }
        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        if (future == null) {
            return;
        }
        cir.setReturnValue(future.thenApply(OreBiomeChunkPatcher::patch));
    }
}
