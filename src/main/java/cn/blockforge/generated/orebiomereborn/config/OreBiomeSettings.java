package cn.blockforge.generated.orebiomereborn.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组配置：哪些矿石在矿石群系里生成、模组矿石清册、以及整体密度档位。
 *
 * <p>内容存成一份 JSON 文件（config/ore_biome_reforged.json）。配置界面（客户端）
 * 点「应用」后写盘并替换内存里的当前实例；世界生成线程只读内存里那份
 * （{@link #get()}），所以没有锁竞争。服务端启动时会从盘上重读一次，
 * 专用服务器直接改文件也生效。</p>
 */
public final class OreBiomeSettings {

    /** 原版页面上列出的 8 种矿石（键名 = 方块路径去掉 _ore 后缀）。 */
    public static final List<String> VANILLA_ORES =
            List.of("coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald");

    /** 矿石密度三档。稀疏 = r7 的老方案，稠密 = r8 的方案，适中在两者之间。 */
    public enum Density {
        SPARSE, MODERATE, DENSE;

        public static Density from(String name) {
            if (name == null) return DENSE;
            return switch (name.toLowerCase()) {
                case "sparse" -> SPARSE;
                case "moderate" -> MODERATE;
                default -> DENSE;
            };
        }

        public int pick(int sparse, int moderate, int dense) {
            return switch (this) {
                case SPARSE -> sparse;
                case MODERATE -> moderate;
                case DENSE -> dense;
            };
        }
    }

    // ---- 存盘字段（Gson 直接序列化公开字段） ----

    /** 原版矿石开关：键 = coal/iron/...，缺省视为开。 */
    public Map<String, Boolean> vanillaOres = new LinkedHashMap<>();
    /** 模组矿石开关：键 = 方块注册 ID（"命名空间:路径"），缺省视为开。 */
    public Map<String, Boolean> modOres = new LinkedHashMap<>();
    /** 密度档位："sparse" / "moderate" / "dense"。 */
    public String density = "dense";
    /**
     * 出生点保护半径（格）：离世界出生点水平距离小于这个数的区块里，
     * 矿石群系的矿一条都不生成，避免刚出生就捡到一身钻石。0 = 不保护。
     * 用包装类型并在 {@link #normalize} 里兜底，老配置文件缺这一项时按默认值处理。
     */
    public Integer minSpawnDistance = DEFAULT_SPAWN_DISTANCE;

    /** 默认保护半径：走两三分钟能出圈，又不至于完全找不到群系。 */
    public static final int DEFAULT_SPAWN_DISTANCE = 1000;

    // ---- 内存中的当前实例 ----

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile OreBiomeSettings CURRENT = defaults();

    public static OreBiomeSettings get() {
        return CURRENT;
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("ore_biome_reforged.json");
    }

    public static OreBiomeSettings defaults() {
        OreBiomeSettings s = new OreBiomeSettings();
        s.vanillaOres = new LinkedHashMap<>();
        for (String ore : VANILLA_ORES) {
            s.vanillaOres.put(ore, true);
        }
        s.modOres = new LinkedHashMap<>();
        s.density = "dense";
        s.minSpawnDistance = DEFAULT_SPAWN_DISTANCE;
        return s;
    }

    /** 深拷贝一份，界面编辑副本，点「应用」才落到全局。 */
    public OreBiomeSettings copy() {
        OreBiomeSettings s = new OreBiomeSettings();
        s.vanillaOres = new LinkedHashMap<>(this.vanillaOres);
        s.modOres = new LinkedHashMap<>(this.modOres);
        s.density = this.density;
        s.minSpawnDistance = this.minSpawnDistance;
        return s;
    }

    /** 从盘上重读（读不到或读坏就维持现状，不炸游戏）。 */
    public static synchronized void reload() {
        try {
            Path path = file();
            if (!Files.exists(path)) return;
            String json = Files.readString(path, StandardCharsets.UTF_8);
            OreBiomeSettings loaded = GSON.fromJson(json, OreBiomeSettings.class);
            if (loaded != null) {
                CURRENT = normalize(loaded);
            }
        } catch (Exception ignored) {
            // 配置文件坏了就用默认值，不影响进游戏
        }
    }

    /** 写盘并让全局指向这份配置。 */
    public static synchronized void save(OreBiomeSettings settings) {
        OreBiomeSettings normalized = normalize(settings.copy());
        CURRENT = normalized;
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(normalized), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写盘失败（只读目录之类）时内存里仍然生效，下次启动会回到默认
        }
    }

    private static OreBiomeSettings normalize(OreBiomeSettings s) {
        if (s.vanillaOres == null) s.vanillaOres = new LinkedHashMap<>();
        if (s.modOres == null) s.modOres = new LinkedHashMap<>();
        if (s.density == null) s.density = "dense";
        if (s.minSpawnDistance == null) {
            s.minSpawnDistance = DEFAULT_SPAWN_DISTANCE;
        } else {
            // 手改文件时写飞了的值夹回来：0 = 不保护，上限一万个方块
            s.minSpawnDistance = Math.max(0, Math.min(10000, s.minSpawnDistance));
        }
        return s;
    }

    // ---- 世界生成侧的读取口 ----

    public Density density() {
        return Density.from(density);
    }

    /** 出生点保护半径（格），保证返回非空、非负。 */
    public int spawnDistance() {
        Integer value = minSpawnDistance;
        if (value == null || value < 0) {
            return DEFAULT_SPAWN_DISTANCE;
        }
        return Math.min(10000, value);
    }

    public boolean isVanillaOreEnabled(String ore) {
        return vanillaOres == null || vanillaOres.getOrDefault(ore, true);
    }

    private OreBiomeSettings() {
    }
}
