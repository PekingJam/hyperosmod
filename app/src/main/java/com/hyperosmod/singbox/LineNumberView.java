package com.hyperosmod.singbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;

/**
 * 编辑器左侧的行号（按逻辑行编号，和 VS Code 一样）。
 *
 * <p>折行的续行不再各自编号：一个换行符算一行，续行那一格留空。实现上不额外维护滚动
 * 偏移——它和编辑框并排放在同一个 {@code NestedScrollView} 里，纵向滚动天然同步，只按
 * {@link Layout} 的行基线把数字画出来即可。</p>
 */
public final class LineNumberView extends View {

    private static final int CHAR_DP = 8;
    private static final int PADDING_DP = 8;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private EditText editor;
    /** 上一次的行数位数：只有位数变了才重新测量，避免每帧 requestLayout 干扰滚动。 */
    private int lastDigits = -1;

    public LineNumberView(Context context) {
        this(context, null);
    }

    public LineNumberView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
                getResources().getDisplayMetrics()));
        paint.setTextAlign(Paint.Align.RIGHT);
    }

    /** 绑定编辑框；文本或行数变化时重算宽度并重绘。 */
    public void attach(EditText target) {
        editor = target;
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int digits = digits();
                if (digits != lastDigits) {
                    lastDigits = digits;
                    requestLayout();
                }
                invalidate();
            }
        });
        lastDigits = digits();
        requestLayout();
        invalidate();
    }

    /** 逻辑行数 = 换行符个数 + 1。 */
    private int logicalLines() {
        CharSequence text = editor == null ? "" : editor.getText();
        if (text == null) {
            return 1;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private int digits() {
        return String.valueOf(Math.max(1, logicalLines())).length();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CHAR_DP,
                getResources().getDisplayMetrics());
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, PADDING_DP,
                getResources().getDisplayMetrics());
        int width = digits() * px + pad * 2;
        int height = 0;
        if (editor != null) {
            Layout layout = editor.getLayout();
            if (layout != null) {
                height = layout.getHeight() + editor.getPaddingTop() + editor.getPaddingBottom();
            }
            height = Math.max(height, editor.getMeasuredHeight());
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editor == null) {
            return;
        }
        Layout layout = editor.getLayout();
        if (layout == null) {
            return;
        }
        CharSequence text = editor.getText();
        if (text == null) {
            return;
        }
        int right = getWidth() - dp(PADDING_DP);
        int top = editor.getPaddingTop();
        int count = layout.getLineCount();
        int logical = 1;
        int scanned = 0;
        int drawn = -1;
        for (int line = 0; line < count; line++) {
            int start = layout.getLineStart(line);
            while (scanned < start && scanned < text.length()) {
                if (text.charAt(scanned) == '\n') {
                    logical++;
                }
                scanned++;
            }
            // 折行的续行起点前面没有换行符，logical 与上一行相同，跳过不画。
            if (logical != drawn) {
                drawn = logical;
                float baseline = top + layout.getLineBaseline(line);
                canvas.drawText(String.valueOf(logical), right, baseline, paint);
            }
        }
    }

    /** 跟随编辑器的文字颜色深浅。 */
    public void setTextColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
