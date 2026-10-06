package cn.blockforge.generated.orebiomereborn.client;

import cn.blockforge.generated.orebiomereborn.OreBiomeReborn;
import cn.blockforge.generated.orebiomereborn.config.OreBiomeSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 独立的矿石生成配置界面。
 *
 * <p>注意背景必须画在 {@link #render(GuiGraphics, int, int, float)} 里、
 * 而且在 {@code super.render(...)} 之前：1.20.1 的渲染链是
 * GameRenderer -&gt; ForgeHooksClient.drawScreen -&gt; Screen.renderWithTooltip
 * &gt; Screen.render，而 {@code Screen.render} 只遍历 renderables，
 * 根本不会调用 {@code Screen.renderBackground}。把底图画在 renderBackground
 * 里等于一帧都不画，界面会变成全透明。</p>
 *
 * <p>点击反馈做了三层，确保「点没点上」一眼就能看出来：
 * 一是原版按钮那声「咔」的点击音效；二是按钮按下后往下沉 2 像素、变暗，
 * 持续几帧再弹回来；三是鼠标悬停时整块提亮并描一圈亮边。列表行也一样，
 * 点下去整行闪一下白，勾选框当场变绿或变灰。</p>
 */
public class OreConfigScreen extends Screen {
    private static final ResourceLocation WOOD_TEXTURE =
            new ResourceLocation(OreBiomeReborn.MOD_ID, "textures/gui/panel_wood.png");
    private static final ResourceLocation STONE_TEXTURE =
            new ResourceLocation(OreBiomeReborn.MOD_ID, "textures/gui/panel_stone.png");

    /** 按下动画持续多少帧（约 0.15 秒）。 */
    private static final int PRESS_FRAMES = 9;

    private static final int MARGIN = 4;
    private static final int FRAME = 4;
    private static final int TITLE_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 26;
    private static final int TAB_WIDTH = 118;
    private static final int TAB_HEIGHT = 22;
    /** 页签间距。这一版多了一个「群系大小」页签，压到 22 才放得下五个。 */
    private static final int TAB_GAP = 22;
    private static final int ROW_HEIGHT = 25;

    private static final Pattern ORE_NAME = Pattern.compile("(^|_)ores?($|_)");

    private final Screen parent;
    private final OreBiomeSettings working;
    private int tab;
    private int scroll;
    private String status = "";
    private boolean dirty;
    private boolean draggingScroll;
    private int rowFlashIndex = -1;
    private int rowFlashTicks;

    // ---- 布局（每帧按当前窗口大小重算） ----
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int bodyX;
    private int bodyY;
    private int bodyW;
    private int bodyH;
    private int footerY;
    private int tabX;
    private int tabY;
    private int contentX;
    private int contentW;
    private int listTop;
    private int listBottom;

    public OreConfigScreen(Screen parent) {
        super(Component.literal("矿石生成设置"));
        this.parent = parent;
        this.working = OreBiomeSettings.get().copy();
    }

    @Override
    protected void init() {
        computeLayout();

        // 标题栏右上角的关闭按钮
        addRenderableWidget(new FlatButton(panelX + panelW - FRAME - 21, panelY + 2,
                19, 18, "X", ButtonStyle.WOOD, () -> true, this::closeAndApply));

        // 左侧三个页签：选中状态用 BooleanSupplier 实时读，切页签就不用重建控件
        addRenderableWidget(new FlatButton(tabX, tabY, TAB_WIDTH, TAB_HEIGHT,
                "原版矿石开关", ButtonStyle.TAB, () -> tab == 0, () -> true, () -> switchTab(0)));
        addRenderableWidget(new FlatButton(tabX, tabY + TAB_GAP, TAB_WIDTH, TAB_HEIGHT,
                "模组矿石开关", ButtonStyle.TAB, () -> tab == 1, () -> true, () -> switchTab(1)));
        addRenderableWidget(new FlatButton(tabX, tabY + TAB_GAP * 2, TAB_WIDTH, TAB_HEIGHT,
                "矿石密度", ButtonStyle.TAB, () -> tab == 2, () -> true, () -> switchTab(2)));
        addRenderableWidget(new FlatButton(tabX, tabY + TAB_GAP * 3, TAB_WIDTH, TAB_HEIGHT,
                "出生点保护", ButtonStyle.TAB, () -> tab == 3, () -> true, () -> switchTab(3)));
        addRenderableWidget(new FlatButton(tabX, tabY + TAB_GAP * 4, TAB_WIDTH, TAB_HEIGHT,
                "群系大小", ButtonStyle.TAB, () -> tab == 4, () -> true, () -> switchTab(4)));

        // 页签下方的功能按钮（按页签显示/隐藏）。位置要让开下面第 5 个页签。
        int sideY = tabY + TAB_GAP * 5 + 6;
        addRenderableWidget(new FlatButton(tabX, sideY, TAB_WIDTH, 20,
                "一键扫描模组矿石", ButtonStyle.WOOD, () -> tab == 1, this::scanModOres));
        addRenderableWidget(new FlatButton(tabX, sideY + 24, TAB_WIDTH, 20,
                "全部开启", ButtonStyle.GRAY, () -> tab == 0 || tab == 1, () -> setAll(true)));
        addRenderableWidget(new FlatButton(tabX, sideY + 48, TAB_WIDTH, 20,
                "全部关闭", ButtonStyle.GRAY, () -> tab == 0 || tab == 1, () -> setAll(false)));

        // 底栏三个按钮
        int buttonY = footerY + (FOOTER_HEIGHT - 20) / 2;
        int right = panelX + panelW - FRAME - 6;
        int closeW = 60;
        int resetW = 84;
        int applyW = 60;
        addRenderableWidget(new FlatButton(right - closeW, buttonY, closeW, 20,
                "关闭", ButtonStyle.WOOD, () -> true, this::closeAndApply));
        addRenderableWidget(new FlatButton(right - closeW - 6 - resetW, buttonY, resetW, 20,
                "恢复默认", ButtonStyle.WOOD, () -> true, this::resetDefaults));
        addRenderableWidget(new FlatButton(right - closeW - 6 - resetW - 6 - applyW,
                buttonY, applyW, 20, "应用", ButtonStyle.WOOD, () -> true, this::apply)
                // 有没应用的改动时，这个按钮会亮一圈黄绿色边框提醒
                .withAccent(() -> dirty));
    }

    private void computeLayout() {
        panelX = MARGIN;
        panelY = MARGIN;
        panelW = Math.max(120, this.width - MARGIN * 2);
        panelH = Math.max(120, this.height - MARGIN * 2);
        bodyX = panelX + FRAME;
        bodyY = panelY + TITLE_HEIGHT + 2;
        bodyW = Math.max(60, panelW - FRAME * 2);
        footerY = panelY + panelH - FOOTER_HEIGHT;
        bodyH = Math.max(40, footerY - 2 - bodyY);

        tabX = bodyX + 8;
        tabY = bodyY + 22;
        contentX = tabX + TAB_WIDTH + 12;
        contentW = Math.max(60, bodyX + bodyW - 16 - contentX);
        listTop = bodyY + 22;
        listBottom = bodyY + bodyH - 4;
    }

    private void switchTab(int newTab) {
        this.tab = newTab;
        this.scroll = 0;
        this.status = "";
    }

    /** 兜底：万一有别的模组显式调用背景绘制，也画同一套底图。 */
    @Override
    public void renderBackground(GuiGraphics graphics) {
        computeLayout();
        drawBase(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        computeLayout();
        if (rowFlashTicks > 0) {
            rowFlashTicks--;
        }
        // 先把整屏铺成不透明，彻底盖掉上一帧残留
        graphics.fill(0, 0, this.width, this.height, 0xFF0B0806);
        drawBase(graphics);
        renderRows(graphics, mouseX, mouseY);
        drawNotes(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderFooter(graphics);
    }

    /** 底图：外框和标题栏用木纹，主体用石头纹，全部不透明。 */
    private void drawBase(GuiGraphics graphics) {
        tile(graphics, WOOD_TEXTURE, panelX, panelY, panelW, panelH);
        tile(graphics, STONE_TEXTURE, bodyX, bodyY, bodyW, bodyH);
        graphics.renderOutline(bodyX - 1, bodyY - 1, bodyW + 2, bodyH + 2, 0xFF1A0F08);
        graphics.renderOutline(bodyX, bodyY, bodyW, bodyH, 0xFF8A7458);

        drawTitle(graphics, "矿石生成设置", panelX + FRAME + 8, panelY + 7, 0xFFFFE8C8);
        graphics.fill(panelX + FRAME, panelY + TITLE_HEIGHT - 1,
                panelX + panelW - FRAME, panelY + TITLE_HEIGHT, 0xFF2A1B12);

        String side = switch (tab) {
            case 0 -> "Minecraft 原版";
            case 1 -> "整合包模组";
            case 2 -> "矿石密度";
            case 3 -> "出生点保护";
            default -> "群系大小";
        };
        drawTitle(graphics, side, tabX, bodyY + 6, 0xFFFFE0B0);

        String heading = switch (tab) {
            case 0 -> "原版矿石（勾选 = 会生成）";
            case 1 -> "模组矿石（先点左边的扫描按钮）";
            case 2 -> "选择一档矿石密度";
            case 3 -> "矿石群系离出生点多远才开始生成";
            default -> "选择矿石群系的大小（当前：" + biomeSizeLabel() + "）";
        };
        if (tab == 1) {
            int on = 0;
            for (Boolean value : working.modOres.values()) {
                if (Boolean.TRUE.equals(value)) {
                    on++;
                }
            }
            heading = heading + "  已勾选 " + on + "/" + working.modOres.size()
                    + " · 密度：" + densityLabel() + " · 保护圈：" + spawnDistanceLabel();
        }
        drawTitle(graphics, fit(heading, bodyX + bodyW - contentX - 12), contentX, bodyY + 6,
                0xFFFFE0B0);
    }

    /**
     * 各页的说明文字，画在列表底板之上才不会被盖暗。
     * 每一页的说明只在选中那一页时才画：以前「群系大小」的说明写在兜底
     * else 分支里，切到矿石开关页也会照画，叠在矿石列表上就是用户看到的
     * 「字体堆积」。
     */
    private void drawNotes(GuiGraphics graphics) {
        if (tab == 2) {
            int noteY = listTop + 3 * ROW_HEIGHT + 16;
            drawTitle(graphics, "密度改的是「地表往下那几格里，有多少泥土和石头变成矿石」",
                    contentX + 2, noteY, 0xFFE5D9D0);
            drawTitle(graphics, "稠密：往下 26 格；适中：14 格；稀疏：6 格",
                    contentX + 2, noteY + 12, 0xFFE5D9D0);
            drawTitle(graphics, "地表那一格用双倍换矿率（稠密约 68%、适中 32%、稀疏 12%），所以地表会露出矿石",
                    contentX + 2, noteY + 24, 0xFFE5D9D0);
            drawTitle(graphics, "没变成矿石的草皮和泥土会统一换成石头，地表不会留绿斑",
                    contentX + 2, noteY + 36, 0xFFE5D9D0);
            drawTitle(graphics, "已经生成过的区块不会变，要去没走过的新区域才看得到效果",
                    contentX + 2, noteY + 48, 0xFFFFD080);
        } else if (tab == 3) {
            int noteY = listTop + 5 * ROW_HEIGHT + 16;
            drawTitle(graphics, "保护圈以内：不长矿石群系，长的是平原、恶地这些普通地形",
                    contentX + 2, noteY, 0xFFE5D9D0);
            drawTitle(graphics, "出了圈才会遇到矿石群系；遇到就有矿，不会再出现「有群系却没矿」的死地",
                    contentX + 2, noteY + 12, 0xFFE5D9D0);
            drawTitle(graphics, "只对之后新生成的区块生效；圈的大小随时可以在这里改",
                    contentX + 2, noteY + 24, 0xFFFFD080);
        } else if (tab == 4) {
            int noteY = listTop + 4 * ROW_HEIGHT + 16;
            drawTitle(graphics, "矿石片区只出现在陆地：海洋、河流、沙滩、恶地都不会有",
                    contentX + 2, noteY, 0xFFE5D9D0);
            drawTitle(graphics, "小 ≈ 一片直径约 160 格；中 ≈ 320 格；大 ≈ 640 格；超大 ≈ 连成一片",
                    contentX + 2, noteY + 12, 0xFFE5D9D0);
            drawTitle(graphics, "片区外会自然回到原版群系；改完要退出世界再进，旧区块不会变化",
                    contentX + 2, noteY + 24, 0xFFFFD080);
        }
    }

    private String densityLabel() {
        return switch (working.density()) {
            case SPARSE -> "稀疏";
            case MODERATE -> "适中";
            case DENSE -> "稠密";
        };
    }

    private String spawnDistanceLabel() {
        int distance = working.spawnDistance();
        return distance <= 0 ? "关" : distance + " 格";
    }

    private String biomeSizeLabel() {
        return switch (working.biomeSize()) {
            case SMALL -> "小";
            case MEDIUM -> "中";
            case LARGE -> "大";
            case HUGE -> "超级";
        };
    }

    /** 16x16 贴图平铺一块矩形，超出部分裁掉。 */
    private void tile(GuiGraphics graphics, ResourceLocation texture, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        graphics.enableScissor(x, y, x + w, y + h);
        for (int ty = y; ty < y + h; ty += 16) {
            for (int tx = x; tx < x + w; tx += 16) {
                graphics.blit(texture, tx, ty, 0.0F, 0.0F, 16, 16, 16, 16);
            }
        }
        graphics.disableScissor();
    }

    private void drawTitle(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(this.font, text, x + 1, y + 1, 0xFF1A0F08, false);
        graphics.drawString(this.font, text, x, y, color, false);
    }

    /**
     * 居中文本、不带阴影。原版 drawCenteredString 会顺手画一层同字色的
     * 偏移阴影，深色字配浅灰底时那层阴影和字几乎糊在一起，就是用户说的
     * 「按钮重影」。按钮文字一律用这个画，只画一遍。
     */
    private void centeredText(GuiGraphics graphics, String text, int centerX, int y, int color) {
        graphics.drawString(this.font, text, centerX - this.font.width(text) / 2, y, color, false);
    }

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Row> rows = currentRows();
        int visible = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        int maxScroll = Math.max(0, rows.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        // 整块列表底板：让空白区域也像是列表的一部分，而不是没画完
        graphics.fill(contentX - 2, listTop - 2, contentX + contentW + 2, listBottom + 2, 0x99000000);
        graphics.renderOutline(contentX - 2, listTop - 2, contentW + 4, listBottom - listTop + 4,
                0xFF1A0F08);

        if (rows.isEmpty()) {
            String hint = tab == 1
                    ? "还没有模组矿石，点左边的「一键扫描模组矿石」"
                    : "这里还没有内容";
            drawTitle(graphics, fit(hint, contentW - 4), contentX + 2, listTop + 6, 0xFFFFE0B0);
        }

        Row hoveredRow = null;
        for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
            int index = i + scroll;
            Row row = rows.get(index);
            int y = listTop + i * ROW_HEIGHT;
            boolean hover = mouseX >= contentX && mouseX < contentX + contentW
                    && mouseY >= y && mouseY < y + ROW_HEIGHT;
            boolean flash = index == rowFlashIndex && rowFlashTicks > 0;
            int background;
            if (flash) {
                background = 0xFF8FBF6A;
            } else if (hover) {
                background = 0xE06A4A2E;
            } else {
                background = 0xB0000000;
            }
            graphics.fill(contentX, y, contentX + contentW, y + ROW_HEIGHT, background);
            graphics.fill(contentX, y + ROW_HEIGHT - 1, contentX + contentW,
                    y + ROW_HEIGHT, 0xFF1A0F08);
            if (hover) {
                graphics.renderOutline(contentX, y, contentW, ROW_HEIGHT - 1, 0xFFFFD9A0);
            }
            graphics.renderItem(row.icon(), contentX + 4, y + 4);
            String text = fit(row.name(), contentW - 34 - 24);
            int textColor = flash ? 0xFF10240A
                    : row.enabled() ? 0xFFFFFFFF : 0xFF8C8C8C;
            graphics.drawString(this.font, text, contentX + 28, y + 9, textColor, true);
            drawCheckbox(graphics, contentX + contentW - 22, y + 4, row.enabled(), hover);
            if (hover && !text.equals(row.name())) {
                hoveredRow = row;
            }
        }

        if (hoveredRow != null) {
            setTooltipForNextRenderPass(Component.literal(hoveredRow.name()));
        }

        if (rows.size() > visible) {
            int trackX = contentX + contentW + 4;
            int trackH = listBottom - listTop;
            int barH = Math.max(18, trackH * visible / rows.size());
            int barY = listTop + (trackH - barH) * scroll / Math.max(1, maxScroll);
            graphics.fill(trackX, listTop, trackX + 7, listBottom, 0x80000000);
            graphics.fill(trackX, barY, trackX + 7, barY + barH, 0xFFB08D63);
            graphics.renderOutline(trackX, barY, 7, barH, 0xFF1A0F08);
        }
    }

    /** 名字太长就截断加省略号，避免和勾选框重叠。 */
    private String fit(String text, int maxWidth) {
        if (maxWidth <= 8 || this.font.width(text) <= maxWidth) {
            return text;
        }
        String result = text;
        while (result.length() > 1 && this.font.width(result + "...") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "...";
    }

    private void drawCheckbox(GuiGraphics graphics, int x, int y, boolean checked, boolean hover) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF1A0F08);
        graphics.fill(x + 2, y + 2, x + 16, y + 16, checked ? 0xFFDCE9D8 : 0xFF9A9A9A);
        graphics.fill(x + 2, y + 2, x + 16, y + 4, checked ? 0xFFF4F4F4 : 0xFFC0C0C0);
        graphics.fill(x + 2, y + 14, x + 16, y + 16, checked ? 0xFF6A9A6A : 0xFF6A6A6A);
        if (checked) {
            graphics.drawCenteredString(this.font, "✔", x + 9, y + 3, 0xFF176B28);
            graphics.renderOutline(x, y, 18, 18, hover ? 0xFFB6F0A0 : 0xFF3F8B3F);
        } else {
            graphics.renderOutline(x, y, 18, 18, hover ? 0xFFF0D0A0 : 0xFF5A5A5A);
        }
    }

    private void renderFooter(GuiGraphics graphics) {
        int buttonStart = panelX + panelW - FRAME - 6 - (60 + 6 + 84 + 6 + 60);
        int available = Math.max(40, buttonStart - (bodyX + 8) - 10);
        String message;
        int color;
        if (!status.isEmpty()) {
            message = status;
            color = dirty ? 0xFFFFD060 : 0xFF9BE29B;
        } else if (dirty) {
            message = "[!] 有改动还没应用，点右边的「应用」才会生效";
            color = 0xFFFFD060;
        } else {
            message = "提示：只影响之后新生成的区块，勾选的矿石会生成";
            color = 0xFFE5D9D0;
        }
        message = fit(message, available);
        graphics.drawString(this.font, message, bodyX + 8,
                footerY + (FOOTER_HEIGHT - 8) / 2, color, true);
    }

    private record Row(ItemStack icon, String name, boolean enabled, Runnable action) {
    }

    private List<Row> currentRows() {
        List<Row> rows = new ArrayList<>();
        if (tab == 0) {
            for (String ore : OreBiomeSettings.VANILLA_ORES) {
                ItemStack stack = iconOf(ResourceLocation.fromNamespaceAndPath("minecraft", ore + "_ore"));
                rows.add(new Row(stack, stack.getHoverName().getString(),
                        working.isVanillaOreEnabled(ore), () -> toggleVanilla(ore)));
            }
        } else if (tab == 1) {
            List<String> keys = new ArrayList<>(working.modOres.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                ItemStack stack = iconOf(ResourceLocation.tryParse(key));
                rows.add(new Row(stack, stack.getHoverName().getString() + "  [" + key + "]",
                        !Boolean.FALSE.equals(working.modOres.get(key)), () -> toggleMod(key)));
            }
        } else if (tab == 2) {
            rows.add(densityRow("sparse", "稀疏：矿脉少，基本只在 0 层以上", Items.COAL_ORE));
            rows.add(densityRow("moderate", "适中：地表有一层矿石，地下矿脉较密", Items.IRON_ORE));
            rows.add(densityRow("dense", "稠密：地表铺满矿石，最接近经典版观感", Items.DIAMOND_ORE));
        } else if (tab == 3) {
            rows.add(spawnRow(0, "不保护：出生点旁边也能长矿石群系"));
            rows.add(spawnRow(256, "256 格：小圈保护，走几步就出圈"));
            rows.add(spawnRow(1000, "1000 格：推荐，出门走两三分钟"));
            rows.add(spawnRow(2000, "2000 格：大圈保护"));
            rows.add(spawnRow(4000, "4000 格：要跑很远才找得到群系"));
        } else {
            rows.add(biomeSizeRow("small", "小群系：一片直径约 160 格，最好找矿",
                    Items.STONE));
            rows.add(biomeSizeRow("medium", "中群系：一片直径约 320 格（推荐）",
                    Items.COBBLESTONE));
            rows.add(biomeSizeRow("large", "大群系：一片直径约 640 格，能逛一会儿",
                    Items.COBBLED_DEEPSLATE));
            rows.add(biomeSizeRow("huge", "超大群系：矿石铺满整片地面，一眼望不到边",
                    Items.OBSIDIAN));
        }
        return rows;
    }

    private static ItemStack iconOf(ResourceLocation id) {
        Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
        return new ItemStack(block == null ? Blocks.AIR : block);
    }

    private Row densityRow(String value, String label, net.minecraft.world.item.Item item) {
        return new Row(new ItemStack(item), label,
                working.density().name().equalsIgnoreCase(value), () -> {
            working.density = value;
            dirty = true;
            status = "已选择「" + label.substring(0, 2) + "」，点应用后生效";
        });
    }

    /** 出生点保护页的一行：点它就换一档保护半径。 */
    private Row spawnRow(int distance, String label) {
        return new Row(new ItemStack(Items.COMPASS), label,
                working.spawnDistance() == distance, () -> {
            working.minSpawnDistance = distance;
            dirty = true;
            status = distance == 0
                    ? "出生点保护已关闭，点应用后生效"
                    : "出生点保护已设为 " + distance + " 格，点应用后生效";
        });
    }

    /** 群系大小页的一行：点它就换一档群系大小。 */
    private Row biomeSizeRow(String value, String label,
            net.minecraft.world.item.Item item) {
        return new Row(new ItemStack(item), label,
                working.biomeSize().key().equals(value), () -> {
            working.biomeSize = value;
            dirty = true;
            status = "群系大小已设为「" + biomeSizeLabel() + "」，退出世界再进才生效";
        });
    }

    private void toggleVanilla(String ore) {
        working.vanillaOres.put(ore, !working.isVanillaOreEnabled(ore));
        dirty = true;
        status = "原版矿石设置已修改，点应用后生效";
    }

    private void toggleMod(String key) {
        working.modOres.put(key, !Boolean.TRUE.equals(working.modOres.get(key)));
        dirty = true;
        status = "模组矿石设置已修改，点应用后生效";
    }

    private void setAll(boolean enabled) {
        if (tab == 1) {
            for (String key : working.modOres.keySet()) {
                working.modOres.put(key, enabled);
            }
        } else {
            for (String ore : OreBiomeSettings.VANILLA_ORES) {
                working.vanillaOres.put(ore, enabled);
            }
        }
        dirty = true;
        status = (enabled ? "已全部开启，" : "已全部关闭，") + "点应用后生效";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        List<Row> rows = currentRows();
        int visible = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        int maxScroll = Math.max(0, rows.size() - visible);
        if (mouseX >= contentX && mouseX < contentX + contentW
                && mouseY >= listTop && mouseY < listBottom) {
            int index = (int) ((mouseY - listTop) / ROW_HEIGHT) + scroll;
            if (index >= 0 && index < rows.size()) {
                rows.get(index).action().run();
                // 整行闪一下，勾选框当场变色，再配一声点击音效
                rowFlashIndex = index;
                rowFlashTicks = PRESS_FRAMES;
                clickSound();
                return true;
            }
        }
        if (rows.size() > visible) {
            int trackX = contentX + contentW + 4;
            if (mouseX >= trackX && mouseX < trackX + 7
                    && mouseY >= listTop && mouseY < listBottom) {
                draggingScroll = true;
                updateScrollFromMouse(mouseY, rows.size(), visible, maxScroll);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll) {
            List<Row> rows = currentRows();
            int visible = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
            updateScrollFromMouse(mouseY, rows.size(), visible,
                    Math.max(0, rows.size() - visible));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScroll = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateScrollFromMouse(double mouseY, int rowCount, int visible, int maxScroll) {
        if (maxScroll <= 0) {
            scroll = 0;
            return;
        }
        int trackH = listBottom - listTop;
        int barH = Math.max(18, trackH * visible / Math.max(1, rowCount));
        int usable = Math.max(1, trackH - barH);
        int target = (int) mouseY - listTop - barH / 2;
        scroll = Math.max(0, Math.min(maxScroll, target * maxScroll / usable));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX >= contentX && mouseX < contentX + contentW
                && mouseY >= listTop && mouseY < listBottom) {
            int visible = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
            int maxScroll = Math.max(0, currentRows().size() - visible);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount) * 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    /** 原版按钮那声「咔」，用来告诉玩家这一下点上了。 */
    private static void clickSound() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private void scanModOres() {
        int added = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || id.getNamespace().equals("minecraft")
                    || id.getNamespace().equals(OreBiomeReborn.MOD_ID)
                    || block == Blocks.AIR || block.asItem() == Items.AIR
                    || !looksLikeOre(block, id)) {
                continue;
            }
            if (!working.modOres.containsKey(id.toString())) {
                working.modOres.put(id.toString(), true);
                added++;
            }
        }
        working.modOres.keySet().removeIf(key -> {
            ResourceLocation id = ResourceLocation.tryParse(key);
            return id == null || BuiltInRegistries.BLOCK.get(id) == Blocks.AIR;
        });
        scroll = 0;
        dirty = true;
        status = added == 0
                ? "扫描完成：没有发现新的模组矿石"
                : "扫描完成：新增 " + added + " 种模组矿石，点应用后生效";
    }

    private static boolean looksLikeOre(Block block, ResourceLocation id) {
        if (ORE_NAME.matcher(id.getPath()).find()) {
            return true;
        }
        return block.builtInRegistryHolder().tags().anyMatch(tag -> {
            String namespace = tag.location().getNamespace();
            String path = tag.location().getPath();
            return (namespace.equals("c") || namespace.equals("forge"))
                    && (path.equals("ores") || path.startsWith("ores/"));
        });
    }

    private void apply() {
        OreBiomeSettings.save(working);
        dirty = false;
        status = "✔ 已应用：新配置只对之后生成的区块生效";
    }

    private void resetDefaults() {
        OreBiomeSettings defaults = OreBiomeSettings.defaults();
        working.vanillaOres = new LinkedHashMap<>(defaults.vanillaOres);
        working.density = defaults.density;
        // 群系大小也要一起回到默认值（中）
        working.biomeSize = defaults.biomeSize;
        // 出生点保护也要一起回到默认值，否则「恢复默认」后保护圈还是原来改过的大小
        working.minSpawnDistance = defaults.minSpawnDistance;
        for (String key : working.modOres.keySet()) {
            working.modOres.put(key, true);
        }
        dirty = true;
        status = "已恢复默认（含出生点保护），点应用后生效";
    }

    private void closeAndApply() {
        OreBiomeSettings.save(working);
        dirty = false;
        this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private enum ButtonStyle {
        TAB, GRAY, WOOD
    }

    /**
     * 自绘按钮：选中状态、可见性、提醒高亮都用 BooleanSupplier 实时取，
     * 切页签不用重建控件；按下时往下沉 2 像素并变暗，配上点击音效。
     */
    private final class FlatButton implements Renderable, GuiEventListener, NarratableEntry {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String label;
        private final ButtonStyle style;
        private final BooleanSupplier selected;
        private final BooleanSupplier visible;
        private final Runnable action;
        private BooleanSupplier accent = () -> false;
        private boolean focused;
        private int pressTicks;

        private FlatButton(int x, int y, int width, int height, String label,
                           ButtonStyle style, BooleanSupplier visible, Runnable action) {
            this(x, y, width, height, label, style, () -> false, visible, action);
        }

        private FlatButton(int x, int y, int width, int height, String label, ButtonStyle style,
                           BooleanSupplier selected, BooleanSupplier visible, Runnable action) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
            this.style = style;
            this.selected = selected;
            this.visible = visible;
            this.action = action;
        }

        private FlatButton withAccent(BooleanSupplier supplier) {
            this.accent = supplier;
            return this;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible.getAsBoolean()) {
                pressTicks = 0;
                return;
            }
            if (pressTicks > 0) {
                pressTicks--;
            }
            boolean hovered = isMouseOver(mouseX, mouseY);
            boolean pressed = pressTicks > 0;
            // 按下时整块面往下沉 2 像素，文字跟着沉，松手后几帧自己弹回来
            int shift = pressed ? 2 : 0;
            int textY = y + (height - 8) / 2 + shift;

            if (style == ButtonStyle.TAB && selected.getAsBoolean()) {
                graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF1A0F08);
                graphics.fill(x, y + shift, x + width, y + height + shift,
                        pressed ? 0xFFD2A860 : (hovered ? 0xFFFFF0C8 : 0xFFF0D8A8));
                graphics.fill(x, y + shift, x + width, y + 2 + shift, 0xFFFFF4D8);
                graphics.fill(x, y + height - 2 + shift, x + width, y + height + shift, 0xFFB48645);
                graphics.renderOutline(x, y + shift, width, height, 0xFF8A5A22);
                centeredText(graphics, label, x + width / 2, textY,
                        pressed ? 0xFF000000 : 0xFF302000);
                drawFocusAndAccent(graphics, hovered);
                return;
            }

            if (style == ButtonStyle.GRAY) {
                graphics.fill(x, y, x + width, y + height, 0xFF1A0F08);
                int face = pressed ? 0xFF4E4E4E : (hovered ? 0xFFA8A8A8 : 0xFF767676);
                graphics.fill(x + 1, y + 1 + shift, x + width - 1, y + height - 1 + shift, face);
                graphics.fill(x + 1, y + 1 + shift, x + width - 1, y + 3 + shift,
                        pressed ? 0xFF3A3A3A : 0xFFC4C4C4);
                graphics.fill(x + 1, y + height - 2 + shift, x + width - 1, y + height - 1 + shift,
                        pressed ? 0xFF9A9A9A : 0xFF4E4E4E);
                centeredText(graphics, label, x + width / 2, textY,
                        pressed ? 0xFFFFFFFF : 0xFF202020);
                drawFocusAndAccent(graphics, hovered);
                return;
            }

            graphics.fill(x, y, x + width, y + height, 0xFF1A0F08);
            tile(graphics, WOOD_TEXTURE, x + 1, y + 1 + shift, width - 2, height - 2);
            if (pressed) {
                graphics.fill(x + 1, y + 1 + shift, x + width - 1, y + height - 1 + shift, 0x66000000);
                graphics.renderOutline(x + 1, y + 1 + shift, width - 2, height - 2, 0xFF000000);
            } else if (hovered) {
                graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x59FFFFFF);
                graphics.renderOutline(x + 1, y + 1, width - 2, height - 2, 0xFFFFE8B0);
            }
            int textX = x + (width - font().width(label)) / 2;
            graphics.drawString(font(), label, textX + 1, textY + 1, 0xFF1A0F08, false);
            graphics.drawString(font(), label, textX, textY,
                    pressed ? 0xFFFFFFFF : (hovered ? 0xFFFFFBE0 : 0xFFFFE8C8), false);
            drawFocusAndAccent(graphics, hovered);
        }

        private net.minecraft.client.gui.Font font() {
            return OreConfigScreen.this.font;
        }

        /** 键盘聚焦画白框，待应用提醒画一圈闪动的黄绿框。 */
        private void drawFocusAndAccent(GuiGraphics graphics, boolean hovered) {
            if (accent.getAsBoolean()) {
                int pulse = (rowFlashTicks > 0 || hovered) ? 0xFFFFF080 : 0xFFC8E060;
                graphics.renderOutline(x - 2, y - 2, width + 4, height + 4, 0xFF1A0F08);
                graphics.renderOutline(x - 2, y - 1, width + 4, height + 2, pulse);
            }
            if (focused) {
                graphics.renderOutline(x - 2, y - 2, width + 4, height + 4, 0xFFFFFFFF);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && visible.getAsBoolean() && isMouseOver(mouseX, mouseY)) {
                pressTicks = PRESS_FRAMES;
                clickSound();
                action.run();
                return true;
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (visible.getAsBoolean() && (keyCode == 257 || keyCode == 32)) {
                pressTicks = PRESS_FRAMES;
                clickSound();
                action.run();
                return true;
            }
            return false;
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return visible.getAsBoolean()
                    && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }

        @Override
        public void setFocused(boolean focused) {
            this.focused = focused;
        }

        @Override
        public boolean isFocused() {
            return focused;
        }

        @Override
        public NarrationPriority narrationPriority() {
            return NarrationPriority.NONE;
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
        }
    }

    /** 供外部（创建世界界面的入口按钮）复用：直接打开这个界面。 */
    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new OreConfigScreen(parent));
    }
}
