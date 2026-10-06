package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import cn.blockforge.generated.orebiomereborn.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 出生点保护：世界生成时判断「这个地方离出生点够不够远」。
 *
 * <p>保护圈只做一件事：<b>让矿石群系本身不出现在圈里</b>。圈内该长平原就长平原、
 * 该长恶地就长恶地，跟原版一模一样，自然也就没有矿石。圈外才轮到矿石群系登场。</p>
 *
 * <p>为什么不在矿脉特性里另加一道拦截：以前那样做过，结果很糟——群系因为区块边界、
 * 群系取样精度之类的原因照样露出来，可圈里的矿脉、地表矿石层却被拦光了，玩家看到
 * 的就是「一块贴着矿石群系名字、地表全是泥土、挖下去连一粒矿都没有」的死地
 * （见 {@link cn.blockforge.generated.orebiomereborn.worldgen.ConfigurableOreFeature}）。
 * 所以现在判断只保留群系这一层：要么整片地都是矿石群系、矿管够，要么整片地
 * 都不是、一颗都没有，不会出现半吊子状态。</p>
 *
 * <p>出生点从 {@link ServerLevel#getSharedSpawnPos()} 拿。世界生成跑在工作线程上，
 * 这里只读一个 volatile 引用加一次字段读取，不加锁。有一个已知的无害边界：
 * 全新存档刚创建时，原版为了找出生点会先生成 (0,0) 附近几个区块，那一刻
 * 出生点还没定下来，就按 (0,0) 算——正好这些区块也都该挡。</p>
 */
public final class SpawnGuard {

    /** 主世界的服务端实例，用来随时读到最新的出生点（/setworldspawn 改了也能跟上）。 */
    private static volatile ServerLevel overworld;

    /**
     * 主世界当前那个区块生成器。
     *
     * <p>下界、末地用的也是同一个 {@code NoiseBasedChunkGenerator} 类，光看类型分不出维度。
     * 区块生成跑在工作线程上、拿不到「当前是哪个维度」，所以在主世界加载时把它的生成器实例
     * 记下来；重写群系时按「这个生成器是不是记下来的那个」放行，下界/末地就不会被误改。
     * 只存实例引用（{@code ConcurrentHashMap} 的键就是引用相等），退出世界时清掉。</p>
     */
    private static final Set<ChunkGenerator> OVERWORLD_GENERATORS = ConcurrentHashMap.newKeySet();

    private SpawnGuard() {
    }

    /** 主世界加载时记下它；世界生成线程要靠它找出生点。 */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel server
                && Level.OVERWORLD.equals(server.dimension())) {
            overworld = server;
            cachedOreHolder = lookupOreHolder(server.getServer());
            ChunkGenerator generator = server.getChunkSource().getGenerator();
            if (generator != null) {
                OVERWORLD_GENERATORS.add(generator);
            }
            OreBiomeDiagnostics.reset();
        }
    }

    /** 主世界卸载（退出存档）时清掉，免得握着旧世界的引用。 */
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == overworld) {
            overworld = null;
            OVERWORLD_GENERATORS.clear();
        }
    }

    /**
     * 这个区块生成器是不是主世界的那个。
     *
     * <p>返回 false 时不要重写群系：下界和末地的生成器在同一个类里，认错就会把圆斑
     * 按同样的坐标长到那些维度去。</p>
     */
    public static boolean isOverworldGenerator(ChunkGenerator generator) {
        return generator != null && OVERWORLD_GENERATORS.contains(generator);
    }

    /** 当前世界的出生点；世界还没加载好时按 (0,0) 算。 */
    public static BlockPos spawnPos() {
        ServerLevel level = currentOverworld();
        return level == null ? BlockPos.ZERO : level.getSharedSpawnPos();
    }

    /**
     * 当前世界的种子，给片区遮罩用：同一个坐标在别的世界不该长出同样的矿石片区。
     * 世界还没加载时按 0 算（客户端主菜单之类，本来也不会去生成地形）。
     */
    public static long worldSeed() {
        ServerLevel level = currentOverworld();
        return level == null ? 0L : level.getSeed();
    }

    /**
     * 事件还没把世界写入缓存时，从 Forge 当前服务器再取一次；避免新世界最初生成阶段
     * 因为时序差异拿不到种子和动态群系注册表。
     */
    private static ServerLevel currentOverworld() {
        ServerLevel level = overworld;
        if (level != null) {
            return level;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getLevel(Level.OVERWORLD);
    }

    /**
     * 这个<b>方块坐标</b>是否落在保护圈里。群系挑选是按方块位置算的，
     * 传进来的坐标要先从「群系格」换算成方块（见调用处）。
     */
    public static boolean withinProtectedRadius(int blockX, int blockZ) {
        int minDistance = OreBiomeSettings.get().spawnDistance();
        if (minDistance <= 0) {
            return false; // 不保护
        }
        BlockPos spawn = spawnPos();
        long dx = (long) blockX - spawn.getX();
        long dz = (long) blockZ - spawn.getZ();
        return dx * dx + dz * dz < (long) minDistance * minDistance;
    }

    /** 这个群系是不是本模组的矿石群系。 */
    public static boolean isOreBiome(Holder<Biome> biome) {
        if (biome == null) {
            return false;
        }
        return biome.unwrapKey().map(key -> key.equals(ModBiomes.ORE_BIOME)).orElse(false);
    }

    /**
     * 矿石群系的注册表句柄，供群系改名注入直接取用。
     *
     * <p>这一版起矿石群系不再靠气候表「赢」出来，而是遮罩通过后当场换成它的
     * Holder，所以要从注册表里把句柄取出来。世界还没加载好、或数据包里没找到
     * 这个群系时返回 null，调用方按「维持原版」处理，不会炸。</p>
     *
     * <p>句柄在 {@link ServerAboutToStartEvent}（服务器的数据包注册表刚就绪、
     * 世界还没开始建）就先缓存一份。原因是群系源第一次被问「你有哪些群系」
     * 发生在很早期的世界加载阶段，那时候 {@code getLevel} 之类的查询还拿不到
     * 主世界实例，如果那时返回 null，矿石群系就永远进不了候选表。</p>
     */
    private static volatile Holder<Biome> cachedOreHolder;

    /** 服务器数据包注册表就绪时缓存矿石群系句柄。 */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        cachedOreHolder = lookupOreHolder(event.getServer());
    }

    /** 退出存档时清掉缓存，避免握着上一个服务器的注册表。 */
    public static void onServerStopping(ServerStoppingEvent event) {
        cachedOreHolder = null;
        OVERWORLD_GENERATORS.clear();
    }

    public static Holder<Biome> oreBiomeHolder() {
        Holder<Biome> cached = cachedOreHolder;
        if (cached != null) {
            return cached;
        }
        ServerLevel level = currentOverworld();
        MinecraftServer server = level != null
                ? level.getServer()
                : ServerLifecycleHooks.getCurrentServer();
        Holder<Biome> found = lookupOreHolder(server);
        if (found != null) {
            cachedOreHolder = found;
        }
        return found;
    }

    /** 从服务器的群系注册表里按键取句柄；任何异常都当作「暂时拿不到」。 */
    private static Holder<Biome> lookupOreHolder(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        try {
            return server.registryAccess().registryOrThrow(Registries.BIOME)
                    .getHolder(ModBiomes.ORE_BIOME).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
