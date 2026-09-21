package dev.krister.dungeonprogresshud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared layout and hit areas for gameplay, HUD editing, and the desktop preview. */
public final class HudGeometry {
    public static final int ROW_HEIGHT = 12;
    public static final int TITLE_HEIGHT = 22;
    public static final int TOP_PADDING = 10;
    public static final int SIDE_PADDING = 10;
    public static final int SECTION_HEIGHT = 12;
    public static final int LEVEL_HEIGHT = 32;
    public static final int PROGRESS_HEIGHT = 24;
    public static final int BUTTON_WIDTH = 34;
    public static final int BUTTON_HEIGHT = 12;
    public static final float TITLE_SCALE = 1.5f;
    public static final float FLOOR_SCALE = 1.25f;
    public static final int ACCENT = 0xFF6ECAFD;
    public static final int PROFIT = 0xFF6FF4C6;
    public static final int WHITE = 0xFFF5F7FA;
    public static final int MUTED = 0xFFB8BDC7;
    public static final int LINE = 0xFF353F4B;
    public static final int PANEL = 0xDD0D1216;
    public static final List<String> DEFAULT_ORDER = List.of(
        "sessionTime", "observedCount", "lastRun", "currentLevel", "target", "levelProgress",
        "classProgress",
        "currentXp", "remaining", "runsLeft", "floor", "xpPerRun", "xpPerHour", "profile",
        "profit", "avgChest", "chestsOpened", "kismets", "croesus", "lastChest");
    private static final List<String> LEGACY_ORDER = List.of(
        "sessionTime", "currentLevel", "target", "levelProgress", "runsLeft", "currentXp",
        "remaining", "floor", "xpPerRun", "profile", "lastRun", "observedCount", "profit",
        "avgChest", "chestsOpened", "kismets", "croesus", "lastChest");

    private HudGeometry() {}

    public record Row(String id, int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
    }

    public record Section(String title, int y, String scope) {}
    public record Layout(int width, int height, List<Section> sections, List<Integer> dividers, List<Row> rows) {
        public int right() { return width - SIDE_PADDING; }
    }

    public static boolean isLevel(String id) {
        return id.equals("currentLevel") || id.equals("target");
    }

    public static String section(String id) {
        return switch (id) {
            case "sessionTime", "observedCount", "lastRun", "historyError" -> "SESSION";
            case "profit", "avgChest", "chestsOpened", "kismets", "croesus", "lastChest", "pendingPrices" -> "PROFIT";
            default -> "CATACOMBS";
        };
    }

    public static float valueScale(String id) {
        return isLevel(id) ? 2f : id.equals("profit") ? 1.25f : 1f;
    }

    /** Base drop rarity colors, shared by the HUD and its previews. */
    public static int labelColor(String id) {
        return switch (id) {
            case "itemHandle", "itemRecomb", "itemAutoRecomb", "itemClaymore", "itemChestplate", "itemNecronDye" -> 0xFFFFAA00; // Legendary
            case "itemImplosion", "itemWitherShield", "itemShadowWarp", "itemFifthStar" -> 0xFFAA00AA; // Epic
            case "itemSkullT5" -> 0xFF5555FF; // Rare
            default -> MUTED;
        };
    }

    public static int progressWidth(int width, double percent) {
        return Double.isFinite(percent) ? (int) Math.round(width * Math.clamp(percent / 100.0, 0.0, 1.0)) : 0;
    }

    public static List<String> normalizeOrder(List<String> saved) {
        if (saved.isEmpty() || saved.equals(LEGACY_ORDER)) return DEFAULT_ORDER;
        var result = new ArrayList<>(saved.stream().filter(DEFAULT_ORDER::contains).distinct().toList());
        for (String id : DEFAULT_ORDER) if (!result.contains(id)) {
            if (id.equals("classProgress") && result.contains("levelProgress")) result.add(result.indexOf("levelProgress") + 1, id);
            else result.add(id);
        }
        return List.copyOf(result);
    }

    public static List<String> move(List<String> order, String dragged, String target) {
        if (!order.contains(dragged) || !order.contains(target) || dragged.equals(target)
            || !section(dragged).equals(section(target))) return order;
        boolean down = order.indexOf(dragged) < order.indexOf(target);
        List<String> moving = isLevel(dragged) && !isLevel(target)
            ? order.stream().filter(HudGeometry::isLevel).toList() : List.of(dragged);
        if (isLevel(target) && !isLevel(dragged)) {
            List<String> levels = order.stream().filter(HudGeometry::isLevel).toList();
            target = down ? levels.getLast() : levels.getFirst();
        }
        var result = new ArrayList<>(order);
        result.removeAll(moving);
        result.addAll(result.indexOf(target) + (down ? 1 : 0), moving);
        return List.copyOf(result);
    }

    public static Layout measure(int labelWidth, int valueWidth, int titleWidth, int floorWidth,
                                 List<String> topRows, List<String> bottomRows, boolean items) {
        return measure(labelWidth, valueWidth, titleWidth, floorWidth, topRows, bottomRows, items, "session", false);
    }

    public static Layout measure(int labelWidth, int valueWidth, int titleWidth, int floorWidth,
                                 List<String> topRows, List<String> bottomRows, boolean items, String scope, boolean allClasses) {
        int contentWidth = Math.max(210, Math.max(labelWidth + 20 + valueWidth,
            titleWidth + 24 + floorWidth));
        int width = contentWidth + SIDE_PADDING * 2;
        var sections = new ArrayList<Section>();
        var dividers = new ArrayList<Integer>();
        var rows = new ArrayList<Row>();
        List<List<String>> groups = items ? List.of(topRows, bottomRows) : List.of(
            topRows.stream().filter(id -> section(id).equals("SESSION")).toList(),
            topRows.stream().filter(id -> !section(id).equals("SESSION") && !id.equals("floor")).toList(),
            bottomRows);
        List<String> headings = items ? List.of("PROFIT", "ITEM DROPS") : List.of("SESSION", "CATACOMBS", "PROFIT");
        int y = TOP_PADDING + TITLE_HEIGHT;
        for (int i = 0; i < groups.size(); i++) {
            List<String> ids = groups.get(i);
            if (ids.isEmpty()) continue;
            if (!sections.isEmpty()) {
                dividers.add(y + 6);
                y += 14;
            }
            String heading = headings.get(i);
            sections.add(new Section(heading.equals("SESSION") ? scope.toUpperCase(Locale.ROOT) : heading, y,
                heading.equals("SESSION") || heading.equals("CATACOMBS")
                    || (heading.equals("PROFIT") && scope.equals("session")) ? "" : scope.toUpperCase(Locale.ROOT)));
            y += SECTION_HEIGHT;
            boolean levelsPlaced = false;
            for (String id : ids) {
                if (isLevel(id)) {
                    if (levelsPlaced) continue;
                    List<String> levels = ids.stream().filter(HudGeometry::isLevel).toList();
                    for (int col = 0; col < levels.size(); col++) {
                        int cellWidth = levels.size() == 2 ? (contentWidth - 20) / 2 : contentWidth;
                        int cellX = col == 0 ? SIDE_PADDING : width - SIDE_PADDING - cellWidth;
                        rows.add(new Row(levels.get(col), cellX, y, cellWidth, LEVEL_HEIGHT));
                    }
                    levelsPlaced = true;
                    y += LEVEL_HEIGHT;
                } else {
                    int height = switch (id) {
                        case "levelProgress" -> PROGRESS_HEIGHT;
                        case "classProgress" -> 4 + (allClasses ? SECTION_HEIGHT + 5 * ROW_HEIGHT : LEVEL_HEIGHT + PROGRESS_HEIGHT);
                        case "profit" -> 16;
                        default -> ROW_HEIGHT;
                    };
                    rows.add(new Row(id, SIDE_PADDING, y, contentWidth, height));
                    y += height;
                }
            }
        }
        return new Layout(width, y + TOP_PADDING, List.copyOf(sections), List.copyOf(dividers), List.copyOf(rows));
    }
}
