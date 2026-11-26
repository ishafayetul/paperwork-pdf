package org.paperwork.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class PagePreview extends View {
    interface Changed { void apply(float[] points); }

    static final int READ = 0, CORNERS = 1, BOX = 2;
    private final int mode;
    private final Changed changed;
    private final Paint paint = new Paint(3);
    private final RectF paper = new RectF();
    private Bitmap bitmap;
    private float[] corners;
    private int handle = -1;
    private float anchorX, anchorY;
    private float[] original;

    PagePreview(Context context, int mode, Changed changed) {
        super(context);
        this.mode = mode;
        this.changed = changed;
        setBackground(Ui.shape(0xffe4ece4, 0xffd4e1d7, 16, context));
        setContentDescription(mode == CORNERS ? "Scan crop. Drag any of the four corner handles."
                : mode == BOX ? "PDF preview. Drag to select a rectangle." : "Page preview");
    }

    void setPage(Bitmap image, float[] selection) {
        if (bitmap != null && bitmap != image) bitmap.recycle();
        bitmap = image;
        corners = selection == null ? null : selection.clone();
        invalidate();
    }

    void selection(float[] value) {
        corners = value == null ? null : value.clone();
        invalidate();
    }

    void release() { setPage(null, null); }

    @Override protected void onDraw(Canvas canvas) {
        if (bitmap == null) return;
        float pad = Ui.dp(getContext(), 17);
        float scale = Math.min((getWidth() - 2 * pad) / bitmap.getWidth(),
                (getHeight() - 2 * pad) / bitmap.getHeight());
        if (scale <= 0) return;
        float w = scale * bitmap.getWidth(), h = scale * bitmap.getHeight();
        float x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
        paper.set(x, y, x + w, y + h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffbfcdc1);
        canvas.drawRect(x + 2, y + 4, x + w + 2, y + h + 4, paint);
        canvas.drawBitmap(bitmap, null, paper, paint);
        if (corners == null) return;
        Path region = new Path();
        region.moveTo(px(corners[0]), py(corners[1]));
        for (int i = 2; i < 8; i += 2) region.lineTo(px(corners[i]), py(corners[i + 1]));
        region.close();
        if (mode == CORNERS) {
            Path outside = new Path();
            outside.setFillType(Path.FillType.EVEN_ODD);
            outside.addRect(paper, Path.Direction.CW);
            outside.addPath(region);
            paint.setColor(0x830c251a);
            canvas.drawPath(outside, paint);
        } else {
            paint.setColor(0x416fb77f);
            canvas.drawPath(region, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 2));
        paint.setColor(mode == CORNERS ? 0xffdff978 : Ui.GREEN);
        canvas.drawPath(region, paint);
        paint.setStyle(Paint.Style.FILL);
        if (mode == CORNERS) {
            for (int i = 0; i < 8; i += 2) {
                paint.setColor(Ui.GREEN);
                canvas.drawCircle(px(corners[i]), py(corners[i + 1]), Ui.dp(getContext(), 10), paint);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(px(corners[i]), py(corners[i + 1]), Ui.dp(getContext(), 6), paint);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (mode == READ || bitmap == null || paper.isEmpty()) return false;
        float x = clamp((event.getX() - paper.left) / paper.width());
        float y = clamp((event.getY() - paper.top) / paper.height());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                original = corners == null ? null : corners.clone();
                if (mode == CORNERS && corners != null) {
                    float closest = Ui.dp(getContext(), 32);
                    for (int i = 0; i < 8; i += 2) {
                        float distance = (float) Math.hypot(event.getX() - px(corners[i]),
                                event.getY() - py(corners[i + 1]));
                        if (distance < closest) {
                            handle = i;
                            closest = distance;
                        }
                    }
                } else if (mode == BOX && paper.contains(event.getX(), event.getY())) {
                    handle = 0;
                    anchorX = x;
                    anchorY = y;
                }
                if (handle < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (handle < 0) return false;
                if (mode == BOX) {
                    corners = rectangle(Math.min(x, anchorX), Math.min(y, anchorY),
                            Math.max(x, anchorX), Math.max(y, anchorY));
                } else {
                    float[] next = corners.clone();
                    next[handle] = x;
                    next[handle + 1] = y;
                    if (ScanMath.valid(next)) corners = next;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (mode == BOX && (corners[2] - corners[0] < .025 || corners[5] - corners[1] < .025)) {
                        corners = original;
                    } else if (changed != null) changed.apply(corners.clone());
                    handle = -1;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    performClick();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                corners = original;
                handle = -1;
                invalidate();
                return true;
            default:
                return false;
        }
    }

    static float[] rectangle(float left, float top, float right, float bottom) {
        return new float[]{left, top, right, top, right, bottom, left, bottom};
    }

    private float px(float x) { return paper.left + paper.width() * x; }
    private float py(float y) { return paper.top + paper.height() * y; }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    @Override public boolean performClick() { return super.performClick(); }
}
