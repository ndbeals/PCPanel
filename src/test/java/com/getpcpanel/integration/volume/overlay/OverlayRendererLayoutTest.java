package com.getpcpanel.integration.volume.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.getpcpanel.profile.Save;

/**
 * A name of several lines stacks them in the top row, growing the overlay by a row per line past the first while the
 * height it is positioned by stays that of one line; the background's gradient is a choice.
 */
// See OverlayRendererBarTest: drawing into an image on macOS drags AWT's desktop integration into the native metadata.
@DisabledOnOs(value = OS.MAC, disabledReason = "the overlay is drawn on Windows only")
class OverlayRendererLayoutTest {
    private static final int TEXT_SIZE = 14;
    private static final int LINE = TEXT_SIZE + 4;

    private static OverlayRenderer renderer(Save save, String name) {
        var renderer = new OverlayRenderer();
        renderer.setStyles(save);
        renderer.setName(name);
        return renderer;
    }

    private static Save textOnly() {
        var save = new Save();
        save.setOverlayShowIcon(false);
        save.setOverlayShowAppName(true);
        save.setOverlayTextSize(TEXT_SIZE);
        return save;
    }

    @Test
    void eachLinePastTheFirstAddsARow() {
        var save = textOnly();
        var one = renderer(save, "Speakers").computeHeight();
        assertEquals(one + LINE, renderer(save, "Speakers\nYeti").computeHeight());
        assertEquals(one + 2 * LINE, renderer(save, "Speakers\nYeti\nHeadset").computeHeight());
    }

    @Test
    void blankLinesTakeNoRow() {
        var save = textOnly();
        assertEquals(renderer(save, "Speakers\nYeti").computeHeight(), renderer(save, "Speakers\n\n  \nYeti\n").computeHeight());
    }

    @Test
    void theHeightItIsPositionedByIsThatOfOneLine() {
        var save = textOnly();
        var multi = renderer(save, "Speakers\nYeti\nHeadset");
        assertEquals(renderer(save, "Speakers").computeHeight(), multi.baseHeight());
        assertEquals(multi.baseHeight(), new OverlayRenderer().setStyles(save));
    }

    @Test
    void linesFitBesideATallerIconWithoutGrowing() {
        var save = textOnly();
        save.setOverlayShowIcon(true);
        save.setOverlayIconSize(2 * LINE);
        assertEquals(renderer(save, "Speakers").computeHeight(), renderer(save, "Speakers\nYeti").computeHeight());
    }

    private static int topPixel(boolean gradient) {
        var save = textOnly();
        save.setOverlayBackgroundColor("rgb(0, 0, 0)");
        save.setOverlayWindowCornerRounding(0);
        save.setOverlayBackgroundGradient(gradient);
        var renderer = renderer(save, "");
        var image = new BufferedImage(renderer.width(), renderer.computeHeight(), BufferedImage.TYPE_INT_ARGB);
        var g2 = image.createGraphics();
        renderer.render(g2, image.getWidth(), image.getHeight());
        g2.dispose();
        return image.getRGB(image.getWidth() / 2, 1);
    }

    @Test
    void theGradientGlossesTheTopOnlyWhenOn() {
        assertEquals(0xFF000000, topPixel(false), "plain background");
        assertNotEquals(0xFF000000, topPixel(true), "lightened by the gloss");
    }
}
