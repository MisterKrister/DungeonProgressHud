import dev.krister.dungeonprogresshud.HudGeometry;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Uses the game's ASCII font and the production layout, including inventory drag targets. */
public final class HudPreview {
    private static final List<Row> TOP = List.of(
        new Row("sessionTime", "Session time", "10m 30s"),
        new Row("observedCount", "Runs", "90"),
        new Row("lastRun", "Last run", "N/A"),
        new Row("currentLevel", "Cata level", "52"),
        new Row("target", "Target", "53"),
        new Row("levelProgress", "Next level", "12.0%"),
        new Row("classProgress", "Classes", "Next level"),
        new Row("currentXp", "Cata XP", "993.84m"),
        new Row("remaining", "Remaining XP", "175.97m"),
        new Row("runsLeft", "Runs left", "876"),
        new Row("xpPerRun", "XP/run", "201k"),
        new Row("xpPerHour", "XP/h (session)", "378k")
    );
    private static final List<Row> BOTTOM = List.of(
        new Row("profit", "Profit", "518.60m"),
        new Row("avgChest", "Avg chest", "2.07m"),
        new Row("kismets", "Kismets", "304"),
        new Row("croesus", "Croesus", "1")
    );
    private static final List<Row> ITEMS = List.of(
        new Row("itemHandle", "Handle", "1"),
        new Row("itemImplosion", "Implosion", "2"),
        new Row("itemWitherShield", "Wither Shield", "1"),
        new Row("itemShadowWarp", "Shadow Warp", "1"),
        new Row("itemRecomb", "Recomb", "17"),
        new Row("itemAutoRecomb", "Auto Recomb", "2"),
        new Row("itemClaymore", "Claymore", "0"),
        new Row("itemFifthStar", "5th Star", "3"),
        new Row("itemChestplate", "Chestplate", "1"),
        new Row("itemSkullT5", "Skull T5", "0"),
        new Row("itemNecronDye", "Necron Dye", "2")
    );
    private record Row(String id, String label, String value) {}
    private static final BufferedImage FONT = loadFont();
    private static boolean level50;
    private static boolean runs;

    public static void main(String[] args) throws IOException {
        level50 = List.of(args).contains("--level-50");
        runs = List.of(args).contains("--runs");
        PreviewPanel panel = new PreviewPanel();
        if (args.length >= 2 && args[0].equals("--screenshot")) {
            panel.setSize(panel.getPreferredSize());
            BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            panel.paint(graphics);
            graphics.dispose();
            ImageIO.write(image, "png", Path.of(args[1]).toFile());
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Dungeon HUD - current class, all classes, items");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static HudGeometry.Layout measurePreview(List<Row> top, List<Row> bottom, boolean items) {
        return measurePreview(top, bottom, items, false, "session");
    }

    private static HudGeometry.Layout measurePreview(List<Row> top, List<Row> bottom, boolean items, boolean allClasses, String scope) {
        List<Row> all = new ArrayList<>(TOP);
        all.addAll(BOTTOM);
        all.addAll(ITEMS);
        int labels = all.stream().mapToInt(row -> textWidth(row.label(), false)).max().orElse(0);
        int values = all.stream().mapToInt(row -> (int) Math.ceil(textWidth(row.value(),
            HudGeometry.isLevel(row.id()) || row.id().equals("profit")) * HudGeometry.valueScale(row.id()))).max().orElse(0);
        int title = (int) Math.ceil(Math.max(textWidth("Dungeon Profit", true), textWidth("Dungeon Items", true)) * HudGeometry.TITLE_SCALE);
        return HudGeometry.measure(labels, values, title, (int) Math.ceil(textWidth("F5", true) * HudGeometry.FLOOR_SCALE),
            top.stream().map(Row::id).toList(), bottom.stream().map(Row::id).toList(), items, scope, allClasses);
    }

    private static final class PreviewPanel extends JPanel {
        private static final int SCALE = 2;
        private static final int GAP = 18;
        private List<String> order = new ArrayList<>();
        private String dragged;
        private String hovered;
        private boolean editorItems;

        PreviewPanel() {
            TOP.forEach(row -> order.add(row.id()));
            BOTTOM.forEach(row -> order.add(row.id()));
            HudGeometry.Layout profit = measurePreview(TOP, BOTTOM, false);
            HudGeometry.Layout items = measurePreview(BOTTOM, ITEMS, true);
            setPreferredSize(new Dimension((profit.width() * 3 + GAP * 4) * SCALE,
                (Math.max(editorLayout().height(), items.height()) + 36) * SCALE));
            setBackground(new Color(0x151B21));
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    double x = event.getX() / (double) SCALE - editorX();
                    double y = event.getY() / (double) SCALE - 24;
                    HudGeometry.Layout current = editorLayout();
                    int buttonY = current.sections().getLast().y() - 2;
                    if (x >= current.right() - HudGeometry.BUTTON_WIDTH && x < current.right()
                        && y >= buttonY && y < buttonY + HudGeometry.BUTTON_HEIGHT) {
                        editorItems = !editorItems;
                        repaint();
                    } else if (event.isShiftDown() && !editorItems) {
                        dragged = hit(event);
                    }
                }
                @Override public void mouseDragged(MouseEvent event) {
                    hovered = hit(event);
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent event) {
                    String target = hit(event);
                    if (dragged != null && target != null) order = HudGeometry.move(order, dragged, target);
                    dragged = null;
                    hovered = null;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private List<Row> ordered(List<Row> rows) {
            return order.stream().flatMap(id -> rows.stream().filter(row -> row.id().equals(id))).toList();
        }
        private int editorX() { return measurePreview(TOP, BOTTOM, false).width() + GAP * 2; }
        private HudGeometry.Layout editorLayout() {
            return editorItems ? measurePreview(BOTTOM, ITEMS, true, false, "last 4 days")
                : measurePreview(ordered(TOP), ordered(BOTTOM), false, true, "last 4 days");
        }
        private String hit(MouseEvent event) {
            if (editorItems) return null;
            double x = event.getX() / (double) SCALE - editorX();
            double y = event.getY() / (double) SCALE - 24;
            return editorLayout().rows().stream().filter(row -> row.contains(x, y)).map(HudGeometry.Row::id).findFirst().orElse(null);
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.scale(SCALE, SCALE);
            int width = measurePreview(TOP, BOTTOM, false).width();
            text(g, "CURRENT CLASS", GAP, 8, HudGeometry.MUTED, 1, false, false);
            text(g, "ALL CLASSES: SHIFT + DRAG", editorX(), 8, HudGeometry.ACCENT, 1, false, false);
            text(g, "ITEM TRACKER", GAP * 3 + width * 2, 8, HudGeometry.MUTED, 1, false, false);
            g.translate(GAP, 24);
            drawHud(g, TOP, BOTTOM, false, false, false, "session");
            g.translate(width + GAP, 0);
            drawHud(g, editorItems ? BOTTOM : ordered(TOP), editorItems ? ITEMS : ordered(BOTTOM), editorItems, true, true, "last 4 days");
            if (!editorItems) for (HudGeometry.Row box : editorLayout().rows()) {
                if (box.id().equals(dragged) || box.id().equals(hovered)) {
                    fill(g, box.x(), box.y(), box.width(), box.height(), box.id().equals(dragged) ? 0x556ECAFD : 0x556FF4C6);
                }
            }
            g.translate(width + GAP, 0);
            drawHud(g, BOTTOM, ITEMS, true, false, false, "session");
            g.dispose();
        }
    }

    private static void drawHud(Graphics2D g, List<Row> top, List<Row> bottom, boolean items, boolean inventory, boolean allClasses, String scope) {
        HudGeometry.Layout layout = measurePreview(top, bottom, items, allClasses, scope);
        fill(g, 0, 0, layout.width(), layout.height(), HudGeometry.PANEL);
        int left = HudGeometry.SIDE_PADDING;
        int topY = HudGeometry.TOP_PADDING;
        fill(g, left, topY, 3, 13, HudGeometry.ACCENT);
        text(g, items ? "Dungeon Items" : "Dungeon Profit", left + 9, topY, HudGeometry.WHITE, HudGeometry.TITLE_SCALE, false, true);
        text(g, "F5", layout.right(), topY + 2, HudGeometry.ACCENT, HudGeometry.FLOOR_SCALE, true, true);
        for (int y : layout.dividers()) fill(g, left, y, layout.right() - left, 1, HudGeometry.LINE);
        for (HudGeometry.Section section : layout.sections()) {
            text(g, section.title(), left, section.y(), HudGeometry.ACCENT, 1, false, false);
            int scopeRight = layout.right() - (inventory && section.equals(layout.sections().getLast()) ? HudGeometry.BUTTON_WIDTH + 6 : 0);
            text(g, section.scope(), scopeRight, section.y(), HudGeometry.ACCENT, 1, true, false);
        }
        List<Row> rows = new ArrayList<>(top);
        rows.addAll(bottom);
        for (HudGeometry.Row box : layout.rows()) {
            Row row = rows.stream().filter(r -> r.id().equals(box.id())).findFirst().orElseThrow();
            if (row.id().equals("classProgress")) {
                drawClasses(g, box, allClasses);
            } else if (HudGeometry.isLevel(row.id())) {
                boolean right = box.x() > left;
                int x = right ? box.right() : box.x();
                text(g, row.label(), x, box.y(), HudGeometry.MUTED, 1, right, false);
                text(g, row.value(), x, box.y() + 12, HudGeometry.WHITE, HudGeometry.valueScale(row.id()), right, true);
            } else {
                boolean profit = row.id().equals("profit");
                String label = !scope.equals("session") && row.id().equals("sessionTime") ? "Run time"
                    : !scope.equals("session") && row.id().equals("xpPerHour") ? "XP/h (runs)" : row.label();
                text(g, label, box.x(), box.y() + (profit ? 4 : 2), HudGeometry.labelColor(row.id()), 1, false, false);
                text(g, row.value(), box.right(), box.y() + 2, profit ? HudGeometry.PROFIT : HudGeometry.WHITE,
                    HudGeometry.valueScale(row.id()), true, profit);
                if (row.id().equals("levelProgress")) {
                    fill(g, box.x(), box.y() + 14, box.width(), 7, HudGeometry.LINE);
                    fill(g, box.x(), box.y() + 14, HudGeometry.progressWidth(box.width(), 12.0), 7, HudGeometry.ACCENT);
                }
            }
        }
        List<HudGeometry.Row> levels = layout.rows().stream().filter(row -> HudGeometry.isLevel(row.id())).toList();
        if (levels.size() == 2) text(g, levels.getFirst().id().equals("currentLevel") ? ">" : "<",
            layout.width() / 2 - 5, levels.getFirst().y() + 12, HudGeometry.ACCENT, 2, false, false);
        if (inventory) {
            String label = items ? "Profit" : "Items";
            int x = layout.right() - HudGeometry.BUTTON_WIDTH;
            int y = layout.sections().getLast().y() - 2;
            fill(g, x, y, HudGeometry.BUTTON_WIDTH, HudGeometry.BUTTON_HEIGHT, HudGeometry.LINE);
            text(g, label, x + (HudGeometry.BUTTON_WIDTH - textWidth(label, false)) / 2, y + 2, HudGeometry.ACCENT, 1, false, false);
        }
    }

    private static void drawClasses(Graphics2D g, HudGeometry.Row box, boolean all) {
        int y = box.y() + 4;
        if (!all) {
            text(g, "Mage level", box.x(), y, HudGeometry.MUTED, 1, false, false);
            text(g, "Target", box.right(), y, HudGeometry.MUTED, 1, true, false);
            text(g, "50", box.x(), y + 12, HudGeometry.WHITE, 2, false, true);
            text(g, "51", box.right(), y + 12, HudGeometry.WHITE, 2, true, true);
            text(g, ">", box.x() + box.width() / 2 - 5, y + 12, HudGeometry.ACCENT, 2, false, false);
            text(g, "Target progress", box.x(), y + HudGeometry.LEVEL_HEIGHT + 2, HudGeometry.MUTED, 1, false, false);
            text(g, "20.2%", box.right(), y + HudGeometry.LEVEL_HEIGHT + 2, HudGeometry.WHITE, 1, true, false);
            progress(g, box.x(), y + HudGeometry.LEVEL_HEIGHT + 14, box.width(), 20.2);
        } else {
            text(g, "CLASSES", box.x(), y, HudGeometry.ACCENT, 1, false, false);
            text(g, runs ? (level50 ? "Runs to 50" : "Runs to next") : (level50 ? "Level 50" : "Next level"), box.right(), y, HudGeometry.MUTED, 1, true, false);
            List<String> labels = List.of("Healer 35", "Mage 50", "Berserk 41", "Archer 38", "Tank 50");
            double[] values = level50 ? new double[]{2.5, 100.0, 14.0, 5.9, 100.0} : new double[]{22.0, 20.2, 68.0, 35.0, 12.0};
            List<String> amounts = runs ? (level50 ? List.of("1,234", "0", "12,345", "123,456", "0")
                : List.of("8", "1,234", "99", "12,345", "1,000"))
                : java.util.Arrays.stream(values).mapToObj(value -> String.format(java.util.Locale.US, "%.1f%%", value)).toList();
            int barX = box.x() + labels.stream().mapToInt(label -> textWidth(label, false)).max().orElse(0) + 6;
            int valueWidth = Math.max(textWidth("100.0%", false), amounts.stream().mapToInt(value -> textWidth(value, false)).max().orElse(0));
            int barRight = box.right() - valueWidth - 6;
            if (barRight - barX < 40) throw new IllegalStateException("Class values leave too little room for the bars");
            for (int i = 0; i < labels.size(); i++) {
                int lineY = y + HudGeometry.SECTION_HEIGHT + i * HudGeometry.ROW_HEIGHT + 2;
                text(g, labels.get(i), box.x(), lineY, HudGeometry.MUTED, 1, false, false);
                progress(g, barX, lineY, barRight - barX, values[i]);
                text(g, amounts.get(i), box.right(), lineY, HudGeometry.WHITE, 1, true, false);
            }
        }
    }

    private static void progress(Graphics2D g, int x, int y, int width, double percent) {
        fill(g, x, y, width, 7, HudGeometry.LINE);
        fill(g, x, y, HudGeometry.progressWidth(width, percent), 7, HudGeometry.ACCENT);
    }

    private static void fill(Graphics2D g, int x, int y, int width, int height, int color) {
        g.setColor(new Color(color, true));
        g.fillRect(x, y, width, height);
    }
    private static int glyphWidth(char c) {
        if (c == ' ') return 3;
        for (int x = 7; x >= 0; x--) for (int y = 0; y < 8; y++) {
            if ((FONT.getRGB(c % 16 * 8 + x, c / 16 * 8 + y) >>> 24) != 0) return x + 1;
        }
        return 0;
    }
    private static int textWidth(String text, boolean bold) {
        int width = 0;
        for (char c : text.toCharArray()) width += glyphWidth(c) + (bold ? 2 : 1);
        return width;
    }
    private static void text(Graphics2D graphics, String text, int x, int y, int color, float scale, boolean right, boolean bold) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(right ? x - textWidth(text, bold) * scale : x, y);
        g.scale(scale, scale);
        for (int pass = 1; pass >= 0; pass--) {
            g.setColor(new Color(pass == 1 ? 0xFF000000 | ((color & 0xFCFCFC) >> 2) : color, true));
            int cursor = pass;
            for (char c : text.toCharArray()) {
                if (c != ' ') for (int gy = 0; gy < 8; gy++) for (int gx = 0; gx < 8; gx++) {
                    if ((FONT.getRGB(c % 16 * 8 + gx, c / 16 * 8 + gy) >>> 24) != 0) {
                        g.fillRect(cursor + gx, gy + pass, bold ? 2 : 1, 1);
                    }
                }
                cursor += glyphWidth(c) + (bold ? 2 : 1);
            }
        }
        g.dispose();
    }
    private static BufferedImage loadFont() {
        Path cache = Path.of(".gradle", "loom-cache", "minecraftMaven");
        try (var files = Files.walk(cache)) {
            for (Path jar : files.filter(p -> p.toString().endsWith("26.1.2.jar")).toList()) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    var entry = zip.getEntry("assets/minecraft/textures/font/ascii.png");
                    if (entry != null) try (var stream = zip.getInputStream(entry)) { return ImageIO.read(stream); }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Build the mod first to make Minecraft's font available.", failure);
        }
        throw new IllegalStateException("Minecraft font missing; build the mod before opening the preview.");
    }
}
