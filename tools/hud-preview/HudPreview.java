import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public final class HudPreview {
    private static final Path HUD_SOURCE = Paths.get(
        "src", "main", "kotlin", "dev", "krister", "dungeonprogresshud", "DungeonProgressHudAddon.kt"
    );
    private static final Set<String> PROFIT_IDS = Set.of("profit", "chestsOpened", "lastChest", "avgChest");
    private static final Set<String> ACCENT_IDS = Set.of("runsLeft", "profit", "lastChest", "avgChest");
    private static final List<Row> ROWS = List.of(
        new Row("sessionTime", "Session Time", "1m 28s", "", item("", 0)),
        new Row("currentLevel", "Cata Level", "50", "", item("", 0)),
        new Row("target", "Target", "51", "", item("", 0)),
        new Row("levelProgress", "Next Level", "28.9%", "", item("", 0)),
        new Row("runsLeft", "Runs Left", "252", "", item("", 0)),
        new Row("currentXp", "Cata XP", "627.58m", "", item("", 0)),
        new Row("lastRun", "Last Run", "491k", "(22.1m/h)", item("", 0)),
        new Row("observedCount", "Runs", "100", "", item("", 0)),
        new Row("profit", "Profit", "21m", "(session)", item("", 0)),
        new Row("avgChest", "Avg Chest", "7m", "", item("", 0)),
        new Row("chestsOpened", "Chests", "3", "", item("", 0)),
        new Row("croesus", "Croesus", "20", "", item("", 0))
    );

    private HudPreview() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PreviewPanel panel = new PreviewPanel();
            JFrame frame = new JFrame("Dungeon Progress HUD Preview");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            SourceWatcher watcher = new SourceWatcher(panel);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    watcher.close();
                }
            });
            watcher.start();
        });
    }

    private static Item item(String label, int color) {
        return new Item(label, new Color(color));
    }

    private record Row(String id, String label, String value, String suffix, Item item) {
    }

    private record Item(String text, Color color) {
    }

    private static final class Layout {
        int rowHeight = 20;
        int titleHeight = 28;
        int topPadding = 9;
        int sidePadding = 13;
        int profitGap = 13;
        int iconSize = 12;
        int iconX = 6;
        int iconSepX = 23;
        int labelX = 32;
        int minValueSepX = 96;
        int labelGap = 6;
        int valueGap = 7;
        int rightPadding = 7;
        int minContentWidth = 166;
        int cyan = 0xff42f3ff;
        int cyanDim = 0xff147b84;
        int green = 0xff63ff57;
        int white = 0xffffffff;
        int muted = 0xffc8c8c8;
        int line = 0x663dfaff;
        int black = 0xff000000;
        int outerDark = 0xff061014;
        int innerDark = 0xff020405;
        int panel = 0xd00a0f11;
        int profitPanel = 0x88101010;
        int sketchWhite = 0xffe8e8e8;
    }

    private static final class PreviewPanel extends JPanel {
        private final Font font = new Font(Font.MONOSPACED, Font.BOLD, 13);
        private Layout layout = loadLayout();
        private long lastLoadedAt = System.currentTimeMillis();

        PreviewPanel() {
            setPreferredSize(new Dimension(760, 520));
            setBackground(new Color(0x2d3131));
            new Timer(250, ignored -> {
                long modified = modifiedTime();
                if (modified > lastLoadedAt) {
                    reload();
                }
            }).start();
        }

        void reload() {
            layout = loadLayout();
            lastLoadedAt = System.currentTimeMillis();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            drawBackground(g);

            int scale = 2;
            int panelWidth = panelWidth(g, layout);
            int panelHeight = panelHeight(layout);
            int originX = Math.max(24, (getWidth() - panelWidth * scale) / 2);
            int originY = Math.max(24, (getHeight() - panelHeight * scale) / 2);

            g.translate(originX, originY);
            g.scale(scale, scale);
            drawHud(g, layout, panelWidth, panelHeight);
            g.dispose();
        }

        private void drawBackground(Graphics2D g) {
            int tile = 48;
            for (int y = 0; y < getHeight(); y += tile) {
                for (int x = 0; x < getWidth(); x += tile) {
                    int shade = ((x / tile + y / tile) & 1) == 0 ? 0x3b3f40 : 0x313535;
                    g.setColor(new Color(shade));
                    g.fillRect(x, y, tile, tile);
                    g.setColor(new Color(255, 255, 255, 20));
                    g.fillPolygon(new int[] { x, x + tile, x + tile }, new int[] { y, y, y + 12 }, 3);
                }
            }
        }

        private void drawHud(Graphics2D g, Layout l, int width, int height) {
            g.setFont(font);
            int dividerY = dividerY(l);
            drawRoundedPanel(g, l, width, height);
            fill(g, 0, dividerY, width, dividerY + 1, l.sketchWhite);

            String title = "Dungeon Profit Hud";
            drawShadowed(g, title, (width - g.getFontMetrics().stringWidth(title)) / 2, l.topPadding + 10, new Color(l.sketchWhite, true));

            int labelWidth = labelWidth(g);
            int separatorX = l.sidePadding + labelWidth + 8;
            int valueX = separatorX + 8;
            int yOffset = l.topPadding + l.titleHeight;
            int profitStart = profitStart();

            for (int index = 0; index < ROWS.size(); index++) {
                Row row = ROWS.get(index);
                if (index == profitStart) {
                    yOffset = dividerY + 1 + l.profitGap;
                }

                int textY = yOffset + (l.rowHeight - 9) / 2 + 8;
                drawShadowed(g, row.label(), l.sidePadding, textY, new Color(l.sketchWhite, true));
                drawShadowed(g, "|", separatorX, textY, new Color(l.sketchWhite, true));
                drawShadowed(g, row.value(), valueX, textY, new Color(l.sketchWhite, true));
                if (!row.suffix().isBlank()) {
                    drawShadowed(g, row.suffix(), valueX + g.getFontMetrics().stringWidth(row.value()) + 10, textY, new Color(l.sketchWhite, true));
                }

                yOffset += l.rowHeight;
            }
        }

        private void drawRoundedPanel(Graphics2D g, Layout l, int width, int height) {
            roundedFill(g, 0, 0, width, height, l.profitPanel);
            roundedBorder(g, 0, 0, width, height, l.sketchWhite);
        }

        private void roundedFill(Graphics2D g, int x, int y, int width, int height, int color) {
            fill(g, x + 4, y, x + width - 4, y + 1, color);
            fill(g, x + 2, y + 1, x + width - 2, y + 2, color);
            fill(g, x + 1, y + 2, x + width - 1, y + 4, color);
            fill(g, x, y + 4, x + width, y + height - 4, color);
            fill(g, x + 1, y + height - 4, x + width - 1, y + height - 2, color);
            fill(g, x + 2, y + height - 2, x + width - 2, y + height - 1, color);
            fill(g, x + 4, y + height - 1, x + width - 4, y + height, color);
        }

        private void roundedBorder(Graphics2D g, int x, int y, int width, int height, int color) {
            fill(g, x + 4, y, x + width - 4, y + 1, color);
            fill(g, x + 2, y + 1, x + 4, y + 2, color);
            fill(g, x + width - 4, y + 1, x + width - 2, y + 2, color);
            fill(g, x + 1, y + 2, x + 2, y + 4, color);
            fill(g, x + width - 2, y + 2, x + width - 1, y + 4, color);
            fill(g, x, y + 4, x + 1, y + height - 4, color);
            fill(g, x + width - 1, y + 4, x + width, y + height - 4, color);
            fill(g, x + 1, y + height - 4, x + 2, y + height - 2, color);
            fill(g, x + width - 2, y + height - 4, x + width - 1, y + height - 2, color);
            fill(g, x + 2, y + height - 2, x + 4, y + height - 1, color);
            fill(g, x + width - 4, y + height - 2, x + width - 2, y + height - 1, color);
            fill(g, x + 4, y + height - 1, x + width - 4, y + height, color);
        }

        private void drawItem(Graphics2D g, Item item, int x, int y, int size) {
            g.setColor(Color.BLACK);
            g.fillRect(x + 1, y + 1, size, size);
            g.setColor(item.color());
            g.fillRect(x, y, size, size);
            g.setColor(new Color(255, 255, 255, 100));
            g.fillRect(x + 2, y + 2, Math.max(3, size / 3), Math.max(2, size / 5));
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect(x + size - 5, y + size - 5, 3, 3);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 7));
            String text = item.text().substring(0, 1).toUpperCase(Locale.ROOT);
            g.drawString(text, x + Math.max(3, size / 3), y + Math.max(8, size - 3));
            g.setFont(font);
        }

        private void drawShadowed(Graphics2D g, String text, int x, int baseline, Color color) {
            g.setColor(Color.BLACK);
            g.drawString(text, x + 1, baseline + 1);
            g.drawString(text, x + 2, baseline + 2);
            g.setColor(color);
            g.drawString(text, x, baseline);
        }

        private int panelWidth(Graphics2D g, Layout l) {
            g.setFont(font);
            int labelWidth = labelWidth(g);
            int separatorX = l.sidePadding + labelWidth + 8;
            int valueX = separatorX + 8;
            int valueWidth = ROWS.stream()
                .mapToInt(row -> g.getFontMetrics().stringWidth(row.value()) + (row.suffix().isBlank() ? 0 : 10 + g.getFontMetrics().stringWidth(row.suffix())))
                .max()
                .orElse(72);
            return Math.max(valueX + valueWidth + l.sidePadding, g.getFontMetrics().stringWidth("Dungeon Profit Hud") + l.sidePadding * 2 + 10);
        }

        private int panelHeight(Layout l) {
            int profitRows = ROWS.size() - profitStart();
            return dividerY(l) + 1 + l.profitGap + profitRows * l.rowHeight + l.topPadding;
        }

        private int labelWidth(Graphics2D g) {
            return Math.max(ROWS.stream().mapToInt(row -> g.getFontMetrics().stringWidth(row.label())).max().orElse(88), 88);
        }

        private int profitStart() {
            for (int i = 0; i < ROWS.size(); i++) {
                if (PROFIT_IDS.contains(ROWS.get(i).id())) return i;
            }
            return -1;
        }

        private int dividerY(Layout l) {
            int profitStart = profitStart();
            return l.topPadding + l.titleHeight + profitStart * l.rowHeight + l.profitGap / 2;
        }

        private void drawDashedVertical(Graphics2D g, int x, int top, int bottom, int color) {
            g.setColor(new Color(color, true));
            int y = top;
            while (y < bottom) {
                g.fillRect(x, y, 1, Math.min(5, bottom - y));
                y += 8;
            }
        }

        private void fill(Graphics2D g, int x1, int y1, int x2, int y2, int argb) {
            g.setColor(new Color(argb, true));
            g.fillRect(x1, y1, x2 - x1, y2 - y1);
        }
    }

    private static final class SourceWatcher {
        private final PreviewPanel panel;
        private volatile boolean running = true;
        private WatchService watchService;

        SourceWatcher(PreviewPanel panel) {
            this.panel = panel;
        }

        void start() {
            Thread thread = new Thread(this::watch, "hud-preview-watch");
            thread.setDaemon(true);
            thread.start();
        }

        void close() {
            running = false;
            try {
                if (watchService != null) watchService.close();
            } catch (IOException ignored) {
            }
        }

        private void watch() {
            try {
                watchService = FileSystems.getDefault().newWatchService();
                HUD_SOURCE.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                while (running) {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (HUD_SOURCE.getFileName().equals(event.context())) {
                            SwingUtilities.invokeLater(panel::reload);
                        }
                    }
                    key.reset();
                }
            } catch (IOException | InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Layout loadLayout() {
        Layout layout = new Layout();
        try {
            String source = Files.readString(HUD_SOURCE, StandardCharsets.UTF_8);
            layout.rowHeight = intValue(source, "HUD_ROW_HEIGHT\\s*=\\s*(\\d+)", layout.rowHeight);
            layout.titleHeight = intValue(source, "HUD_TITLE_HEIGHT\\s*=\\s*(\\d+)", layout.titleHeight);
            layout.topPadding = intValue(source, "HUD_TOP_PADDING\\s*=\\s*(\\d+)", layout.topPadding);
            layout.sidePadding = intValue(source, "HUD_SIDE_PADDING\\s*=\\s*(\\d+)", layout.sidePadding);
            layout.profitGap = intValue(source, "HUD_PROFIT_GAP\\s*=\\s*(\\d+)", layout.profitGap);
            layout.iconSize = intValue(source, "HUD_ICON_SIZE\\s*=\\s*(\\d+)", layout.iconSize);
            layout.iconX = intValue(source, "val\\s+iconX\\s*=\\s*(\\d+)", layout.iconX);
            layout.iconSepX = intValue(source, "val\\s+iconSepX\\s*=\\s*(\\d+)", layout.iconSepX);
            layout.labelX = intValue(source, "val\\s+labelX\\s*=\\s*(\\d+)", layout.labelX);
            layout.minValueSepX = intValue(source, "val\\s+valueSepX\\s*=\\s*max\\((\\d+),", layout.minValueSepX);
            layout.labelGap = intValue(source, "valueSepX\\s*=\\s*max\\(\\d+,\\s*labelX\\s*\\+\\s*labelWidth\\s*\\+\\s*(\\d+)\\)", layout.labelGap);
            layout.valueGap = intValue(source, "val\\s+valueX\\s*=\\s*valueSepX\\s*\\+\\s*(\\d+)", layout.valueGap);
            layout.rightPadding = intValue(source, "return\\s+max\\(valueX\\s*\\+\\s*valueWidth\\s*\\+\\s*(\\d+),", layout.rightPadding);
            layout.minContentWidth = intValue(source, "return\\s+max\\(valueX\\s*\\+\\s*valueWidth\\s*\\+\\s*\\d+,\\s*(\\d+)\\)", layout.minContentWidth);
            layout.cyan = colorValue(source, "HUD_CYAN", layout.cyan);
            layout.cyanDim = colorValue(source, "HUD_CYAN_DIM", layout.cyanDim);
            layout.green = colorValue(source, "HUD_GREEN", layout.green);
            layout.white = colorValue(source, "HUD_WHITE", layout.white);
            layout.muted = colorValue(source, "HUD_MUTED", layout.muted);
            layout.line = colorValue(source, "HUD_LINE", layout.line);
            layout.black = colorValue(source, "HUD_BLACK", layout.black);
            layout.outerDark = colorValue(source, "HUD_OUTER_DARK", layout.outerDark);
            layout.innerDark = colorValue(source, "HUD_INNER_DARK", layout.innerDark);
            layout.panel = colorValue(source, "HUD_PANEL", layout.panel);
            layout.profitPanel = colorValue(source, "HUD_PROFIT_PANEL", layout.profitPanel);
            layout.sketchWhite = colorValue(source, "HUD_SKETCH_WHITE", layout.sketchWhite);
        } catch (IOException ignored) {
        }
        return layout;
    }

    private static int intValue(String source, String regex, int fallback) {
        Matcher matcher = Pattern.compile(regex).matcher(source);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static int colorValue(String source, String name, int fallback) {
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*0x([0-9a-fA-F]{8})").matcher(source);
        return matcher.find() ? (int) Long.parseLong(matcher.group(1), 16) : fallback;
    }

    private static long modifiedTime() {
        try {
            return Files.getLastModifiedTime(HUD_SOURCE).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
