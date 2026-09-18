package dev.krister.dungeonprogresshud;

/** Renderer-independent geometry shared by the Minecraft HUD and desktop preview. */
public final class HudGeometry {
    public static final int ROW_HEIGHT = 15;
    public static final int TITLE_HEIGHT = 28;
    public static final int TOP_PADDING = 10;
    public static final int SIDE_PADDING = 13;
    public static final int PROFIT_GAP = 10;
    public static final int BUTTON_WIDTH = 46;
    public static final int BUTTON_MARGIN = 5;
    private HudGeometry() {}

    public record Layout(int width, int dividerY, int height, int separatorX, int valueX) {}

    public static Layout measure(int labelWidth, int valueWidth, int titleWidth, int topRows, int bottomRows, boolean showButton) {
        int separator = SIDE_PADDING + labelWidth + 8;
        int value = separator + 8;
        int button = showButton ? BUTTON_WIDTH + BUTTON_MARGIN * 2 : 0;
        int width = Math.max(value + valueWidth + SIDE_PADDING, titleWidth + SIDE_PADDING * 2 + button);
        int divider = TOP_PADDING + TITLE_HEIGHT + topRows * ROW_HEIGHT + PROFIT_GAP / 2;
        int height = divider + 1 + PROFIT_GAP + bottomRows * ROW_HEIGHT + TOP_PADDING;
        return new Layout(width, divider, height, separator, value);
    }
}
