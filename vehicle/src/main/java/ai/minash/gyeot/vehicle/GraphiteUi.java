package ai.minash.gyeot.vehicle;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

final class GraphiteUi {
    static final int INK = Color.rgb(241, 242, 243);
    static final int MUTED = Color.rgb(169, 176, 183);
    static final int RULE = Color.rgb(59, 66, 73);
    static final int PRIMARY_INK = Color.rgb(27, 34, 40);

    enum Icon { MIC, PAUSE, LOCK, PHONE, MOON, VOLUME }

    private GraphiteUi() { }

    static int dp(Context context, float size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    static TextView label(Context context, String text, int size, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(Math.max(size, 18f / context.getResources().getDisplayMetrics().scaledDensity));
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static Drawable surface(Context context, int color, boolean outlined) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, 8));
        if (outlined) shape.setStroke(dp(context, 1), Color.rgb(81, 91, 101));
        return shape;
    }

    static Button button(Context context, String text, boolean primary, Runnable action) {
        Button view = new GraphiteButton(context, primary);
        view.setText(text);
        view.setAllCaps(false);
        view.setTextSize(24);
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(178, 187, 195), primary ? PRIMARY_INK : INK}));
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPadding(dp(context, 24), dp(context, 10), dp(context, 24), dp(context, 10));
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(dp(context, 48));
        view.setMinimumHeight(dp(context, 48));
        view.setIncludeFontPadding(false);
        view.setElevation(0);
        view.setStateListAnimator(null);
        view.setBackgroundTintList(null);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    static void colorButton(Button view, boolean primary) {
        Context context = view.getContext();
        int fill = view.isEnabled() ? context.getColor(primary ? R.color.graphite_primary : R.color.graphite_secondary)
                : Color.rgb(51, 57, 64);
        Drawable background = surface(context, fill, !primary || !view.isEnabled());
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary
                ? Color.argb(28, 0, 0, 0) : Color.argb(28, 255, 255, 255)), background, null));
    }

    private static final class GraphiteButton extends Button {
        private final boolean primary;

        GraphiteButton(Context context, boolean primary) {
            super(context);
            this.primary = primary;
        }

        @Override public void setEnabled(boolean enabled) {
            if (enabled == isEnabled()) return;
            super.setEnabled(enabled);
            colorButton(this, primary);
        }
    }

    static void icon(TextView view, Icon icon, int color, int size) {
        Glyph drawable = new Glyph(icon, color);
        drawable.setBounds(0, 0, size, size);
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setCompoundDrawablePadding(Math.max(8, size / 2));
    }

    private static final class Glyph extends Drawable {
        private final Icon icon;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Glyph(Icon icon, int color) {
            this.icon = icon;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            path.reset();
            switch (icon) {
                case MIC -> {
                    canvas.drawRoundRect(9, 2, 15, 14, 3, 3, paint);
                    path.moveTo(5, 10); path.lineTo(5, 12);
                    path.cubicTo(5, 21, 19, 21, 19, 12); path.lineTo(19, 10);
                    path.moveTo(12, 19); path.lineTo(12, 22);
                    path.moveTo(8, 22); path.lineTo(16, 22);
                }
                case PAUSE -> {
                    path.moveTo(8, 5); path.lineTo(8, 19);
                    path.moveTo(16, 5); path.lineTo(16, 19);
                }
                case LOCK -> {
                    canvas.drawRoundRect(4, 10, 20, 21, 2, 2, paint);
                    path.moveTo(8, 10); path.lineTo(8, 6);
                    path.cubicTo(8, .7f, 16, .7f, 16, 6); path.lineTo(16, 10);
                }
                case PHONE -> {
                    canvas.drawRoundRect(6, 2, 18, 22, 2, 2, paint);
                    path.moveTo(11, 18); path.lineTo(13, 18);
                }
                case MOON -> {
                    path.moveTo(21, 12.8f);
                    path.cubicTo(17, 22, 3, 21, 3, 12);
                    path.cubicTo(3, 7, 7, 3, 11.2f, 3);
                    path.cubicTo(7, 9, 14, 17, 21, 12.8f);
                }
                case VOLUME -> {
                    path.moveTo(11, 5); path.lineTo(6, 9); path.lineTo(2, 9);
                    path.lineTo(2, 15); path.lineTo(6, 15); path.lineTo(11, 19); path.close();
                    path.moveTo(16, 9); path.cubicTo(18, 10, 18, 14, 16, 15);
                }
            }
            canvas.drawPath(path, paint);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
