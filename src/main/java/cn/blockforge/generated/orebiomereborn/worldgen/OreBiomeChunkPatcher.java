package cn.blockforge.generated.orebiomereborn.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * 区块群系重写：区块的群系填好之后，按圆斑遮罩把该换的格子换成矿石群系。
 *
 * <p><b>为什么不再在 {@code MultiNoiseBiomeSource#getNoiseBiome} 里做。</b>那条路在
 * 装了 TerraBlender 的整合包里是走不通的：TerraBlender 的
 * {@code MixinMultiNoiseBiomeSource} 在同一个方法的 {@code HEAD} 处
 * {@code @Inject(cancellable = true)} 并<b>无条件</b> {@code setReturnValue} 自己的结果。
 * Mixin 对同一个注入点的多个处理器是一条链，前一个一旦取消，后面的处理器根本不会被调用，
 * 而且先后顺序由 mixin 优先级决定、默认值相同就是未定义——所以我们的处理器可能一次都不跑，
 * 也不会报任何错（注解写的是 {@code require = 0}）。表现为「群系压根不生成，命令还搜不到」。
 * 另外 noise_boost 还把 {@code getNoiseBiome} 方法体里的取样调用整个 {@code @Redirect} 掉了。
 * 这条路依赖别人不拦截，不可靠。</p>
 *
 * <p><b>现在改成「事后重写」。</b>区块生成的 BIOMES 阶段跑完、群系容器已经写好之后，
 * 由 {@code ChunkGenerator#createBiomes} 注入拿到那个区块，直接改它的群系容器填好的格子：
 * 出生点保护圈内、圆斑遮罩外的格子不动；圈外的普通陆地（不是海洋/河流/沙滩/海岸）
 * 换成矿石群系。这样不管群系是谁挑的（原版、TerraBlender、Terralith、BiomesOPlenty、
 * noise_boost 的加速逻辑），都不影响我们——我们只覆盖结果。</p>
 *
 * <p>这一步发生在 BIOMES 阶段、装饰（FEATURES）阶段之前，所以按群系筛选的矿脉
 * （{@code placed_feature} 里的 {@code minecraft:biome}）看到的就是矿石群系，矿会长出来。</p>
 *
 * <p>线程安全：世界生成跑在多个工作线程上，每个区块只由它自己的线程处理；
 * 改群系容器前后按原版做法 {@code acquire()/release()}，且任何异常都就地吞掉——
 * 宁可这一格维持原版，也绝不能把区块生成线程带崩、把存档带崩。</p>
 */
public final class OreBiomeChunkPatcher {

    private OreBiomeChunkPatcher() {
    }

    /**
     * 把这一区块的群系按遮罩改一遍。任何情况下都返回传进来的区块，
     * 方便直接挂在 {@code CompletableFuture} 的 {@code thenApply} 上。
     */
    public static ChunkAccess patch(ChunkAccess chunk) {
        try {
            patchNow(chunk);
        } catch (Throwable ignored) {
            // 见类注释：世界生成线程上不许冒泡
        }
        return chunk;
    }

    private static void patchNow(ChunkAccess chunk) {
        if (chunk == null) {
            return;
        }
        // 能走到这里就说明「区块生成钩子」确实运行了，命令靠这个标记自证。
        OreBiomeDiagnostics.chunkSeen();

        Holder<Biome> ore = SpawnGuard.oreBiomeHolder();
        if (ore == null) {
            // 数据包里没有这个群系，或者注册表还没就绪；这一区块按原版处理
            OreBiomeDiagnostics.holderMissing();
            return;
        }

        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }

        ChunkPos pos = chunk.getPos();
        // 群系容器的一格是 4x4x4 方块（quart 坐标），区块最小角的 quart 坐标
        int quartX = QuartPos.fromBlock(pos.getMinBlockX());
        int quartZ = QuartPos.fromBlock(pos.getMinBlockZ());
        // 第 0 个 section 对应的 section 序号（主世界是 -4，即 y=-64）
        int minSection = chunk.getMinBuildHeight() >> 4;

        // 圆斑遮罩和出生点保护只看 (x,z)，所以 16 根柱子先各算一次，省掉重复判断。
        boolean[][] patchColumn = new boolean[4][4];
        boolean anyColumn = false;
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                int blockX = QuartPos.toBlock(quartX + dx);
                int blockZ = QuartPos.toBlock(quartZ + dz);
                if (SpawnGuard.withinProtectedRadius(blockX, blockZ)) {
                    OreBiomeDiagnostics.protectedArea();
                    continue;
                }
                if (!OreBiomePatchMask.allows(blockX, blockZ)) {
                    OreBiomeDiagnostics.maskRejected();
                    continue;
                }
                patchColumn[dx][dz] = true;
                anyColumn = true;
            }
        }
        if (!anyColumn) {
            return;
        }

        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null) {
                continue;
            }
            // 群系容器永远是 PalettedContainer，只是对外暴露成只读接口。
            if (!(section.getBiomes() instanceof PalettedContainer<?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) raw;
            container.acquire();
            try {
                for (int dx = 0; dx < 4; dx++) {
                    for (int dz = 0; dz < 4; dz++) {
                        if (!patchColumn[dx][dz]) {
                            continue;
                        }
                        for (int dy = 0; dy < 4; dy++) {
                            Holder<Biome> current = container.get(dx, dy, dz);
                            // 注册表句柄是同一个对象，先按引用快速跳过已经换好的格子
                            if (current == ore || SpawnGuard.isOreBiome(current)) {
                                continue;
                            }
                            if (!OreBiomeLandFilter.isReplaceableLand(current)) {
                                OreBiomeDiagnostics.nonLand();
                                continue;
                            }
                            container.getAndSetUnchecked(dx, dy, dz, ore);
                            OreBiomeDiagnostics.replaced();
                        }
                    }
                }
            } finally {
                container.release();
            }
        }
    }
}
