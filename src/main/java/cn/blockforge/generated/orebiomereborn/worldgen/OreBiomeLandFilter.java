package cn.blockforge.generated.orebiomereborn.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「这个原版群系能不能被换成矿石群系」的统一判断。
 *
 * <p>命令（{@code /orebiome}）和世界生成（{@code MultiNoiseBiomeSourceMixin}）必须用
 * 同一套规则，否则命令报出来的坐标和游戏里真正生成的位置会对不上——用户就会遇到
 * 「指令说这里有，跑过去却没有」。</p>
 *
 * <p>规则是<b>排除法</b>：只要不是海洋、河流、沙滩、海岸这类「水面群系」、也不是
 * 恶地这类「裸露岩层群系」，就允许换成矿石群系。早先用的是一份只认原版陆地群系的白名单，
 * 装了 BiomesOPlenty 之类的群系模组时，大片模组群系会被漏掉，矿区的实际面积远小于档位标称值。</p>
 *
 * <p>恶地（badlands / mesa 系列）必须排除：它本身地表就是裸露的彩色黏土层和红砂岩，
 * 铺上矿石群系看着一模一样，玩家会以为群系边界漏了；而且恶地经常和沙漠连成大片，
 * 换进去就会出现「地图上又多出一大片矿石群系」的观感。配置界面上早就写着
 * 「恶地不会有」，这里补上判断，让代码和说明对上。</p>
 */
public final class OreBiomeLandFilter {

    /** 群系 ID 里只要带这些词，就当成水域/海岸，永远不改。 */
    private static final String[] WATER_KEYWORDS = {"ocean", "river", "beach", "shore"};

    /** 群系 ID 里只要带这些词，就当成恶地类（裸露岩层），永远不改。 */
    private static final String[] BADLANDS_KEYWORDS = {"badlands", "mesa"};

    private OreBiomeLandFilter() {
    }

    /**
     * 判定结果的缓存。
     *
     * <p>区块生成时每个区块要判上千个群系格，而 {@code getPath().toLowerCase()} 每次都新建
     * 字符串；群系总共就几十种，按 ID 缓存一次判定结果，世界生成线程上几乎零开销。</p>
     */
    private static final Map<ResourceLocation, Boolean> CACHE = new ConcurrentHashMap<>();

    /** 传进来的原版群系是否允许被换成矿石群系。 */
    public static boolean isReplaceableLand(Holder<Biome> biome) {
        if (biome == null) {
            // 拿不到群系信息时按「不算陆地」处理，宁可少替换也不要把水误判成陆地
            return false;
        }
        Optional<ResourceLocation> id = biome.unwrapKey().map(key -> key.location());
        if (id.isEmpty()) {
            // 极少数没有注册键的群系：当作可替换，免得整片陆地被漏掉
            return true;
        }
        return CACHE.computeIfAbsent(id.get(), OreBiomeLandFilter::compute);
    }

    private static boolean compute(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (String keyword : WATER_KEYWORDS) {
            if (path.contains(keyword)) {
                return false;
            }
        }
        for (String keyword : BADLANDS_KEYWORDS) {
            if (path.contains(keyword)) {
                return false;
            }
        }
        return true;
    }
}
