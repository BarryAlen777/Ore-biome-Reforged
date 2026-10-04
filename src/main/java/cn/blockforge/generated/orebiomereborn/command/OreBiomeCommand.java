package cn.blockforge.generated.orebiomereborn.command;

import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import cn.blockforge.generated.orebiomereborn.registry.ModBiomes;
import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomeDiagnostics;
import cn.blockforge.generated.orebiomereborn.worldgen.OreBiomePatchMask;
import cn.blockforge.generated.orebiomereborn.worldgen.OrePool;
import cn.blockforge.generated.orebiomereborn.worldgen.SpawnGuard;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
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

    /**
     * {@code /orebiome} 的搜索半径（方块）。圆斑本来就按网格撒出来，这个范围
     * 足够列出最近的几十片。片区是由遮罩直接算出来的，不需要一格格采样。
     */
    private static final int SEARCH_RADIUS = 20000;

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
                    List<Component> lines = new ArrayList<>();
                    lines.add(Component.literal("配置文件：" + OreBiomeSettings.file()));
                    lines.add(Component.literal("密度档位：" + settings.density
                            + "（地表往下 " + density.pick(6, 14, 26) + " 格、含矿率 "
                            + density.pick(60, 160, 340) / 10 + "%）"));
                    lines.add(Component.literal("群系大小：" + biomeSizeText(settings)
                            + "（改完要退出世界再进才生效）"));
                    lines.add(Component.literal("出生点保护：" + spawnGuardText(settings, context)));
                    lines.add(biomeLine(context));
                    lines.add(Component.literal("原版矿石：开 " + vanillaOn + " / " + OreBiomeSettings.VANILLA_ORES.size()));
                    lines.add(Component.literal("模组矿石清单：共 " + settings.modOres.size()
                            + " 种，勾选 " + enabled.size() + " 种"));
                    for (int i = 0; i < Math.min(6, enabled.size()); i++) {
                        String key = enabled.get(i);
                        Block block = OrePool.blockOf(key);
                        lines.add(Component.literal("  " + key
                                + (block == null ? "   ← 这个方块已经不存在了" : "")));
                    }
                    if (enabled.size() > 6) {
                        lines.add(Component.literal("  ……其余 " + (enabled.size() - 6) + " 种略"));
                    }
                    lines.add(Component.literal("地表矿石层可用的矿种：" + pool.size()
                            + (pool.isEmpty() ? "   ← 一种都没有，所以什么都不会生成" : "")));
                    lines.add(Component.literal("注入计数：调用 " + OreBiomeDiagnostics.calls()
                            + "，遮罩通过后替换 " + OreBiomeDiagnostics.replacedCount()));
                    lines.add(Component.literal("拦截原因：出生点 " + OreBiomeDiagnostics.protectedAreaCount()
                            + "，遮罩外 " + OreBiomeDiagnostics.maskRejectedCount()
                            + "，非陆地 " + OreBiomeDiagnostics.nonLandCount()
                            + "，句柄缺失 " + OreBiomeDiagnostics.holderMissingCount()));
                    lines.add(Component.literal(OreBiomeDiagnostics.calls() == 0
                            ? "诊断：还没有捕获到群系查询，请先走出出生点并再执行一次"
                            : (OreBiomeDiagnostics.replacedCount() == 0
                            ? "诊断：注入已运行，但当前还没有替换成功"
                            : "诊断：注入正在替换群系")));
                    lines.add(Component.literal("提示：已经生成过的老区块不会变，想看效果要去没走过的新区域"));
                    for (Component line : lines) {
                        context.getSource().sendSuccess(() -> line, false);
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
        BlockPos target = findNearestOreBiome(level, player.getBlockX(), player.getBlockZ());
        if (target == null) {
            context.getSource().sendFailure(
                    Component.translatable("commands.orebiome.not_found", SEARCH_RADIUS));
            return 0;
        }
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

    /**
     * 找离玩家最近的、确实长着矿石群系的一片地。
     *
     * <p>先用片区遮罩的网格列出最近的几十个圆斑中心（读哈希，瞬间完成），
     * 再逐个向群系源确认「这里真的会变成矿石群系」。圆斑中心若正好落在
     * 海洋、河流或出生点保护圈里，那一格仍然是原版群系，就换斑内别的点、
     * 再不行换下一片。全部确认过的点都在陆地上，传过去脚下就是矿石地带。</p>
     */
    private static BlockPos findNearestOreBiome(ServerLevel level, int blockX, int blockZ) {
        List<BlockPos> candidates = OreBiomePatchMask.nearestPatchCenters(
                blockX, blockZ, SEARCH_RADIUS, 64);
        for (BlockPos candidate : candidates) {
            BlockPos hit = probeCandidate(level, candidate);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 在候选圆斑的中心和周边几个点里，挑第一个确实是矿石群系的坐标。 */
    private static BlockPos probeCandidate(ServerLevel level, BlockPos center) {
        if (!SpawnGuard.withinProtectedRadius(center.getX(), center.getZ())
                && isOreBiomeAt(level, center.getX(), center.getZ())) {
            return center;
        }
        int offset = Math.max(8, (int) Math.round(
                OreBiomeSettings.get().biomeSize().patchLatticeBlocks() * 0.32D));
        int[][] offsets = {
                {offset, 0}, {-offset, 0}, {0, offset}, {0, -offset},
                {offset, offset}, {-offset, offset}, {offset, -offset}, {-offset, -offset}
        };
        for (int[] step : offsets) {
            int x = center.getX() + step[0];
            int z = center.getZ() + step[1];
            if (SpawnGuard.withinProtectedRadius(x, z)) {
                continue;
            }
            if (isOreBiomeAt(level, x, z)) {
                return new BlockPos(x, 64, z);
            }
        }
        return null;
    }

    /**
     * 直接问群系源：这个坐标生成出来的是不是矿石群系。
     * 不加载区块，只在气候采样层问一次，所以几千格开外也能瞬间判断。
     */
    private static boolean isOreBiomeAt(ServerLevel level, int blockX, int blockZ) {
        try {
            BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
            Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
            Holder<Biome> holder = source.getNoiseBiome(
                    QuartPos.fromBlock(blockX),
                    QuartPos.fromBlock(64),
                    QuartPos.fromBlock(blockZ),
                    sampler);
            return holder != null && holder.is(ModBiomes.ORE_BIOME);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 「你脚下的群系是什么、是不是矿石群系」这一行，直接回答「这儿为什么没矿」。 */
    private static Component biomeLine(CommandContext<CommandSourceStack> context) {
        Holder<Biome> here;
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            here = player.serverLevel().getBiome(player.blockPosition());
        } catch (Exception ignored) {
            // 服务端控制台执行时没有玩家，这一行就不带了
            return Component.literal("你脚下的群系：控制台没有坐标，略过");
        }
        String id = here.unwrapKey().map(key -> key.location().toString()).orElse("未知");
        Component name = here.unwrapKey()
                .<Component>map(key -> Component.translatable(
                        "biome." + key.location().getNamespace() + "." + key.location().getPath()))
                .orElse(Component.literal("未知"));
        boolean ore = SpawnGuard.isOreBiome(here);
        return Component.literal("你脚下的群系：").append(name)
                .append(Component.literal("（" + id + "）"))
                .append(Component.literal(ore
                        ? "  ← 就是矿石群系，这里一定有矿"
                        : "  ← 不是矿石群系，所以这附近没有模组的矿"));
    }

    /** 群系大小档位的中文说法，显示实际片区直径。 */
    private static String biomeSizeText(OreBiomeSettings settings) {
        OreBiomeSettings.BiomeSize size = settings.biomeSize();
        String label = switch (size) {
            case SMALL -> "小";
            case MEDIUM -> "中";
            case LARGE -> "大";
            case HUGE -> "超级";
        };
        int diameter = switch (size) {
            case SMALL -> 160;
            case MEDIUM -> 320;
            case LARGE -> 640;
            case HUGE -> 2560;
        };
        return label + "（片区直径约 " + diameter + " 格）";
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
