package org.paperwork.mobile;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;

import java.io.File;
import java.io.IOException;

final class ScanImage {
    static Bitmap read(ContentResolver resolver, Uri uri, int limit) throws IOException {
        return read(ImageDecoder.createSource(resolver, uri), limit);
    }

    static Bitmap read(File file, int limit) throws IOException {
        return read(ImageDecoder.createSource(file), limit);
    }

    private static Bitmap read(ImageDecoder.Source source, int limit) throws IOException {
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float s = Math.min(1f, (float) limit / Math.max(w, h));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
        });
    }

    static float[] detect(Bitmap photo) {
        float scale = 168f / Math.max(photo.getWidth(), photo.getHeight());
        Bitmap miniature = Bitmap.createScaledBitmap(photo, Math.max(2, Math.round(photo.getWidth() * scale)),
                Math.max(2, Math.round(photo.getHeight() * scale)), true);
        int w = miniature.getWidth(), h = miniature.getHeight();
        int[] colors = new int[w * h];
        miniature.getPixels(colors, 0, w, 0, 0, w, h);
        if (miniature != photo) miniature.recycle();
        return ScanMath.detect(colors, w, h);
    }

    static Bitmap correct(Bitmap photo, float[] corners, int effect, int rotation) throws IOException {
        if (!ScanMath.valid(corners)) throw new IOException("Adjust the four crop corners.");
        int originalWidth = photo.getWidth(), originalHeight = photo.getHeight();
        float[] pixels = corners.clone();
        for (int i = 0; i < 8; i += 2) {
            pixels[i] *= originalWidth;
            pixels[i + 1] *= originalHeight;
        }
        int w = Math.max(1, Math.round(Math.max(distance(pixels, 0, 2), distance(pixels, 4, 6))));
        int h = Math.max(1, Math.round(Math.max(distance(pixels, 0, 6), distance(pixels, 2, 4))));
        boolean sideways = rotation % 180 != 0;
        Matrix perspective = new Matrix();
        float[] box = {0, 0, w, 0, w, h, 0, h};
        Matrix turn = new Matrix();
        turn.setRotate(rotation);
        android.graphics.RectF original = new android.graphics.RectF(0, 0, w, h);
        turn.mapRect(original);
        turn.postTranslate(-original.left, -original.top);
        // Fit the original corners to a rectangle, then apply the chosen quarter turn.
        if (!perspective.setPolyToPoly(pixels, 0, box, 0, 4)) {
            throw new IOException("The crop shape is too narrow. Move the corners farther apart.");
        }
        perspective.postConcat(turn);
        Bitmap output = Bitmap.createBitmap(sideways ? h : w, sideways ? w : h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(android.graphics.Color.WHITE);
        canvas.drawBitmap(photo, perspective, new Paint(Paint.FILTER_BITMAP_FLAG));
        if (effect != 0) {
            int width = output.getWidth(), height = output.getHeight();
            int[] argb = new int[width * height];
            output.getPixels(argb, 0, width, 0, 0, width, height);
            ScanMath.enhance(argb, width, height, effect);
            output.setPixels(argb, 0, width, 0, 0, width, height);
        }
        return output;
    }

    private static float distance(float[] points, int a, int b) {
        return (float) Math.hypot(points[a] - points[b], points[a + 1] - points[b + 1]);
    }
}
