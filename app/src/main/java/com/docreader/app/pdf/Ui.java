package com.docreader.app.pdf;

import android.content.Context;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

/** أدوات واجهة صغيرة مشتركة بين الشاشات: تفاعل لمسي، حركات دخول/خروج، ومنع الضغط المزدوج. */
public final class Ui {

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // =====================================================================
    // منحنيات الحركة الموحّدة (Material motion): كل حركات التطبيق تستخدم نفس
    // المنحنيات كي يشعر المستخدم بإيقاع واحد متناسق بدل حركات متفرقة.
    // =====================================================================

    /** الحركة القياسية: بداية سريعة ونهاية ناعمة. */
    private static final Interpolator STANDARD = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /** للعناصر الداخلة إلى الشاشة: تتباطأ عند الوصول. */
    private static final Interpolator ENTER = new PathInterpolator(0f, 0f, 0.2f, 1f);
    /** للعناصر الخارجة من الشاشة: تتسارع أثناء المغادرة. */
    private static final Interpolator EXIT = new PathInterpolator(0.4f, 0f, 1f, 1f);

    // =====================================================================
    // تفاعل لمسي (press feedback) موحّد لأي عنصر قابل للضغط.
    // التحسين: مقدار التصغير يتناسب عكسيًا مع حجم العنصر - الأزرار الصغيرة
    // (أيقونات/نجوم) تصغر حتى 6% لتشعر بالضغط، أما البطاقات العريضة فتصغر
    // نحو 2-3% فقط، فلا "ترتجّ" ولا تبدو مبالغًا فيها (كان التصغير ثابتًا
    // 6% لكل شيء حتى البطاقات الكاملة العرض). الرجوع بارتداد خفيف بدل
    // الارتداد القوي القديم الذي كان يظهر كاهتزاز.
    // =====================================================================

    private static final float MIN_PRESS_SCALE = 0.94f;
    private static final float MAX_PRESS_SCALE = 0.98f;
    private static final long PRESS_DOWN_DURATION = 100;
    private static final long PRESS_UP_DURATION = 240;

    /** يضيف حركة "ضغط" بصرية خفيفة (تصغير/تكبير) لأي View قابل للنقر، من
     *  غير ما يمسّ أي OnClickListener مُركّب عليه سواء قبل أو بعد النداء. */
    public static void applyPressFeedback(final View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    float target = pressScaleFor(v);
                    v.animate()
                            .scaleX(target)
                            .scaleY(target)
                            .setDuration(PRESS_DOWN_DURATION)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(PRESS_UP_DURATION)
                            .setInterpolator(new OvershootInterpolator(1.6f))
                            .start();
                    break;
                default:
                    break;
            }
            // نرجّع false دايمًا عشان الحدث يكمّل مساره الطبيعي لأي
            // OnClickListener/OnLongClickListener مركّب على نفس الـ View.
            return false;
        });
    }

    /** مقدار التصغير المناسب لحجم العنصر (الكبير يصغر أقل). */
    private static float pressScaleFor(View v) {
        int size = Math.max(v.getWidth(), v.getHeight());
        if (size <= 0) return MIN_PRESS_SCALE;
        float shrinkPx = dp(v.getContext(), 10);
        float scale = 1f - (shrinkPx / size);
        if (scale < MIN_PRESS_SCALE) scale = MIN_PRESS_SCALE;
        if (scale > MAX_PRESS_SCALE) scale = MAX_PRESS_SCALE;
        return scale;
    }

    /** نبضة صغيرة (pop) تُستخدم عند تبديل حالة (زي تفعيل نجمة المفضلة)
     *  لإبراز التغيير بصريًا بدل ما يتغير الأيقونة فجأة بلا أي حركة،
     *  مع اهتزاز لمسي خفيف (يحترم إعدادات الجهاز). */
    public static void popAnimation(final View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.6f);
        view.setScaleY(0.6f);
        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(new OvershootInterpolator(4f))
                .start();
        tick(view);
    }

    /** اهتزاز لمسي خفيف جدًا عند تبديل حالة (يحترم إعداد "ردود الفعل اللمسية" بالنظام). */
    public static void tick(View view) {
        if (view == null) return;
        try {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        } catch (Exception ignored) {
            // بعض الأجهزة قد ترفض الاهتزاز - الحركة البصرية كافية.
        }
    }

    // =====================================================================
    // منع الضغط المزدوج: الضغط مرتين بسرعة على بطاقة/زر تنقّل كان يفتح
    // نفس الشاشة مرتين (شاشتان فوق بعض + حركة انتقال متداخلة) وهو أوضح
    // سبب لإحساس "التقطّع". نتجاهل أي نقرة تالية خلال فترة قصيرة.
    // =====================================================================

    private static final long CLICK_GUARD_MS = 500;
    private static long lastGuardedClickAt = 0;

    /** يرجّع true إذا كانت هذه النقرة تكرارًا سريعًا يجب تجاهله. */
    public static boolean isDoubleClick() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastGuardedClickAt < CLICK_GUARD_MS) return true;
        lastGuardedClickAt = now;
        return false;
    }

    // =====================================================================
    // إظهار/إخفاء ناعم: بدل تبديل setVisibility المفاجئ الذي يجعل المحتوى
    // "يقفز"، نُظهر العنصر بتلاشي مع صعود بسيط، ونخفيه بتلاشي سريع.
    // =====================================================================

    /** يُظهر العنصر بتلاشي مع صعود بسيط (8dp). */
    public static void fadeIn(final View view, long durationMs) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(view.getContext(), 8));
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(durationMs)
                .setInterpolator(ENTER)
                .start();
    }

    /** يُظهر العنصر بتلاشي فقط (بدون حركة عمودية) - مناسب للعناصر الكبيرة. */
    public static void fadeInPlain(final View view, long durationMs) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(0f);
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(1f)
                .setDuration(durationMs)
                .setInterpolator(STANDARD)
                .start();
    }

    /** يُخفي العنصر (GONE) بعد تلاشي سريع. */
    public static void fadeOut(final View view, long durationMs) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        view.animate().cancel();
        view.animate()
                .alpha(0f)
                .setDuration(durationMs)
                .setInterpolator(EXIT)
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                    view.setTranslationY(0f);
                })
                .start();
    }
}
