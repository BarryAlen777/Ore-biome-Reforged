package cn.blockforge.generated.orebiomereborn.command;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import cn.blockforge.generated.orebiomereborn.registry.ModBiomes;
import cn.blockforge.generated.orebiomereborn.worldgen.OrePool;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /orebiome}：矿石群系相关的几个小工具。
 *
 * <ul>
 *   <li>{@code /orebiome}：查出离你最近的矿石生态群系，只把坐标报给你，<b>不会</b>把你传过去；</li>
 *   <li>{@code /orebiome tp}：确实想立刻过去时才会传送（默认那条命令不传送）；</li>
 *   <li>{@code /orebiome status}：打印世界生成此刻真正读到的那份配置（密度、
 *       原版矿石开关、模组矿石清单），用来确认「界面上勾的」和「生成用的」是不是同一份；</li>
 *   <li>{@code /orebiome reload}：从 config 文件重新读一遍配置。</li>
 * </ul>
 *
 * <p>矿石生态群系就是普通主世界群系的一种，跟沙漠、平原一样长在地图里。
 * 矿石群系只存在于主世界，所以无论你此刻站在哪个维度，查找的都是主世界里的位置。</p>
 */
public final class OreBiomeCommand {

    /** 搜索半径（方块）。和原版 /locate 用的一样大。 */
    private static final int SEARCH_RADIUS = 6400;
    /** 横向采样精度：越小越准、越慢。 */
    private static final int HORIZONTAL_RESOLUTION = 32;
    /** 纵向采样精度。 */
    private static final int VERTICAL_RESOLUTION = 64;

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("orebiome")
                .requires(source -> source.hasPermission(2))
                .executes(context -> locate(context, false))
                .then(Commands.literal("tp")
                        .executes(context -> locate(context, true)))
                .then(Commands.literal("status").executes(context -> {
                    OreBiomeSettings settings = OreBiomeSettings.get();
                    OreBiomeSettings.Density density = settings.density();
                    int vanillaOn = 0;
                    for (String ore : OreBiomeSettings.VANILLA_ORES) {
                        if (settings.isVanillaOreEnabled(ore)) {
                            vanillaOn++;
                        }
                    }
                    List<String> enabled = new ArrayList<>();
                    for (Map.Entry<String, Boolean> entry : settings.modOres.entrySet()) {
                        if (Boolean.TRUE.equals(entry.getValue())) {
                            enabled.add(entry.getKey());
                        }
                    }
                    OrePool pool = OrePool.forSettings(settings);
                    List<String> lines = new ArrayList<>();
                    lines.add("配置文件：" + OreBiomeSettings.file());
                    lines.add("密度档位：" + settings.density
                            + "（地表往下 " + density.pick(6, 14, 26) + " 格、含矿率 "
                            + density.pick(60, 160, 340) / 10 + "%）");
                    lines.add("出生点保护：" + spawnGuardText(settings, context));
                    lines.add("原版矿石：开 " + vanillaOn + " / " + OreBiomeSettings.VANILLA_ORES.size());
                    lines.add("模组矿石清单：共 " + settings.modOres.size()
                            + " 种，勾选 " + enabled.size() + " 种");
                    for (int i = 0; i < Math.min(6, enabled.size()); i++) {
                        String key = enabled.get(i);
                        Block block = OrePool.blockOf(key);
                        lines.add("  " + key + (block == null ? "   ← 这个方块已经不存在了" : ""));
                    }
                    if (enabled.size() > 6) {
                        lines.add("  ……其余 " + (enabled.size() - 6) + " 种略");
                    }
                    lines.add("地表矿石层可用的矿种：" + pool.size()
                            + (pool.isEmpty() ? "   ← 一种都没有，所以什么都不会生成" : ""));
                    for (String line : lines) {
                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("reload").executes(context -> {
                    OreBiomeSettings.reload();
                    context.getSource().sendSuccess(
                            () -> Component.translatable("commands.orebiome.reloaded"), false);
                    return 1;
                })));
    }

    /**
     * 找最近的矿石生态群系。
     *
     * @param teleport {@code false} 只报坐标（默认），{@code true} 才真的把人送过去
     */
    private static int locate(CommandContext<CommandSourceStack> context, boolean teleport)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        // 矿石群系只长在主世界：站在下界/末地时也去主世界找，报出来的坐标才有意义。
        ServerLevel level = player.server.getLevel(Level.OVERWORLD);
        if (level == null) {
            level = player.serverLevel();
        }
        // 以玩家所在的 X/Z、海平面高度为起点搜索（群系搜索本身是三维的）。
        BlockPos origin = new BlockPos(player.getBlockX(), 64, player.getBlockZ());
        Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(
                holder -> holder.is(ModBiomes.ORE_BIOME),
                origin,
                SEARCH_RADIUS, HORIZONTAL_RESOLUTION, VERTICAL_RESOLUTION);
        if (found == null) {
            context.getSource().sendFailure(
                    Component.translatable("commands.orebiome.not_found", SEARCH_RADIUS));
            return 0;
        }
        BlockPos target = found.getFirst();
        // 最近的矿石群系往往在几千格开外，那块地的区块根本没加载过。
        // 原版的高度图对「未加载」的区块会返回一个默认值（主世界就是基岩层 -64 附近），
        // 于是 /orebiome tp 每次都把人送到地底。这里先把目标区块加载出来，
        // 读到的才是真正的地面高度，传送才会落在草地上。
        LevelChunk chunk = level.getChunk(
                SectionPos.blockToSectionCoord(target.getX()),
                SectionPos.blockToSectionCoord(target.getZ()));
        int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                target.getX() & 15, target.getZ() & 15) + 1;
        BlockPos surface = new BlockPos(target.getX(), surfaceY, target.getZ());
        double dx = surface.getX() - player.getX();
        double dz = surface.getZ() - player.getZ();
        long distance = Math.round(Math.sqrt(dx * dx + dz * dz));
        if (teleport) {
            // surfaceY 上面算出来的就是「脚底该站的那一格」（地面方块的正上方），
            // 直接用它，人才会稳稳落在草地上，而不会悬空一格再掉下来。
            player.teleportTo(level,
                    surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D,
                    player.getYRot(), player.getXRot());
            context.getSource().sendSuccess(
                    () -> Component.translatable("commands.orebiome.success",
                            surface.getX(), surface.getZ()),
                    false);
            return 1;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("commands.orebiome.found",
                        surface.getX(), surface.getY(), surface.getZ(), distance),
                false);
        context.getSource().sendSuccess(
                () -> Component.translatable("commands.orebiome.tp_hint"), false);
        return 1;
    }

    private static String spawnGuardText(OreBiomeSettings settings,
            CommandContext<CommandSourceStack> context) {
        int guard = settings.spawnDistance();
        if (guard <= 0) {
            return "关闭（贴着出生点也刷矿）";
        }
        String text = "距出生点 " + guard + " 格以内没有矿石群系（更不会有矿）";
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            // 保护圈是按主世界出生点算的，站在下界/末地时也要拿主世界的出生点，
            // 否则报出来的「离出生点多远」是错的。
            ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
            BlockPos spawn = (overworld != null ? overworld : player.serverLevel()).getSharedSpawnPos();
            long dx = player.blockPosition().getX() - spawn.getX();
            long dz = player.blockPosition().getZ() - spawn.getZ();
            long dist = Math.round(Math.sqrt(dx * dx + dz * dz));
            text += "；你当前离出生点约 " + dist + " 格（"
                    + (dist < guard ? "还在保护圈内" : "已出保护圈") + "）";
        } catch (Exception ignored) {
            // 服务端控制台执行时没有玩家，就不带这句
        }
        return text;
    }

    private OreBiomeCommand() {
    }
}
