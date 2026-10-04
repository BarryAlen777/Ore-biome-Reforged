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

    /**
     * 群系大小四档。每档两个数字，都喂给 {@link cn.blockforge.generated.orebiomereborn.worldgen.OreBiomePatchMask}：
     * 「格距」是撒圆斑用的格子边长，「出斑率」是每个格子里真放一片的概率。
     * 片区就是一块圆斑，直径直接由格距决定（模拟脚本量出的中位直径 ≈ 标称值）。
     * 从这一版起圆斑遮罩是片区大小的唯一决定者——矿石群系不再登记进原版气候表
     * （气候表按最近邻选群系，会把片区切成碎块），改由注入在斑内当场换群系，
     * 所以界面里写 160 / 320 / 640 / 2560 格，游戏里就是这么大一片。
     *
     * <p>四档覆盖率都约 12%，区别只在一片有多大、隔多远出现一片。</p>
     */
    public enum BiomeSize {
        /** 小：一片直径约 160 格，隔两三百格就有一片，最好找矿。 */
        SMALL("small", 184, 0.22D),
        /** 中：一片直径约 320 格，默认档位。 */
        MEDIUM("medium", 368, 0.22D),
        /** 大：一片直径约 640 格，能逛一会儿。 */
        LARGE("large", 736, 0.22D),
        /** 超级：一片直径约 2560 格，一整眼望不到边的矿石大陆（在 1280 的基础上再翻倍）。 */
        HUGE("huge", 2944, 0.22D);

        private final String key;
        private final int patchLatticeBlocks;
        private final double patchProbability;

        BiomeSize(String key, int patchLatticeBlocks, double patchProbability) {
            this.key = key;
            this.patchLatticeBlocks = patchLatticeBlocks;
            this.patchProbability = patchProbability;
        }

        /** 存盘 / 配置界面用的字符串。 */
        public String key() {
            return key;
        }

        /** 遮罩格子的边长（格），圆斑直径约为它的 0.8～1.06 倍。 */
        public int patchLatticeBlocks() {
            return patchLatticeBlocks;
        }

        /** 每个格子里放一片矿石圆斑的概率。 */
        public double patchProbability() {
            return patchProbability;
        }

        public static BiomeSize from(String name) {
            if (name == null) return MEDIUM;
            return switch (name.toLowerCase()) {
                case "small" -> SMALL;
                case "large" -> LARGE;
                case "huge" -> HUGE;
                default -> MEDIUM;
            };
        }
    }

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
     * 群系大小档位："small" / "medium" / "large" / "huge"。
     * 老配置文件没有这一项时按默认值（中）处理。
     */
    public String biomeSize = "medium";
    /**
     * 出生点保护半径（格）：离世界出生点水平距离小于这个数的区块里不会长矿石群系，
     * 自然也就没有矿。0 = 不保护。用包装类型并在 {@link #normalize} 里兜底，
     * 老配置文件缺这一项时按默认值处理。
     */
    public Integer minSpawnDistance = DEFAULT_SPAWN_DISTANCE;

    /**
     * 配置的版本号，用来给老配置文件做迁移。老文件没有这个字段，读出来是 0。
     * 加版本号是为了改默认值时能顺手把「上一版的默认值」也一起换掉，
     * 否则玩家的 config 文件里会一直留着旧默认值，改代码也救不回来。
     */
    public int settingsVersion = SETTINGS_VERSION;

    /** 当前配置版本。1 = 最早那版；2 = 保护圈默认缩到 256；3 = 默认改回 1000；4 = 新增群系大小。 */
    public static final int SETTINGS_VERSION = 4;

    /** 默认保护半径：出门走两三分钟就能遇到矿石群系，又不至于一出生就在矿堆里。 */
    public static final int DEFAULT_SPAWN_DISTANCE = 1000;
    /** 默认群系大小：中，大致一个原版群系的规模。 */
    public static final String DEFAULT_BIOME_SIZE = "medium";
    /** 上一版（版本 2）用过的临时默认半径。迁移时要把这个旧默认值换回来。 */
    private static final int LEGACY_SPAWN_DISTANCE = 256;

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
        s.biomeSize = DEFAULT_BIOME_SIZE;
        s.minSpawnDistance = DEFAULT_SPAWN_DISTANCE;
        s.settingsVersion = SETTINGS_VERSION;
        return s;
    }

    /** 深拷贝一份，界面编辑副本，点「应用」才落到全局。 */
    public OreBiomeSettings copy() {
        OreBiomeSettings s = new OreBiomeSettings();
        s.vanillaOres = new LinkedHashMap<>(this.vanillaOres);
        s.modOres = new LinkedHashMap<>(this.modOres);
        s.density = this.density;
        s.biomeSize = this.biomeSize;
        s.minSpawnDistance = this.minSpawnDistance;
        s.settingsVersion = this.settingsVersion;
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
        // 群系大小是第 4 版新加的字段：老配置文件里没有，Gson 读出来是 null
        // （或者类字段初始值 "medium"），统一归一成合法档位。
        s.biomeSize = BiomeSize.from(s.biomeSize).key();
        if (s.settingsVersion < SETTINGS_VERSION) {
            // 迁移老配置文件。只动「还停在上一版默认值」的那些，玩家自己挑过的
            // 0 / 500 / 2000 / 4000 等值原样保留。
            // 版本 1 的默认本来就是 1000，正好是这一版要的，不用碰；
            // 版本 2 那版为了让人快点出圈，默认缩到了 256，这里把它拉回 1000。
            if (s.settingsVersion == 2
                    && s.minSpawnDistance != null
                    && s.minSpawnDistance == LEGACY_SPAWN_DISTANCE) {
                s.minSpawnDistance = DEFAULT_SPAWN_DISTANCE;
            }
            s.settingsVersion = SETTINGS_VERSION;
        }
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

    /** 群系大小档位，保证非空。 */
    public BiomeSize biomeSize() {
        return BiomeSize.from(biomeSize);
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
