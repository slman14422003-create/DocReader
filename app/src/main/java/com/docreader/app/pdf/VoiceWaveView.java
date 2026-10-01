package com.docreader.app.pdf;

import com.docreader.app.R;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * فقاعة (كبسولة) بداخلها موجة صوتية على شكل أعمدة مدوّرة متناظرة حول الخط الأوسط.
 *
 * الفكرة: نحتفظ بسجلّ قصير لمستوى الصوت الأخير، فيكون أحدث مستوى عند المنتصف وتنتشر القيم
 * الأقدم نحو الطرفين، فتبدو الموجة وكأنها تخرج من المنتصف مع كل كلمة. كل عمود يتحرك بطور
 * مختلف قليلًا فتبدو الحركة عضوية لا آلية، والأعمدة تتدرّج لونيًا (أغمق في المنتصف) مع توهّج
 * ناعم خلفها يقوى مع علوّ الصوت. عند الصمت تتحول الأعمدة إلى نقاط صغيرة تتموّج بهدوء
 * كأنها تتنفس، وعند الإيقاف المؤقت تخفت وتستقر.
 */
public class VoiceWaveView extends View {

    /** مصدر مستوى الصوت (0..1) - يُستدعى كل إطار من الخيط الرئيسي. */
    public interface LevelSource {
        float getLevel();
    }

    private static final int HIST = 40;              // عدد عيّنات السجلّ
    private static final float HIST_STEP = 0.026f;   // ثانية بين عيّنتين
    private static final float BAR_DP = 3.4f;        // عرض العمود
    private static final float GAP_DP = 3.0f;        // المسافة بين عمودين

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF bar = new RectF();
    private final RectF glowRect = new RectF();

    private int colorMain;
    private int colorLight;
    private int colorDark;

    private final float[] hist = new float[HIST];

    @Nullable
    private LevelSource source;
    private boolean paused = false;
    private boolean running = false;
    private float cur = 0f;
    private float dim = 1f;
    private float phase = 0f;
    private float clock = 0f;
    private float accum = 0f;
    private long lastFrame = 0L;
    private float density = 1f;

    public VoiceWaveView(Context context) {
        this(context, null);
    }

    public VoiceWaveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        bgPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(density);
        glowPaint.setStyle(Paint.Style.FILL);
        barPaint.setStyle(Paint.Style.FILL);
        refreshColors();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setLevelSource(@Nullable LevelSource s) {
        this.source = s;
    }

    /** في وضع الإيقاف المؤقت تخفّ الموجة وتهدأ. */
    public void setPaused(boolean p) {
        this.paused = p;
    }

    private void refreshColors() {
        Context c = getContext();
        colorMain = c.getColor(R.color.primary_cyan);
        colorLight = c.getColor(R.color.primary_cyan_light);
        colorDark = c.getColor(R.color.primary_cyan_dark);
        bgPaint.setColor(c.getColor(R.color.primary_soft));
        borderPaint.setColor((colorMain & 0x00FFFFFF) | 0x38000000);
        rebuildShaders(getWidth());
    }

    private void rebuildShaders(int w) {
        if (w <= 0) return;
        // تدرّج أفقي متناظر: فاتح عند الطرفين وأغمق عند المنتصف
        barPaint.setShader(new LinearGradient(0f, 0f, w, 0f,
                new int[]{colorLight, colorMain, colorDark, colorMain, colorLight},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        int clear = colorMain & 0x00FFFFFF;
        int soft = (colorMain & 0x00FFFFFF) | 0x55000000;
        glowPaint.setShader(new LinearGradient(0f, 0f, w, 0f,
                new int[]{clear, soft, clear}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    // ------------------------------------------------------------------ دورة الحياة

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshColors();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateRunning();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateRunning();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshColors();
    }

    private void updateRunning() {
        boolean should = isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE;
        if (should && !running) {
            running = true;
            lastFrame = 0L;
            postOnAnimation(frame);
        } else if (!should && running) {
            stop();
        }
    }

    private void stop() {
        running = false;
        removeCallbacks(frame);
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            invalidate();
            postOnAnimation(this);
        }
    };

    // ------------------------------------------------------------------ المحاكاة

    private void step() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0L ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        clock += dt;

        float target = 0f;
        if (!paused && source != null) {
            try {
                target = Math.max(0f, Math.min(1f, source.getLevel()));
            } catch (Throwable ignored) {
            }
        }
        // صعود سريع (يلتقط بداية الكلمة) وهبوط أبطأ (ينساب بدل أن يرتجف)
        float k = target > cur ? 1f - (float) Math.exp(-dt * 30f) : 1f - (float) Math.exp(-dt * 9f);
        cur += (target - cur) * k;

        float dimTarget = paused ? 0.45f : 1f;
        dim += (dimTarget - dim) * (1f - (float) Math.exp(-dt * 8f));

        phase += dt * (2.4f + 7f * cur);

        accum += dt;
        while (accum >= HIST_STEP) {
            accum -= HIST_STEP;
            System.arraycopy(hist, 0, hist, 1, HIST - 1);
            hist[0] = cur;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildShaders(w);
    }

    /** مستوى السجلّ عند بُعد d عن المنتصف (0 = الأحدث في المنتصف .. 1 = الأقدم عند الطرفين). */
    private float histAt(float d) {
        float f = d * (HIST - 1);
        int i0 = (int) f;
        int i1 = Math.min(HIST - 1, i0 + 1);
        float fr = f - i0;
        return hist[i0] + (hist[i1] - hist[i0]) * fr;
    }

    // ------------------------------------------------------------------ الرسم

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float half = borderPaint.getStrokeWidth() / 2f;
        rect.set(half, half, w - half, h - half);
        final float r = rect.height() / 2f;
        canvas.drawRoundRect(rect, r, r, bgPaint);

        // توهّج ناعم خلف الأعمدة يقوى مع علوّ الصوت
        final float glowA = Math.min(1f, 0.25f + 1.1f * cur) * dim;
        glowPaint.setAlpha((int) (255 * glowA));
        glowRect.set(rect.left + r * 0.3f, rect.top + h * 0.12f, rect.right - r * 0.3f, rect.bottom - h * 0.12f);
        canvas.drawRoundRect(glowRect, r, r, glowPaint);

        final float barW = BAR_DP * density;
        final float step = barW + GAP_DP * density;
        final float padX = r * 0.75f;
        final float span = Math.max(step, w - 2f * padX);
        int n = (int) (span / step);
        if (n % 2 == 0) n--;              // عدد فردي ليكون للمنتصف عمود
        if (n < 3) n = 3;
        final float used = n * step - (GAP_DP * density);
        final float x0 = (w - used) / 2f;
        final float cy = h / 2f;
        final float maxH = h - 2f * 7f * density;
        final float minH = barW;           // أدنى ارتفاع: نقطة مدوّرة
        final float mid = (n - 1) / 2f;
        final float radius = barW / 2f;

        for (int i = 0; i < n; i++) {
            final float d = mid == 0f ? 0f : Math.abs(i - mid) / mid;      // 0 منتصف .. 1 طرف
            final float env = histAt(d);
            // تنفّس هادئ يسري عبر الأعمدة عند الصمت
            final float idle = 0.06f + 0.05f * (float) Math.sin(clock * 2.6f - i * 0.5f);
            // تغيّر عضوي بسيط لكل عمود حتى لا تتحرك الأعمدة بتطابق آلي
            final float organic = 0.74f + 0.26f * (float) Math.sin(phase * 1.25f + i * 0.72f);
            final float taper = 1f - 0.82f * (float) Math.pow(d, 2.2);       // تضييق عند الطرفين
            float f = (idle + (1f - idle) * env * organic) * taper * (0.35f + 0.65f * dim);
            f = Math.max(0f, Math.min(1f, f));
            final float bh = minH + (maxH - minH) * f;

            final float x = x0 + i * step;
            bar.set(x, cy - bh / 2f, x + barW, cy + bh / 2f);
            barPaint.setAlpha((int) (255 * (0.55f + 0.45f * dim)));
            canvas.drawRoundRect(bar, radius, radius, barPaint);
        }

        canvas.drawRoundRect(rect, r, r, borderPaint);
    }
}
