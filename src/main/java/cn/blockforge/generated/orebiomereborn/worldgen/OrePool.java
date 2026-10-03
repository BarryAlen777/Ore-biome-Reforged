package cn.blockforge.generated.orebiomereborn.worldgen;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「这块石头该换成哪种矿」的加权清单。
 *
 * <p>原版矿石按配置界面的开关进表，并且带权重：煤、铁最常见，钻石、绿宝石最稀有，
 * 这样看起来才像自然形成的矿带而不是一片彩色噪点。模组矿石按「一键扫描」记下
 * 并勾选的清单进表，每种权重相同。</p>
 *
 * <p>清单会按配置实例缓存一份：配置只在点「应用」时整体换掉，所以世界生成线程
 * 每个区块取一次缓存就行，不用反复查注册表。</p>
 */
public final class OrePool {

    /** 一种矿的两个变体：石头层用的、深板岩层用的，加上出现权重。 */
    public record Entry(BlockState stone, BlockState deepslate, int weight) {
    }

    private record VanillaOre(String key, Block stone, Block deepslate, int weight) {
    }

    /** 权重是相对值：煤 16 份、钻石 2 份、绿宝石 1 份。 */
    private static final List<VanillaOre> VANILLA = List.of(
            new VanillaOre("coal", Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 16),
            new VanillaOre("iron", Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 12),
            new VanillaOre("copper", Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 10),
            new VanillaOre("gold", Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 5),
            new VanillaOre("redstone", Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 5),
            new VanillaOre("lapis", Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 5),
            new VanillaOre("diamond", Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 2),
            new VanillaOre("emerald", Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 1));

    /** 每种模组矿石的权重。和煤铁同量级偏低一点，勾得越多越显眼。 */
    private static final int MOD_ORE_WEIGHT = 5;

    private static final Entry[] EMPTY = new Entry[0];

    private final Entry[] entries;
    private final int totalWeight;

    private OrePool(List<Entry> list) {
        this.entries = list.toArray(EMPTY);
        int sum = 0;
        for (Entry entry : this.entries) {
            sum += entry.weight();
        }
        this.totalWeight = sum;
    }

    // ---- 缓存：配置实例一换就重建 ----

    /**
     * 把「配置实例 + 由它算出的清单」绑成一个整体再发布。
     *
     * <p>不能拆成两个 volatile 字段分别写：世界生成是多线程的，
     * 拆开会出现「清单是 A 的、来源标记已经变成 B」这种撕裂状态，
     * 之后按 B 取到的却是 A 的矿石清单。绑成一个对象一次写完就没有这个问题。</p>
     */
    private record Snapshot(OreBiomeSettings source, OrePool pool) {
    }

    private static volatile Snapshot cache;

    public static OrePool forSettings(OreBiomeSettings settings) {
        Snapshot snapshot = cache;
        if (snapshot == null || snapshot.source() != settings) {
            snapshot = new Snapshot(settings, build(settings));
            cache = snapshot;
        }
        return snapshot.pool();
    }

    public static OrePool build(OreBiomeSettings settings) {
        List<Entry> list = new ArrayList<>();
        for (VanillaOre ore : VANILLA) {
            if (settings.isVanillaOreEnabled(ore.key())) {
                list.add(new Entry(ore.stone().defaultBlockState(),
                        ore.deepslate().defaultBlockState(), ore.weight()));
            }
        }
        for (Map.Entry<String, Boolean> entry : settings.modOres.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            Block block = blockOf(entry.getKey());
            if (block == null) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            list.add(new Entry(block.defaultBlockState(),
                    deepslateVariant(block, id).defaultBlockState(), MOD_ORE_WEIGHT));
        }
        return new OrePool(list);
    }

    /** 按 ID 取方块；整合包变了、方块已经不存在时返回 null。 */
    public static Block blockOf(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(location);
        return block == Blocks.AIR ? null : block;
    }

    /**
     * 深板岩层用哪种方块：优先找该模组自己的 {@code deepslate_xxx} 变体，
     * 没有就用同一个方块（经典版的矿石模组本来也不分层）。
     */
    public static Block deepslateVariant(Block block, ResourceLocation id) {
        if (id == null) {
            return block;
        }
        if (id.getPath().startsWith("deepslate_")) {
            return block;
        }
        Block deepslate = BuiltInRegistries.BLOCK.get(id.withPath("deepslate_" + id.getPath()));
        return deepslate == Blocks.AIR ? block : deepslate;
    }

    public boolean isEmpty() {
        return totalWeight <= 0;
    }

    public int size() {
        return entries.length;
    }

    /** 按权重抽一种矿。 */
    public Entry pick(RandomSource random) {
        int roll = random.nextInt(totalWeight);
        for (Entry entry : entries) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return entries[entries.length - 1];
    }
}
