package cn.blockforge.generated.orebiomereborn.client;

import cn.blockforge.generated.orebiomereborn.OreBiomeReborn;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端入口：四个地方都能打开独立配置界面。
 *
 * <ul>
 *   <li>主菜单 Mods（模组列表）里的「配置」按钮 —— ConfigScreenHandler 扩展点；</li>
 *   <li>「创建新的世界」界面左上角的「矿石设置」按钮；</li>
 *   <li>按 Esc 打开的「游戏菜单」<b>左上角</b>的「矿石群系设置」按钮 ——
 *       用的就是原版 Button。放在左上角是因为原版菜单是竖着居中排的，
 *       任何「贴着中间那几行」的位置都会压到「进度 / 统计信息」上面；</li>
 *   <li>游戏内命令 {@code /oreconfig}。</li>
 * </ul>
 *
 * <p>两个界面按钮都是监听 {@code ScreenEvent.Init.Post}、按界面类型挑一个加，
 * 除此之外不画任何控件，不会和原版界面互相干扰。</p>
 */
@Mod.EventBusSubscriber(modid = OreBiomeReborn.MOD_ID, value = Dist.CLIENT)
public final class OreBiomeClient {
    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new OreConfigScreen(parent)));
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("oreconfig").executes(context -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreen(new OreConfigScreen(minecraft.screen));
            return 1;
        }));
    }

    /** 往「创建新的世界」和「游戏菜单」的左上角各加一个入口按钮。 */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof CreateWorldScreen) {
            Button.Builder builder = Button.builder(Component.literal("矿石设置"),
                    button -> OreConfigScreen.open(screen));
            event.addListener(builder.bounds(4, 3, 80, 20).build());
            return;
        }
        if (screen instanceof PauseScreen) {
            // 原版游戏菜单整列是竖着居中排的：屏幕中间那一行就是
            // 「进度 / 统计信息」。之前放在 height/2 - 56 正好压在这两个按钮上，
            // 所以改到左上角——那里原版什么都不放，怎么都不会重叠。
            Button.Builder builder = Button.builder(Component.literal("矿石群系设置"),
                    button -> OreConfigScreen.open(screen));
            event.addListener(builder.bounds(6, 6, 110, 20).build());
        }
    }

    private OreBiomeClient() {
    }
}
