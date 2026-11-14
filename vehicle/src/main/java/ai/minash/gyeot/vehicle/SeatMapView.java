package ai.minash.gyeot.vehicle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

final class SeatMapView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final DashPathEffect dash = new DashPathEffect(new float[]{4, 3}, 0);
    private int selectedSeat = 1;
    private boolean observing;
    private float mapScale = 1;

    SeatMapView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateDescription();
    }

    void setSeat(int seat) {
        selectedSeat = seat == 0 ? 0 : 1;
        updateDescription();
        invalidate();
    }

    void setObserving(boolean value) {
        if (observing == value) return;
        observing = value;
        updateDescription();
        invalidate();
    }

    private void updateDescription() {
        setContentDescription("설정한 관찰 위치: 2열 " + (selectedSeat == 0 ? "왼쪽" : "오른쪽")
                + (observing ? ", 카메라 관찰 중" : ", 카메라 관찰 대기"));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 340f, getHeight() / 440f);
        mapScale = scale;
        int save = canvas.save();
        canvas.translate((getWidth() - 340 * scale) / 2, (getHeight() - 440 * scale) / 2);
        canvas.scale(scale, scale);
        path.reset();
        path.moveTo(91, 24);
        path.cubicTo(123, 12, 217, 12, 249, 24);
        path.cubicTo(284, 38, 292, 80, 292, 155);
        path.lineTo(292, 313);
        path.cubicTo(292, 370, 285, 405, 253, 416);
        path.cubicTo(219, 429, 121, 429, 87, 416);
        path.cubicTo(55, 405, 48, 370, 48, 313);
        path.lineTo(48, 155);
        path.cubicTo(48, 80, 56, 38, 91, 24);
        path.close();
        fill(Color.rgb(36, 44, 51)); canvas.drawPath(path, paint);
        stroke(getContext().getColor(R.color.graphite_map_outline), 2.2f); canvas.drawPath(path, paint);
        stroke(Color.rgb(74, 86, 95), 2);
        path.reset(); path.moveTo(87, 55); path.cubicTo(125, 42, 215, 42, 253, 55);
        path.moveTo(87, 390); path.cubicTo(125, 404, 215, 404, 253, 390);
        canvas.drawPath(path, paint);
        ordinarySeat(canvas, 107, 112, "운전석");
        ordinarySeat(canvas, 233, 112, "동승석");
        stroke(Color.rgb(74, 86, 95), 1.5f);
        canvas.drawRoundRect(156, 98, 184, 194, 6, 6, paint);
        canvas.drawLine(170, 112, 170, 180, paint);
        paint.setPathEffect(dash);
        canvas.drawLine(72, 236, 268, 236, paint);
        paint.setPathEffect(null);
        ordinarySeat(canvas, selectedSeat == 0 ? 233 : 107, 280, selectedSeat == 0 ? "2열 오른쪽" : "2열 왼쪽");
        childSeat(canvas, selectedSeat == 0 ? 107 : 233);
        canvas.restoreToCount(save);
    }

    private void ordinarySeat(Canvas canvas, float center, float top, String label) {
        fill(Color.rgb(25, 29, 33));
        canvas.drawRoundRect(center - 30, top, center + 30, top + 74, 10, 10, paint);
        stroke(Color.rgb(125, 137, 147), 1.6f);
        canvas.drawRoundRect(center - 30, top, center + 30, top + 74, 10, 10, paint);
        canvas.drawRoundRect(center - 20, top - 22, center + 20, top - 8, 5, 5, paint);
        stroke(Color.rgb(74, 86, 95), 1.4f);
        canvas.drawLine(center - 18, top + 9, center - 18, top + 61, paint);
        canvas.drawLine(center + 18, top + 9, center + 18, top + 61, paint);
        fill(Color.rgb(182, 190, 197));
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        if (mapScale >= .9f) {
            paint.setTextSize(Math.max(11, 18 / mapScale));
            canvas.drawText(label.startsWith("2열") ? "2열" : label, center, top + 43, paint);
        }
    }

    private void childSeat(Canvas canvas, float center) {
        int selected = observing ? getContext().getColor(R.color.graphite_primary) : Color.rgb(137, 148, 157);
        stroke(selected, 1.4f);
        paint.setPathEffect(dash);
        canvas.drawRoundRect(center - 39, 257, center + 39, 375, 18, 18, paint);
        paint.setPathEffect(null);
        fill(Color.rgb(37, 45, 52));
        canvas.drawRoundRect(center - 32, 267, center + 32, 366, 14, 14, paint);
        stroke(selected, 2.6f);
        canvas.drawRoundRect(center - 32, 267, center + 32, 366, 14, 14, paint);
        fill(selected);
        canvas.drawRoundRect(center - 23, 247, center + 23, 264, 7, 7, paint);
        stroke(selected, 2.5f);
        path.reset();
        path.moveTo(center - 25, 281); path.cubicTo(center - 18, 292, center - 18, 330, center - 25, 345);
        path.moveTo(center + 25, 281); path.cubicTo(center + 18, 292, center + 18, 330, center + 25, 345);
        canvas.drawPath(path, paint);
        canvas.drawLine(center, 299, center, 321, paint);
        canvas.drawLine(center - 12, 307, center, 325, paint);
        canvas.drawLine(center + 12, 307, center, 325, paint);
        fill(GraphiteUi.INK);
        canvas.drawCircle(center, 285, 6, paint);
        canvas.drawCircle(center, 325, 4.5f, paint);
        stroke(selected, 1.8f);
        float direction = selectedSeat == 0 ? -1 : 1;
        canvas.drawLine(center + direction * 33, 333, center + direction * 63, 333, paint);
        fill(selected); canvas.drawCircle(center + direction * 63, 333, 3, paint);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setPathEffect(null);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
}
