package org.paperwork.mobile;

import java.util.Arrays;

/** Scan correction on bounded ARGB buffers, independent of Android's UI. */
final class ScanMath {
    static final String[] FILTERS = {"Original", "Document", "Grayscale", "Black & white"};

    static float[] full() {
        return new float[]{0, 0, 1, 0, 1, 1, 0, 1};
    }

    static int gray(int argb) {
        return (54 * (argb >> 16 & 255) + 183 * (argb >> 8 & 255) + 19 * (argb & 255)) >> 8;
    }

    static boolean valid(float[] points) {
        if (points.length != 8) return false;
        float twiceArea = 0;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = (a + 2) % 8, c = (a + 4) % 8;
            if (!(points[a] >= 0 && points[a] <= 1 && points[a + 1] >= 0 && points[a + 1] <= 1)) {
                return false;
            }
            float ax = points[b] - points[a], ay = points[b + 1] - points[a + 1];
            float bx = points[c] - points[b], by = points[c + 1] - points[b + 1];
            if (ax * by - ay * bx <= .002f) return false;
            twiceArea += points[a] * points[b + 1] - points[a + 1] * points[b];
        }
        return twiceArea > .035f;
    }

    /** Approximate corners of the largest bright component on a contrasting surface. */
    static float[] detect(int[] pixels, int width, int height) {
        int[] hist = new int[256];
        long sum = 0;
        byte[] luminance = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int lum = gray(pixels[i]);
            hist[lum]++;
            sum += lum;
            luminance[i] = (byte) lum;
        }
        long lowerSum = 0;
        int lowerCount = 0, threshold = 150;
        double best = 0;
        for (int value = 0; value < 255; value++) {
            lowerCount += hist[value];
            lowerSum += (long) value * hist[value];
            int remaining = pixels.length - lowerCount;
            if (lowerCount == 0 || remaining == 0) continue;
            double difference = (double) lowerSum / lowerCount - (double) (sum - lowerSum) / remaining;
            double score = (double) lowerCount * remaining * difference * difference;
            if (score > best) {
                best = score;
                threshold = value;
            }
        }
        threshold = Math.max(102, Math.min(224, threshold + 13));
        boolean[] seen = new boolean[pixels.length];
        int[] queue = new int[pixels.length];
        int[] largest = null;
        int largestCount = 0;
        for (int start = 0; start < pixels.length; start++) {
            if (seen[start] || (luminance[start] & 255) < threshold) continue;
            int tail = 0;
            seen[start] = true;
            queue[tail++] = start;
            int[] candidates = {start, start, start, start};
            int[] scores = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int head = 0; head < tail; head++) {
                int at = queue[head], x = at % width, y = at / width;
                if (x + y < scores[0]) { scores[0] = x + y; candidates[0] = at; }
                if (x - y > scores[1]) { scores[1] = x - y; candidates[1] = at; }
                if (x + y > scores[2]) { scores[2] = x + y; candidates[2] = at; }
                if (y - x > scores[3]) { scores[3] = y - x; candidates[3] = at; }
                if (x > 0) tail = visit(at - 1, luminance, seen, queue, tail, threshold);
                if (x < width - 1) tail = visit(at + 1, luminance, seen, queue, tail, threshold);
                if (y > 0) tail = visit(at - width, luminance, seen, queue, tail, threshold);
                if (y < height - 1) tail = visit(at + width, luminance, seen, queue, tail, threshold);
            }
            if (tail > largestCount) {
                largestCount = tail;
                largest = candidates;
            }
        }
        if (largest == null || largestCount < pixels.length * .19 || largestCount > pixels.length * .96) {
            return full();
        }
        float[] points = new float[8];
        int imageCorners = 0;
        for (int i = 0; i < 4; i++) {
            float x = (float) (largest[i] % width) / (width - 1);
            float y = (float) (largest[i] / width) / (height - 1);
            points[2 * i] = x;
            points[2 * i + 1] = y;
            if (Math.min(x, 1 - x) < .018 && Math.min(y, 1 - y) < .018) imageCorners++;
        }
        return imageCorners < 3 && valid(points) ? points : full();
    }

    private static int visit(int index, byte[] luminance, boolean[] seen, int[] queue, int tail, int threshold) {
        if (!seen[index] && (luminance[index] & 255) >= threshold) {
            seen[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    /** Estimate local paper brightness from overlapping bright tile samples. */
    static void enhance(int[] pixels, int width, int height, int filter) {
        if (filter == 0) return;
        int step = Math.max(12, Math.max(width, height) / 28);
        int cols = Math.max(2, (width + step - 1) / step + 1);
        int rows = Math.max(2, (height + step - 1) / step + 1);
        int[] background = new int[cols * rows];
        int[] histogram = new int[256];
        for (int gy = 0; gy < rows; gy++) {
            for (int gx = 0; gx < cols; gx++) {
                Arrays.fill(histogram, 0);
                int count = 0;
                for (int y = Math.max(0, (gy - 1) * step); y < Math.min(height, (gy + 1) * step); y += 3) {
                    for (int x = Math.max(0, (gx - 1) * step); x < Math.min(width, (gx + 1) * step); x += 3) {
                        histogram[gray(pixels[y * width + x])]++;
                        count++;
                    }
                }
                int n = 0;
                int value = 255;
                for (; value > 0; value--) {
                    n += histogram[value];
                    if (n >= Math.max(1, count / 8)) break;
                }
                background[gy * cols + gx] = Math.max(90, value);
            }
        }
        for (int y = 0; y < height; y++) {
            int row = y / step;
            float dy = (float) (y % step) / step;
            for (int x = 0; x < width; x++) {
                int col = x / step, at = y * width + x;
                float dx = (float) (x % step) / step;
                int tile = row * cols + col;
                float top = background[tile] * (1 - dx) + background[tile + 1] * dx;
                float bottom = background[tile + cols] * (1 - dx) + background[tile + cols + 1] * dx;
                float light = 250 / (top * (1 - dy) + bottom * dy);
                int p = pixels[at];
                if (filter == 3) {
                    pixels[at] = gray(p) * light < 178 ? 0xff111111 : 0xffffffff;
                } else if (filter == 2) {
                    int v = ink(gray(p), light);
                    pixels[at] = 0xff000000 | v << 16 | v << 8 | v;
                } else {
                    int r = ink(p >> 16 & 255, light), g = ink(p >> 8 & 255, light), b = ink(p & 255, light);
                    pixels[at] = 0xff000000 | r << 16 | g << 8 | b;
                }
            }
        }
    }

    private static int ink(int value, float light) {
        float corrected = value * light;
        if (corrected >= 239) return 255;
        return Math.max(0, Math.round((corrected - 18) * 1.15f));
    }
}
