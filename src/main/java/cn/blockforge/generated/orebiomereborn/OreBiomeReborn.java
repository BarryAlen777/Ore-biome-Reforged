package cn.blockforge.generated.orebiomereborn;

import cn.blockforge.generated.orebiomereborn.client.OreBiomeClient;
import cn.blockforge.generated.orebiomereborn.command.OreBiomeCommand;
import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import cn.blockforge.generated.orebiomereborn.registry.ModFeatures;
import cn.blockforge.generated.orebiomereborn.worldgen.SpawnGuard;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * 矿石生态群系（Ore Biome Reforged）：Forge 1.20.1 的主入口。
 *
 * <p>经典版 Ore Biome 只做一件事：新增一个铺满矿石的生物群系，
 * 默认只有原版矿石会生成。它不添加任何新方块、新物品或新生物，也不新增维度。
 * 这个群系就长在普通主世界里，像沙漠、平原一样是主世界群系中的一种。</p>
 *
 * <p>1.20.1 的群系、矿脉（ConfiguredFeature / PlacedFeature）全是数据包注册表，
 * 内容写在 {@code data/ore_biome_reforged/} 下的 JSON 里。而「主世界要用哪些
 * 群系」这张表在原版里是写死在 {@code OverworldBiomeBuilder} 里的，数据包改不了，
 * 所以由 {@code mixin/OverworldBiomeBuilderMixin} 把矿石群系插进主世界群系表。
 * 这个入口类只负责挂上 {@code /orebiome} 命令。</p>
 */
@Mod(OreBiomeReborn.MOD_ID)
public final class OreBiomeReborn {

    /** 模组命名空间，同时也是群系、矿脉的注册 ID 前缀。 */
    public static final String MOD_ID = "ore_biome_reforged";

    public OreBiomeReborn() {
        // 模组总线：注册自定义矿脉特性类型（configurable_ore / configurable_ore_surface /
        // mod_ores）。data 里的 configured_feature JSON 用 "ore_biome_reforged:xxx"
        // 引用它们，不挂到模组总线上注册表里就没有这些类型，创建世界时
        // 会直接「Failed to load registries」崩溃。
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModFeatures.FEATURES.register(modBus);

        // forge 总线：/orebiome 命令（默认只报最近的矿石群系坐标）
        MinecraftForge.EVENT_BUS.addListener(OreBiomeCommand::register);
        // 开服时从盘上重读一次配置（专用服务器直接改 config 文件也能生效）
        MinecraftForge.EVENT_BUS.addListener(OreBiomeReborn::onServerStarting);
        // 出生点保护要知道主世界的出生点在哪。群系挑选跑在世界生成线程上，
        // 那里拿不到服务器实例，所以在主世界加载时先记一份、卸载时清掉。
        MinecraftForge.EVENT_BUS.addListener(SpawnGuard::onLevelLoad);
        MinecraftForge.EVENT_BUS.addListener(SpawnGuard::onLevelUnload);
        // 客户端：在模组列表（Mods）里注册「配置」按钮，打开 OreConfigScreen
        // 这个独立界面；创建世界界面和 Esc 游戏菜单左上角的入口在 OreBiomeClient 里挂
        if (FMLEnvironment.dist == Dist.CLIENT) {
            OreBiomeClient.registerConfigScreen();
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        OreBiomeSettings.reload();
    }
}
