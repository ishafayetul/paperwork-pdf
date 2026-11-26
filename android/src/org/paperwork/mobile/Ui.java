package org.paperwork.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;

import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xfff4f7f2, INK = 0xff172e29, GREEN = 0xff1a493d, SOFT = 0xff526d60,
            BORDER = 0xffdce7dd, WHITE = 0xffffffff, LIME = 0xffdff978;

    static int dp(Context context, float size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(int fill, int stroke, int radius, Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(context, radius));
        if (stroke != 0) bg.setStroke(dp(context, 1), stroke);
        return bg;
    }

    static void interactive(View view, int fill, int stroke, int radius) {
        Context context = view.getContext();
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28538a73),
                shape(fill, stroke, radius, context), null));
    }

    static LinearLayout column(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    static TextView text(Context context, String text, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    static TextView button(Context context, String text, int fill, int fg, Runnable action) {
        android.widget.Button view = new android.widget.Button(context, null, android.R.attr.borderlessButtonStyle);
        view.setText(text);
        view.setAllCaps(false);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setMinimumHeight(dp(context, 46));
        view.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0xff627b6d, fg}));
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        interactive(view, fill, fill == WHITE ? BORDER : 0, 11);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    static void gap(LinearLayout parent, int height) {
        parent.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(1, dp(parent.getContext(), height)));
    }

    static void weight(LinearLayout parent, View view) {
        int margin = parent.getChildCount() == 0 ? 0 : dp(parent.getContext(), 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = margin;
        parent.addView(view, lp);
    }

    static final class Glyph extends View {
        private final Paint paint = new Paint(3);
        private final String name;
        private final int color;

        Glyph(Context context, String name, int color) {
            super(context);
            this.name = name;
            this.color = color;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(getWidth() / 44f, getHeight() / 44f);
            if ("hero".equals(name)) {
                hero(canvas);
            } else {
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.9f);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.translate(10, 10);
                switch (name) {
                    case "scan":
                    case "crop":
                        line(canvas, 3,9,3,3,9,3);
                        line(canvas, 15,3,21,3,21,9);
                        line(canvas, 21,15,21,21,15,21);
                        line(canvas, 9,21,3,21,3,15);
                        line(canvas, 6, 12, 18, 12);
                        break;
                    case "merge":
                    case "split":
                        canvas.drawRoundRect(3, 3, 10, 12, 1.4f, 1.4f, paint);
                        canvas.drawRoundRect(14, 3, 21, 12, 1.4f, 1.4f, paint);
                        line(canvas, 6,15,6,18,18,18,18,15);
                        line(canvas, 12,18,12,22);
                        break;
                    case "organize":
                        for (int y : new int[]{3, 14}) for (int x : new int[]{3, 14})
                            canvas.drawRoundRect(x,y,x+7,y+7,1.4f,1.4f,paint);
                        break;
                    case "photos":
                    case "images":
                        canvas.drawRoundRect(3, 4, 21, 20, 1.5f, 1.5f, paint);
                        canvas.drawCircle(8, 8.5f, 1.6f, paint);
                        line(canvas, 4,18,9,13,12,16,16,11,20,16);
                        break;
                    case "protect":
                    case "unlock":
                        canvas.drawRoundRect(5,10,19,21,1.5f,1.5f,paint);
                        canvas.drawArc(new RectF(8,3,16,12), 180, 180, false, paint);
                        line(canvas, 8,8,8,10);
                        if ("protect".equals(name)) line(canvas,16,8,16,10);
                        line(canvas, 12,14,12,17);
                        break;
                    default:
                        line(canvas, 6,3,14,3,19,8,19,21,5,21,5,4,6,3);
                        line(canvas, 14,3,14,8,19,8);
                        line(canvas, 8,12,15,12);
                        line(canvas, 8,16,14,16);
                }
            }
            canvas.restore();
        }

        private void hero(Canvas canvas) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(.35f);
            paint.setColor(0x4ebde4be);
            for (int size = 16; size < 44; size += 5) canvas.drawCircle(23,22,size,paint);
            drawSheet(canvas, -22, -5, 2, 0xff48826f);
            drawSheet(canvas, 19, 6, 4, 0xff80ac8f);
            drawSheet(canvas, -4, 0, -1, 0xfff1f8db);
            paint.setColor(LIME);
            line(canvas, 38,9,38,15);
            line(canvas, 35,12,41,12);
        }

        private void drawSheet(Canvas canvas, int angle, int dx, int dy, int fill) {
            canvas.save();
            canvas.translate(dx, dy);
            canvas.rotate(angle, 23, 23);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRoundRect(13, 9, 33, 35, 2.7f, 2.7f, paint);
            paint.setColor(0xff779782);
            paint.setStrokeWidth(.65f);
            line(canvas, 17,16,26,16);
            line(canvas, 17,21,29,21);
            line(canvas, 17,24,27,24);
            line(canvas, 17,27,26,27);
            canvas.restore();
        }

        private void line(Canvas canvas, float... xy) {
            paint.setStyle(Paint.Style.STROKE);
            Path path = new Path();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
    }
}
