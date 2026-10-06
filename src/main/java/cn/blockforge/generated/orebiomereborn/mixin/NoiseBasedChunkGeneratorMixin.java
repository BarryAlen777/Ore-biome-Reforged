package cn.blockforge.generated.orebiomereborn.mixin;

import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomeChunkPatcher;
import cn.blockforge.generated.orebiomereborn.worldgen.SpawnGuard;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 区块群系重写的钩子（主世界用的就是这一个）。
 *
 * <p>主世界的区块生成器是 {@code NoiseBasedChunkGenerator}，它自己重写了
 * {@code createBiomes}（内部再调私有的 {@code doCreateBiomes} 去填群系），
 * 所以必须挂在这一层的 {@code createBiomes} 上，挂在父类 {@code ChunkGenerator} 上不会被走到。
 * 兜底那一个见 {@link ChunkGeneratorMixin}。</p>
 *
 * <p>为什么选这个注入点、以及为什么放弃原来的 {@code getNoiseBiome}，
 * 详见 {@link OreBiomeChunkPatcher} 的类注释。</p>
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {

    @Inject(
            method = "createBiomes",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private void orebiomereborn$patchBiomes(Executor executor, RandomState randomState, Blender blender,
            StructureManager structureManager, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // 下界的区块生成器也是 NoiseBasedChunkGenerator，所以这里必须认生成器实例：
        // 只有主世界的那个才会被放行，否则圆斑会照同样的坐标长到下界去。
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
