/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.fml.earlydisplay;

import net.minecraftforge.fml.loading.progress.Message;
import net.minecraftforge.fml.loading.progress.ProgressMeter;
import net.minecraftforge.fml.loading.progress.StartupNotificationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.lwjgl.opengl.GL32C.*;

public class RenderElement {
    static final int INDEX_TEXTURE_OFFSET = 5;
    private final SimpleBufferBuilder bb;
    private final Renderer renderer;
    static int globalAlpha = 255;
    private int retireCount;

    interface Renderer {

        void accept(SimpleBufferBuilder bb, DisplayContext context, int frame);

        default Renderer then(Renderer r) {
            if (r == null) return this;
            return (bb, ctx, frame) -> {
                r.accept(bb, ctx, frame);
                this.accept(bb, ctx, frame);
            };
        }
    }
    @FunctionalInterface
    interface TextureRenderer {
        void accept(SimpleBufferBuilder bb, DisplayContext context, int[] size, int frame);
    }
    @FunctionalInterface
    interface Initializer extends Supplier<Renderer> {}

    @FunctionalInterface
    interface TextGenerator {
        void accept(SimpleBufferBuilder bb, SimpleFont fh, DisplayContext ctx);
    }

    public record DisplayContext(int width, int height, int scale, ElementShader elementShader, ColourScheme colourScheme, PerformanceInfo performance) {
        public int scaledWidth() {
            return scale() * width();
        }
        public int scaledHeight() {
            return scale() * height();
        }
    }

    public RenderElement(final Initializer rendererInitializer) {
        this.bb = new SimpleBufferBuilder(1);
        this.renderer = rendererInitializer.get();
    }

    public boolean render(DisplayContext ctx, int count) {
        ctx.elementShader().activate();
        this.renderer.accept(bb, ctx, count);
        return this.retireCount == 0 || this.retireCount < count;
    }

    public void retire(final int frame) {
        this.retireCount = frame;
    }

    private static void startupLogMessages(SimpleBufferBuilder bb, SimpleFont font, DisplayContext context) {
        List<StartupNotificationManager.AgeMessage> messages = StartupNotificationManager.getMessages();
        List<SimpleFont.DisplayText> texts = new ArrayList<>();
        texts.add(new SimpleFont.DisplayText("Lancement de The Site en cours...\n", 0xFFFFFFFF));

        if (!messages.isEmpty()) {
            Message msg = messages.get(messages.size() - 1).message();
            texts.add(new SimpleFont.DisplayText(msg.getText(), 0xAFFFFFFF));
        }

        font.generateVerticesForTexts(10, context.scaledHeight() - texts.size() * font.lineSpacing() + font.descent() - 10, bb, texts.toArray(SimpleFont.DisplayText[]::new));
    }

    public static RenderElement logMessageOverlay(SimpleFont font) {
        return new RenderElement(RenderElement.initializeText(font, RenderElement::startupLogMessages));
    }

    public static RenderElement logo() {
        return new RenderElement(RenderElement.initializeTexture("logo.png", 400, 6, (bb, ctx, sz, frame) -> {
            float scaleFactor = 0.5f;
            var x0 = (ctx.scaledWidth() - sz[0] * ctx.scale() * scaleFactor) / 2;
            var x1 = (ctx.scaledWidth() + sz[0] * ctx.scale() * scaleFactor) / 2;
            var y0 = (ctx.scaledHeight() - sz[0] * ctx.scale() * scaleFactor) / 2;
            var y1 = (ctx.scaledHeight() + sz[0] * ctx.scale() * scaleFactor) / 2;
            QuadHelper.loadQuad(bb, x0, x1, y0, y1, 0f, 1f, 0f, 1f, 0xFFFFFFFF);
        }));
    }

    public static RenderElement spinner() {
        return new RenderElement(RenderElement.initializeTexture("spinner.png", 400, 7, (bb, ctx, sz, frame) -> {
            int spinnerSize = 48 * ctx.scale();
            int margin = 20 * ctx.scale();
            int frames = 30;

            var x1 = ctx.scaledWidth() - margin;
            var x0 = x1 - spinnerSize;
            var y1 = ctx.scaledHeight() - margin;
            var y0 = y1 - spinnerSize;

            int frameidx = frame % frames;
            float framepos = (frameidx * (float)sz[0]) / sz[1];
            float framesize = sz[0] / (float)sz[1];

            QuadHelper.loadQuad(bb, x0, x1, y0, y1, 0f, 1f, framepos, framepos+framesize, 0xFFFFFFFF);
        }));
    }

    public static RenderElement background() {
        return new RenderElement(RenderElement.initializeTexture("background.png", 40000, 5, (bb, ctx, sz, frame) -> {
            var x0 = 0;
            var x1 = ctx.scaledWidth() * ctx.scale();
            var y0 = 0;
            var y1 = ctx.scaledHeight() * ctx.scale();
            QuadHelper.loadQuad(bb, x0, x1, y0, y1, 0f, 1f, 0f, 1f, 0xFFFFFFFF);
        }));
    }

    public static RenderElement progressBars(SimpleFont font) {
        return new RenderElement(() -> (bb, ctx, frame) -> RenderElement.startupProgressBars(font, bb, ctx, frame));
    }

    public static void startupProgressBars(SimpleFont font, final SimpleBufferBuilder buffer, final DisplayContext context, final int frameNumber) {
        Renderer acc = null;
        var barCount = 2;
        List<ProgressMeter> currentProgress = StartupNotificationManager.getCurrentProgress();
        var size = currentProgress.size();
        var alpha = 0xFF;
        for (int i = 0; i < barCount && i < size; i++) {
            final ProgressMeter pm = currentProgress.get(i);
            Renderer barRenderer = barRenderer(i, alpha, font, pm, context);
            acc = barRenderer.then(acc);
            alpha >>= 1;
        }
        if (acc != null)
            acc.accept(buffer, context, frameNumber);
    }

    private static final int BAR_HEIGHT = 20;
    private static final int BAR_WIDTH = 400;
    private static Renderer barRenderer(int cnt, int alpha, SimpleFont font, ProgressMeter pm, DisplayContext context) {
        var barSpacing = font.lineSpacing() - font.descent() + BAR_HEIGHT;
        var y = 250 * context.scale() + cnt * barSpacing;
        var colour = (alpha << 24) | 0xFFFFFF;
        Renderer bar;
        if (pm.steps() == 0) {
            bar = progressBar(ctx->new int[] {(ctx.scaledWidth() - BAR_WIDTH * ctx.scale()) / 2, y + font.lineSpacing() - font.descent(), BAR_WIDTH * ctx.scale()}, f->colour, frame -> indeterminateBar(frame, cnt == 0));
        } else {
            bar = progressBar(ctx -> new int[]{(ctx.scaledWidth() - BAR_WIDTH * ctx.scale()) / 2, y + font.lineSpacing() - font.descent(), BAR_WIDTH * ctx.scale()}, f -> colour, f -> new float[]{0f, pm.progress()});
        }
        Renderer label = (bb, ctx, frame) -> renderText(font, text((ctx.scaledWidth() - BAR_WIDTH * ctx.scale()) / 2, y, pm.label().getText(), colour), bb, ctx);
        return bar.then(label);
    }
    private static float[] indeterminateBar(int frame, boolean isActive) {
        if (RenderElement.globalAlpha != 0xFF || !isActive) {
            return new float[] {0f,1f};
        } else {
            var progress = frame % 100;
            return new float[]{clamp((progress - 2) / 100f, 0f, 1f), clamp((progress + 2) / 100f, 0f, 1f)};
        }
    }

    @FunctionalInterface
    interface ColourFunction {
        int colour(int frame);
    }

    @FunctionalInterface
    interface ProgressDisplay {
        float[] progress(int frame);
    }

    @FunctionalInterface
    interface BarPosition {
        int[] location(DisplayContext context);
    }
    public static Renderer progressBar(BarPosition position, ColourFunction colourFunction, ProgressDisplay progressDisplay) {
        return (bb, context, frame) -> {
            var colour = colourFunction.colour(frame);
            var alpha = (colour & 0xFF000000) >> 24;
            context.elementShader().updateTextureUniform(0);
            context.elementShader().updateRenderTypeUniform(ElementShader.RenderType.BAR);
            var progress = progressDisplay.progress(frame);
            bb.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
            var inset = 2;
            var pos = position.location(context);
            var x0 = pos[0];
            var x1 = pos[0] + pos[2] + 4 * inset;
            var y0 = pos[1];
            var y1 = y0 + BAR_HEIGHT;
            QuadHelper.loadQuad(bb, x0, x1, y0, y1, 0f, 0f, 0f, 0f, context.colourScheme().foreground().packedint(alpha));

            x0 += inset;
            x1 -= inset;
            y0 += inset;
            y1 -= inset;
            QuadHelper.loadQuad(bb, x0, x1, y0, y1, 0f, 0f, 0f, 0f, context.colourScheme().background().packedint(RenderElement.globalAlpha));

            x1 = x0 + inset + (int)(progress[1] * pos[2]);
            x0 += inset + progress[0] * pos[2];
            y0 += inset;
            y1 -= inset;
            QuadHelper.loadQuad(bb, x0, x1, y0, y1, 0f, 0f, 0f, 0f, colour);
            bb.draw();
        };
    }

    private static Initializer initializeText(SimpleFont font, TextGenerator textGenerator) {
        return () -> (bb, context, frame) -> renderText(font, textGenerator, bb, context);
    }

    private static void renderText(final SimpleFont font, final TextGenerator textGenerator, final SimpleBufferBuilder bb, final DisplayContext context) {
        context.elementShader().updateTextureUniform(font.textureNumber());
        context.elementShader().updateRenderTypeUniform(ElementShader.RenderType.FONT);
        bb.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        textGenerator.accept(bb, font, context);
        bb.draw();
    }

    private static TextGenerator text(int x, int y, String text, int colour) {
        return (bb, font, context) -> font.generateVerticesForTexts(x, y, bb, new SimpleFont.DisplayText(text, colour));
    }
    private static Initializer initializeTexture(final String textureFileName, int size, int textureNumber, TextureRenderer positionAndColour) {
        return ()->{
            int[] imgSize = STBHelper.loadTextureFromClasspath(textureFileName, size, GL_TEXTURE0 + textureNumber + INDEX_TEXTURE_OFFSET);
            return (bb, ctx, frame) -> {
                ctx.elementShader().updateTextureUniform(textureNumber + INDEX_TEXTURE_OFFSET);
                ctx.elementShader().updateRenderTypeUniform(ElementShader.RenderType.TEXTURE);
                renderTexture(bb, ctx, frame, imgSize, positionAndColour);
            };
        };
    }

    private static void renderTexture(SimpleBufferBuilder bb, DisplayContext context, int frame, int[] size, TextureRenderer positionAndColour) {
        bb.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        positionAndColour.accept(bb, context, size, frame);
        bb.draw();
    }


    public static float clamp(float num, float min, float max) {
        if (num < min) {
            return min;
        } else {
            return Math.min(num, max);
        }
    }

    public static int clamp(int num, int min, int max) {
        if (num < min) {
            return min;
        } else {
            return Math.min(num, max);
        }
    }

    public static int hsvToRGB(float hue, float saturation, float value) {
        int i = (int)(hue * 6.0F) % 6;
        float f = hue * 6.0F - (float)i;
        float f1 = value * (1.0F - saturation);
        float f2 = value * (1.0F - f * saturation);
        float f3 = value * (1.0F - (1.0F - f) * saturation);
        float f4;
        float f5;
        float f6;
        switch(i) {
            case 0:
                f4 = value;
                f5 = f3;
                f6 = f1;
                break;
            case 1:
                f4 = f2;
                f5 = value;
                f6 = f1;
                break;
            case 2:
                f4 = f1;
                f5 = value;
                f6 = f3;
                break;
            case 3:
                f4 = f1;
                f5 = f2;
                f6 = value;
                break;
            case 4:
                f4 = f3;
                f5 = f1;
                f6 = value;
                break;
            case 5:
                f4 = value;
                f5 = f1;
                f6 = f2;
                break;
            default:
                throw new RuntimeException("Something went wrong when converting from HSV to RGB. Input was " + hue + ", " + saturation + ", " + value);
        }

        int j = clamp((int)(f4 * 255.0F), 0, 255);
        int k = clamp((int)(f5 * 255.0F), 0, 255);
        int l = clamp((int)(f6 * 255.0F), 0, 255);
        return 0xFF << 24 | j << 16 | k << 8 | l;
    }
}
