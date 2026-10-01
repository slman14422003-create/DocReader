package com.docreader.app.pdf;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * القراءة الصوتية لملف PDF - مجانية بالكامل، بمحرّكين:
 *
 *  1) صوت عصبي أونلاين (الافتراضي): أصوات Microsoft Neural (عربية بعدة لهجات + إنجليزي + فرنسي + تركي)
 *     عبر EdgeTtsClient - جودة قريبة جدًا من الصوت البشري، بدون مفتاح ولا حساب. الجمل التالية
 *     تُجهَّز مسبقًا أثناء نطق الحالية فلا يوجد فراغ بين الجمل.
 *  2) صوت الجهاز (android.speech.tts.TextToSpeech): يعمل بدون إنترنت، وهو الاحتياطي التلقائي
 *     لو فشل الصوت الأونلاين.
 *
 * تسلسل القراءة: جملة واحدة في كل مرة (مقطع = Chunk)، وعند انتهائها ننتقل للتي بعدها. كل عملية نطق
 * تحمل "رمزًا" (speakToken) وأي ردّ متأخر أو مكرّر من محرك سابق يُتجاهل تمامًا - وهذا يمنع إعادة
 * قراءة السطر التالي. ولا نطلب تركيز الصوت (AudioFocus) مع صوت الجهاز لأن محرك النطق يديره بنفسه؛
 * تنافس التطبيق مع المحرك كان يسبّب إيقافًا/استئنافًا وهميًا بين الجمل وبالتالي إعادة القراءة.
 *
 * كل ردود Listener تصل على الخيط الرئيسي.
 */
final class PdfSpeaker {

    enum State {IDLE, LOADING, PLAYING, PAUSED}

    interface Listener {
        void onStateChanged(State state);

        /** بدأت قراءة صفحة جديدة (النص جاهز). */
        void onPageStarted(int page, PdfSpeechText.PageText text);

        /** الكلمة/الجملة الجاري نطقها الآن. wordIndex داخل text.words. */
        void onSpeaking(int page, PdfSpeechText.PageText text, int chunkIndex, int wordIndex);

        /** انتهى الملف كله. */
        void onFinished();

        void onError(String message);

        /** لا يوجد صوت مثبّت للغة (lang: ar/en/fr/tr) - يعرض الواجهة خيار التثبيت. */
        void onVoiceMissing(String lang);

        /** محرك النطق نفسه غير متاح/غير مثبّت. */
        void onEngineUnavailable();

        /** رسالة حالة أثناء التجهيز (تنزيل نموذج التعرّف الضوئي / التعرّف على صفحة ممسوحة). */
        default void onStatus(String message) {
        }
    }

    static final class VoiceOption {
        final String name;
        final String label;

        VoiceOption(String name, String label) {
            this.name = name;
            this.label = label;
        }
    }

    private static final String PREFS = "pdf_tts";
    private static final String KEY_RATE = "rate";
    private static final String KEY_ENGINE = "engine"; // cloud | device
    private static final String KEY_PROFILE = "profile";   // 0 طبيعي، 1 واضح (افتراضي)، 2 دراسة
    private static final String KEY_PITCH = "pitch_idx";
    private static final String KEY_MIXED = "mixed_voices";
    private static final String KEY_ASSIST = "arabic_assist";
    private static final String KEY_ACRO = "spell_acronyms";
    private static final String KEY_LETTERS = "letter_names";   // نطق الحروف المنفردة باسمها
    private static final String KEY_LEXICON = "user_lexicon";
    private static final String KEY_EQ = "voice_eq";          // صفاء الصوت (مؤثر Equalizer)
    private static final String KEY_GAIN = "voice_gain";      // تعزيز مستوى الصوت (LoudnessEnhancer)
    private static final String KEY_TAA = "taa_mode";         // نطق التاء المربوطة
    private static final String KEY_TAAFIX = "taa_fix";       // تصحيح إملاء ة/ه
    private static final String KEY_NOIRAB = "no_irab";       // قراءة بلا إعراب
    private static final String KEY_DEV_ENGINE = "dev_engine";        // حزمة محرك النطق المحلي (null = افتراضي النظام)
    private static final String KEY_DEV_OFFLINE = "dev_offline_only"; // أصوات الجهاز التي لا تحتاج إنترنت فقط
    private static final String KEY_DEV_FALLBACK = "dev_fallback";    // الرجوع التلقائي لصوت الجهاز عند تعطل العصبي
    private static final String KEY_DEV_SPEED = "dev_speed_idx";      // معايرة سرعة صوت الجهاز عن العصبي
    private static final String KEY_LEARN = "voice_self_learn";       // التعلّم الذاتي للنموذج الصوتي المحلي
    /** الفهرس 0 = تلقائي: النموذج المحلي يتعلّم إيقاع العصبي ويطابقه؛ الباقي معايرة يدوية ثابتة. */
    private static final String[] DEV_SPEED_LABELS = {"تلقائي (يتعلّم من العصبي)", "بدون معايرة", "أبطأ 10%", "أبطأ 20%", "أسرع 10%", "أسرع 20%"};
    private static final float[] DEV_SPEED_MUL = {1f, 1f, 0.9f, 0.8f, 1.1f, 1.2f};
    private static final String[] EQ_LABELS = {"طبيعي", "صافٍ (يُبرز الحروف)", "دافئ", "عميق"};
    private static final String[] GAIN_LABELS = {"عادي", "+3 ديسيبل", "+6 ديسيبل"};
    private static final int[] GAIN_MB = {0, 300, 600};
    private static final String[] TAA_LABELS = {"تلقائي (يقرّر المحرك)", "فصيح: تاء داخل الجملة وهاء عند الوقف", "هاء خفيفة دائمًا"};
    /** منحنيات التعديل (تردد Hz، تغيير dB): تُستنبط منها قيم نطاقات الـ Equalizer أيًّا كان عددها في الجهاز. */
    private static final float[][][] EQ_CURVES = {
            null,
            // صافٍ: يخفّف الدمدمة السفلية ويرفع نطاق الوضوح (2-5 كيلوهرتز) حيث تتميّز الحروف
            {{60, -5}, {120, -4}, {250, -2}, {500, 0}, {1000, 1}, {2000, 2.5f}, {3500, 4}, {6000, 3}, {10000, 1}, {16000, 0}},
            // دافئ: صوت أنعم وأثقل قليلًا
            {{60, 3}, {150, 3}, {300, 2}, {1000, 0}, {3000, -1}, {6000, -2.5f}, {12000, -3}},
            // عميق: قاع أقوى وحدّة أقل
            {{60, 5}, {120, 4}, {250, 2}, {500, 0}, {2000, -1}, {6000, -1.5f}, {12000, -2}},
    };
    private static final int[] PITCH_HZ = {0, 8, 16, -8, -16};
    private static final String[] PITCH_LABELS = {"عادية", "أعلى قليلًا", "أعلى", "أخفض قليلًا", "أخفض"};
    private static final String[] PROFILE_LABELS = {"طبيعي", "واضح (مُوصى به)", "دراسة (بطيء مع وقفات)"};
    private static final int POLL_MS = 40;
    /** تأخير الصوت الفعلي عن موضع التشغيل (مخزن المخرج/البلوتوث): نؤخّر التظليل بمقداره حتى لا يسبق الكلمة. */
    private static final int CLOUD_LAG_MS = 190;
    private static final int DEVICE_LAG_MS = 140;

    /** أصوات الأونلاين: {اللغة, اسم الصوت, الوصف}. الأول لكل لغة هو الافتراضي. */
    private static final String[][] CLOUD_VOICES = {
            {"ar", "ar-SA-ZariyahNeural", "زارية · سعودية · أنثى"},
            {"ar", "ar-SA-HamedNeural", "حامد · سعودي · ذكر"},
            {"ar", "ar-SY-AmanyNeural", "أماني · سورية · أنثى"},
            {"ar", "ar-SY-LaithNeural", "ليث · سوري · ذكر"},
            {"ar", "ar-EG-SalmaNeural", "سلمى · مصرية · أنثى"},
            {"ar", "ar-EG-ShakirNeural", "شاكر · مصري · ذكر"},
            {"ar", "ar-JO-SanaNeural", "سناء · أردنية · أنثى"},
            {"ar", "ar-JO-TaimNeural", "تيم · أردني · ذكر"},
            {"ar", "ar-LB-LaylaNeural", "ليلى · لبنانية · أنثى"},
            {"ar", "ar-LB-RamiNeural", "رامي · لبناني · ذكر"},
            {"ar", "ar-AE-FatimaNeural", "فاطمة · إماراتية · أنثى"},
            {"ar", "ar-AE-HamdanNeural", "حمدان · إماراتي · ذكر"},
            {"ar", "ar-QA-AmalNeural", "أمل · قطرية · أنثى"},
            {"ar", "ar-QA-MoazNeural", "معاذ · قطري · ذكر"},
            {"ar", "ar-KW-NouraNeural", "نورة · كويتية · أنثى"},
            {"ar", "ar-KW-FahedNeural", "فهد · كويتي · ذكر"},
            {"en", "en-US-EmmaMultilingualNeural", "Emma · أمريكية · أنثى"},
            {"en", "en-US-AndrewMultilingualNeural", "Andrew · أمريكي · ذكر"},
            {"en", "en-US-AvaMultilingualNeural", "Ava · أمريكية · أنثى"},
            {"en", "en-US-BrianMultilingualNeural", "Brian · أمريكي · ذكر"},
            {"en", "en-US-AriaNeural", "Aria · أمريكية · أنثى"},
            {"en", "en-US-GuyNeural", "Guy · أمريكي · ذكر"},
            {"en", "en-GB-SoniaNeural", "Sonia · بريطانية · أنثى"},
            {"en", "en-GB-RyanNeural", "Ryan · بريطاني · ذكر"},
            {"fr", "fr-FR-VivienneMultilingualNeural", "Vivienne · أنثى"},
            {"fr", "fr-FR-RemyMultilingualNeural", "Rémy · ذكر"},
            {"fr", "fr-FR-DeniseNeural", "Denise · أنثى"},
            {"fr", "fr-FR-HenriNeural", "Henri · ذكر"},
            {"tr", "tr-TR-EmelNeural", "Emel · أنثى"},
            {"tr", "tr-TR-AhmetNeural", "Ahmet · ذكر"},
    };

    /** صوت جملة واحدة جاهز للتشغيل (أو null عند الفشل). */
    private static final class CloudAudio {
        final byte[] data;
        final int[] wordMs;
        final int[] wordChar;
        /** النص المنطوق الفعلي (بعد تنظيفه) وخريطته إلى مواضع النص الأصلي - لسلامة التظليل. */
        final SpeechPrep.Spoken spoken;
        /** غلاف علو الصوت (0..1 لكل AudioEnvelope.WINDOW_MS) لتحريك موجة المشغّل؛ يُحسب في خيط خلفي. */
        volatile float[] env;
        volatile boolean envBusy;

        CloudAudio(EdgeTtsClient.Result r, SpeechPrep.Spoken spoken) {
            this.data = r.audio;
            this.wordMs = r.wordMs;
            this.wordChar = r.wordChar;
            this.spoken = spoken;
        }
    }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService synthPool = Executors.newFixedThreadPool(3);
    private final ExecutorService envPool = Executors.newSingleThreadExecutor();
    // مؤثرات الصوت: جلسة صوت واحدة ثابتة نربطها بكل مشغّل (عصبي/جهاز/تجربة) فيسري عليها المعادل والتعزيز
    private int fxSession = 0;
    private boolean fxInit = false;
    private android.media.audiofx.Equalizer eq;
    private android.media.audiofx.LoudnessEnhancer loud;
    private MediaPlayer previewPlayer;
    private File previewFile;
    private int previewGen = 0;
    /** آخر لحظة (uptime) بدأت فيها كلمة على صوت الجهاز - لتحريك الموجة تقديريًا (لا نصل لعينات صوت الجهاز). */
    private volatile long lastWordAt = 0L;
    private final SharedPreferences prefs;
    private final AudioManager audio;
    private final File cacheDir;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsFailed = false;
    private Runnable pendingAfterInit = null;

    // المصدر (يُستخدم من خيط io فقط)
    private PdfSpeechText.Source source;
    private File sourceFile;
    private volatile int pageCount = 0;
    private volatile PdfSpeechText.PageText prefetched;

    // حالة التشغيل (الخيط الرئيسي فقط)
    private State state = State.IDLE;
    private volatile int session = 0;   // تحميل الصفحات
    private int speakToken = 0;         // كل عملية نطق (مقطع) لها رمز؛ الردود القديمة تُهمَل
    private int currentPage = -1;
    private PdfSpeechText.PageText currentText;
    private int currentChunk = 0;
    private int currentWord = -1;
    private int resumeChunk = 0;
    private int resumeShift = 0;
    private int emptyStreak = 0;
    private boolean anyText = false;
    private float rate;
    private String lastAppliedLang = null;
    private int deviceErrStreak = 0;
    private final Set<String> notifiedMissing = new HashSet<>();
    private final Set<String> badVoices = new HashSet<>();
    private final Map<String, Voice> usedVoice = new HashMap<>();

    // الصوت الأونلاين
    private boolean cloudBroken = false;   // فشل مؤخرًا -> نستخدم صوت الجهاز مؤقتًا ثم نعيد تجربة العصبي
    private long cloudBrokenAt = 0L;
    private boolean cloudErrorShown = false; // نافذة الخطأ الكاملة تظهر مرة واحدة لكل جلسة قراءة
    /** بعد فشل الصوت العصبي نعود لتجربته بعد هذه المدة بدل الاستسلام لبقية الملف (أعطال الخادم عابرة عادةً). */
    private static final long CLOUD_RETRY_MS = 60_000L;
    private boolean cloudActive = false;   // المقطع الحالي يُنطق عبر الأونلاين
    private int cloudGen = 0;
    private int cloudPlayErrStreak = 0;
    private final Map<String, CloudAudio> cloudReady = new HashMap<>();
    private final Set<String> cloudPending = new HashSet<>();
    private final Set<String> cloudRetried = new HashSet<>();
    private final Set<String> badCloudVoices = new HashSet<>(); // أصوات فشلت في هذه الجلسة (نتجاوزها لصوت بديل)
    private int cloudVoiceSwitches = 0;
    /** النص المنطوق على صوت الجهاز (بعد التنظيف) وخريطته، لربط onRangeStart بالكلمة الأصلية. */
    private volatile SpeechPrep.Spoken deviceSpoken = null;
    // مشغّل الجملة التالية: يُجهَّز (prepare) أثناء نطق الحالية فيبدأ فور انتهائها بدون فجوة
    private MediaPlayer nextPlayer = null;
    private File nextFile = null;
    private String nextKey = null;
    private CloudAudio nextAudio = null;
    private boolean nextPrepared = false;
    private volatile int deviceSpokenToken = -1;
    private String playerDiag = "";   // آخر خطأ من MediaPlayer (للتشخيص)
    private boolean playerFdMode = false; // المحاولة الثانية: تشغيل عبر FileDescriptor
    private String awaitingKey = null;
    private int awaitingToken = 0;
    private int awaitingChunk = 0;
    private MediaPlayer player;
    private File playerFile;
    private CloudAudio playerAudio;
    private boolean playerPrepared = false;
    private boolean playerPaused = false;

    // تركيز الصوت (للصوت الأونلاين فقط)
    private AudioFocusRequest focusRequest;
    private boolean focusHeld = false;
    private boolean resumeOnFocusGain = false;

    PdfSpeaker(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            if ("cloud".equals(prefs.getString(KEY_ENGINE, "cloud"))) EdgeTtsClient.warmUp(); // اتصال دافئ قبل أول جملة
        } catch (Throwable ignored) {
        }
        this.rate = Math.max(0.5f, Math.min(2.5f, prefs.getFloat(KEY_RATE, 1.0f)));
        this.cacheDir = new File(app.getCacheDir(), "tts_cloud");
        cleanCacheDir();
        SpeechPrep.setArabicAssist(isArabicAssist());
        SpeechPrep.setSpellAcronyms(isSpellAcronyms());
        SpeechPrep.setLetterNames(isLetterNames());
        SpeechPrep.setUserLexicon(getUserLexicon());
        SpeechPrep.setTaaMode(getTaaMode());
        SpeechPrep.setTaaTypoFix(isTaaFix());
        SpeechPrep.setNoIrab(isNoIrab());
        try {
            SpeechLearner.init(app.getFilesDir()); // ذاكرة النطق المتعلَّمة
        } catch (Throwable ignored) {
        }
        try {
            LocalVoiceModel.init(app.getFilesDir()); // النموذج الصوتي المحلي (إيقاع/صحة الأصوات)
        } catch (Throwable ignored) {
        }
        // قاموس التشكيل: يبدأ تحميله الآن (مرة واحدة وفي الخلفية) كي يكون جاهزًا قبل أول جملة تُنطق؛
        // لو كان التطبيق بدأه من ClinicalMasterApp فهذا الاستدعاء ينتظر انتهاءه ولا يكرّر العمل.
        if (!TashkeelDict.isReady()) {
            final Context appCtx = app;
            new Thread(() -> TashkeelDict.load(appCtx), "tashkeel-dict-load").start();
        }
        initTts();
    }

    // ------------------------------------------------------------------ محرك الجهاز

    /** جيل محرك النطق: ردود تهيئة محرك سابق (بعد تبديل المحرك) تُهمَل. */
    private int ttsGen = 0;

    private void initTts() {
        initTts(prefs.getString(KEY_DEV_ENGINE, null), false);
    }

    private void initTts(final String enginePkg, final boolean retriedDefault) {
        final int gen = ++ttsGen;
        final TextToSpeech.OnInitListener onInit = status -> main.post(() -> {
            if (gen != ttsGen) return;
            if (status != TextToSpeech.SUCCESS) {
                try {
                    if (tts != null) tts.shutdown();
                } catch (Throwable ignored) {
                }
                tts = null;
                if (enginePkg != null && !retriedDefault) {
                    // المحرك المختار لم يعد يعمل (حُذف/عُطّل): نرجع لمحرك النظام الافتراضي بدل تعطيل القراءة
                    prefs.edit().remove(KEY_DEV_ENGINE).apply();
                    usedVoice.clear();
                    badVoices.clear();
                    lastAppliedLang = null;
                    initTts(null, true);
                    return;
                }
                ttsFailed = true;
                if (pendingAfterInit != null) {
                    pendingAfterInit = null;
                    setState(State.IDLE);
                    listener.onEngineUnavailable();
                }
                return;
            }
            ttsReady = true;
            ttsFailed = false;
            try {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                tts.setSpeechRate(deviceRate());
                tts.setPitch(devicePitch());
            } catch (Throwable ignored) {
            }
            tts.setOnUtteranceProgressListener(progressListener);
            Runnable r = pendingAfterInit;
            pendingAfterInit = null;
            if (r != null) r.run();
        });
        try {
            tts = enginePkg == null ? new TextToSpeech(app, onInit) : new TextToSpeech(app, onInit, enginePkg);
        } catch (Throwable t) {
            tts = null;
            ttsFailed = true;
        }
    }

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            final int[] id = parseId(utteranceId);
            if (id == null) return;
            main.post(() -> handleStart(id));
        }

        @Override
        public void onDone(String utteranceId) {
            final int[] id = parseId(utteranceId);
            if (id == null) return;
            main.post(() -> handleDone(id));
        }

        @Override
        public void onError(String utteranceId) {
            onError(utteranceId, TextToSpeech.ERROR);
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            final int[] id = parseId(utteranceId);
            if (id == null) return;
            main.post(() -> handleError(id, errorCode));
        }

        @Override
        public void onRangeStart(String utteranceId, int start, int end, int frame) {
            final int[] id = parseId(utteranceId);
            if (id == null) return;
            main.postDelayed(() -> handleRange(id, start, end), DEVICE_LAG_MS);
        }
    };

    private static String makeId(int token, int page, int chunk, int shift) {
        return token + ":" + page + ":" + chunk + ":" + shift;
    }

    /** [token, page, chunk, shift] */
    private static int[] parseId(String id) {
        if (id == null) return null;
        try {
            String[] p = id.split(":");
            if (p.length != 4) return null;
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean stale(int[] id) {
        return id[0] != speakToken || id[1] != currentPage || currentText == null || cloudActive;
    }

    private void handleStart(int[] id) {
        if (stale(id)) return;
        if (id[2] < 0 || id[2] >= currentText.chunks.size()) return;
        deviceErrStreak = 0;
        lastWordAt = SystemClock.uptimeMillis();
        if (devMeasToken == id[0] && id[3] == 0) devMeasAt = lastWordAt; // نقيس المقاطع الكاملة فقط (لا المستأنفة)
        PdfSpeechText.Chunk c = currentText.chunks.get(id[2]);
        currentChunk = id[2];
        currentWord = Math.max(c.firstWord, Math.min(c.lastWord, currentText.wordAtOffset(c.start + id[3])));
        if (state != State.PLAYING) setState(State.PLAYING);
        listener.onSpeaking(currentPage, currentText, currentChunk, currentWord);
    }

    private void handleRange(int[] id, int start, int end) {
        if (stale(id)) return;
        if (id[2] < 0 || id[2] >= currentText.chunks.size()) return;
        if (end - start > 80) return; // بعض المحركات تعطي نطاق الجملة كلها - نتجاهله
        lastWordAt = SystemClock.uptimeMillis();
        PdfSpeechText.Chunk c = currentText.chunks.get(id[2]);
        int off = start;
        SpeechPrep.Spoken ds = deviceSpoken;
        if (ds != null && deviceSpokenToken == id[0]) off = ds.toOriginal(start);
        int w = currentText.wordAtOffset(c.start + id[3] + off);
        w = Math.max(c.firstWord, Math.min(c.lastWord, w));
        if (w == currentWord && id[2] == currentChunk) return;
        currentChunk = id[2];
        currentWord = w;
        listener.onSpeaking(currentPage, currentText, currentChunk, currentWord);
    }

    private void handleDone(int[] id) {
        if (stale(id)) return;
        if (id[2] != currentChunk) return;
        if (isVoiceLearning() && devMeasToken == id[0] && devMeasAt > 0L && devMeasLang != null) {
            try {
                LocalVoiceModel.learnDevice(enginePkg(), devMeasVoice, devMeasLang, devMeasChars,
                        SystemClock.uptimeMillis() - devMeasAt, devMeasRate);
                LocalVoiceModel.noteVoice(enginePkg(), devMeasVoice, true);
            } catch (Throwable ignored) {
            }
            devMeasAt = 0L;
        }
        advance();
    }

    private void handleError(int[] id, int code) {
        if (stale(id)) return;
        // صوت شبكة فشل (غالبًا بدون إنترنت): نعتمد صوتًا محليًا ونكمل من نفس المقطع.
        String lang = id[2] >= 0 && id[2] < currentText.chunks.size()
                ? currentText.chunks.get(id[2]).lang : null;
        Voice v = lang != null ? usedVoice.get(lang) : null;
        if (v != null && isVoiceLearning()) LocalVoiceModel.noteVoice(enginePkg(), v.getName(), false);
        if (v != null && v.isNetworkConnectionRequired() && !badVoices.contains(v.getName())) {
            badVoices.add(v.getName());
            usedVoice.remove(lang);
            lastAppliedLang = null;
            speakChunk(id[2], id[3]);
            return;
        }
        deviceErrStreak++;
        if (deviceErrStreak >= 3) {
            deviceErrStreak = 0;
            pause();
            listener.onError("تعذّر نطق النص (رمز الخطأ " + code + "). جرّب تغيير الصوت أو تثبيت بيانات الصوت من إعدادات محرك النطق.");
            return;
        }
        advance(); // نتخطى المقطع المشكل ونكمل
    }

    // ------------------------------------------------------------------ التحكم العام

    State getState() {
        return state;
    }

    // ------------------------------------------------------------------ مستوى الصوت (لموجة المشغّل)

    /**
     * مستوى الصوت الحالي 0..1 لرسم الموجة. للصوت العصبي: من غلاف الصوت الحقيقي عند موضع التشغيل
     * الحالي (نفس تعويض التأخير المستعمل لتظليل الكلمة). لصوت الجهاز (لا نصل لعيّناته): نبضة
     * تقديرية مع كل كلمة منطوقة. يُستدعى من الخيط الرئيسي فقط.
     */
    float getLevel() {
        if (state != State.PLAYING) return 0f;
        final MediaPlayer p = player;
        if (cloudActive && (p == null || !playerPrepared || playerPaused)) {
            return 0.05f; // فجوة بين جملتين (تجهيز الصوت التالي): موجة هادئة
        }
        if (cloudActive) {
            final CloudAudio a = playerAudio;
            final float[] env = a != null ? a.env : null;
            if (env != null && env.length > 1) {
                int pos;
                try {
                    pos = p.getCurrentPosition();
                } catch (Throwable t) {
                    return 0f;
                }
                pos -= (int) (CLOUD_LAG_MS * Math.max(0.5f, rate));
                if (pos < 0) return 0f;
                float f = pos / (float) AudioEnvelope.WINDOW_MS;
                int i = (int) f;
                if (i >= env.length) return 0f;
                float v0 = env[i];
                float v1 = i + 1 < env.length ? env[i + 1] : v0;
                return v0 + (v1 - v0) * (f - i);
            }
        }
        return simulatedLevel(SystemClock.uptimeMillis());
    }

    private float simulatedLevel(long now) {
        if (cloudActive) { // الغلاف لم يجهز بعد (أو تعذّر فكّه): حركة كلام تقديرية
            double t = now / 1000.0;
            double v = 0.36 + 0.24 * Math.sin(t * 11.0) + 0.14 * Math.sin(t * 27.0 + 1.3);
            return (float) Math.max(0.08, Math.min(1.0, v));
        }
        long since = now - lastWordAt;
        if (lastWordAt > 0L && since >= 0L && since < 1200L) {
            float pulse = (float) Math.exp(-since / 170.0);
            float wob = 0.85f + 0.15f * (float) Math.sin(now / 43.0);
            return Math.min(1f, (0.22f + 0.62f * pulse) * wob);
        }
        return 0.05f;
    }

    /** يحسب غلاف علو الصوت للجملة (مرة واحدة) في خيط خلفي. */
    private void ensureEnvelope(final CloudAudio a) {
        if (a == null || a.env != null || a.envBusy || a.data == null) return;
        a.envBusy = true;
        try {
            envPool.execute(() -> {
                try {
                    a.env = AudioEnvelope.fromEncoded(a.data);
                } catch (Throwable ignored) {
                }
                a.envBusy = false;
            });
        } catch (RejectedExecutionException e) {
            a.envBusy = false;
        }
    }

    boolean isActive() {
        return state != State.IDLE;
    }

    int getCurrentPage() {
        return currentPage;
    }

    int getPageCount() {
        return pageCount;
    }

    float getRate() {
        return rate;
    }

    /** يبدأ (أو يعيد) القراءة من صفحة معيّنة (0-based) من الملف. */
    void play(File file, int startPage) {
        Runnable go = () -> {
            session++;
            hardStopOutputs();
            resetCloud();
            cloudBroken = false;
            cloudErrorShown = false;
            badCloudVoices.clear();
            cloudVoiceSwitches = 0;
            deviceErrStreak = 0;
            notifiedMissing.clear();
            emptyStreak = 0;
            anyText = false;
            lastAppliedLang = null;
            currentText = null;
            currentWord = -1;
            setState(State.LOADING);
            final int sess = session;
            PdfOcr.setStatusSink(msg -> main.post(() -> {
                if (sess == session) listener.onStatus(msg);
            }));
            io.execute(() -> {
                try {
                    if (source == null || sourceFile == null || !sourceFile.equals(file)) {
                        if (source != null) source.close();
                        source = PdfSpeechText.Source.open(app, file);
                        sourceFile = file;
                        prefetched = null;
                    }
                    pageCount = source.pageCount();
                } catch (Throwable t) {
                    main.post(() -> {
                        if (sess != session) return;
                        setState(State.IDLE);
                        abandonFocus();
                        listener.onError("تعذّر تجهيز نص الملف للقراءة.");
                    });
                    return;
                }
                // قاموس التشكيل يُحمَّل في الخلفية؛ ننتظره (على خيط io لا الرئيسي) قبل تجهيز أول جملة،
                // وإلا تُنطق الجمل الأولى (وتُخزَّن أصواتها المجهّزة مسبقًا) بلا تشكيل القاموس.
                try {
                    if (!TashkeelDict.isReady()) TashkeelDict.awaitReady(4000);
                } catch (Throwable ignored) {
                }
                loadPageOnIo(startPage, sess, 0, 0);
            });
        };
        if (isCloudEngine()) go.run();
        else runWhenReady(go);
    }

    void pause() {
        pauseInternal(true);
    }

    private void pauseInternal(boolean abandon) {
        if (state != State.PLAYING && state != State.LOADING) return;
        captureResumePoint();
        if (cloudActive && player != null && playerPrepared && !playerPaused) {
            try {
                player.pause();
                playerPaused = true;
            } catch (Throwable t) {
                releasePlayer();
            }
            main.removeCallbacks(poll);
        } else {
            hardStopOutputs();
        }
        if (abandon) abandonFocus();
        setState(State.PAUSED);
    }

    void resume() {
        if (state != State.PAUSED) return;
        if (player != null && playerPrepared && playerPaused) {
            requestFocus();
            playerPaused = false;
            try {
                player.start();
                applySpeed(player);
            } catch (Throwable t) {
                releasePlayer();
                setState(State.LOADING);
                speakChunk(currentChunk, 0);
                return;
            }
            setState(State.PLAYING);
            startPoll();
            return;
        }
        if (currentText == null) {
            // لم تبدأ صفحة بعد - نعيد التحميل
            if (sourceFile != null) play(sourceFile, Math.max(0, currentPage));
            return;
        }
        lastAppliedLang = null;
        setState(State.LOADING);
        speakChunk(resumeChunk, resumeShift);
    }

    void togglePlayPause() {
        if (state == State.PLAYING || state == State.LOADING) pause();
        else if (state == State.PAUSED) resume();
    }

    void stop() {
        session++;
        pendingAfterInit = null;
        PdfOcr.cancelWarm();
        hardStopOutputs();
        resetCloud();
        abandonFocus();
        resumeOnFocusGain = false;
        currentText = null;
        currentPage = -1;
        currentWord = -1;
        setState(State.IDLE);
    }

    void nextPage() {
        if (sourceFile == null || state == State.IDLE) return;
        if (currentPage + 1 >= pageCount) return;
        boolean wasPaused = state == State.PAUSED;
        jumpToPage(currentPage + 1, wasPaused);
    }

    /** نص المقطع الجاري (الأصلي) - للتعلّم من سلوك الاستماع. */
    private String currentChunkRaw() {
        try {
            if (currentText == null || currentChunk < 0 || currentChunk >= currentText.chunks.size()) return null;
            PdfSpeechText.Chunk c = currentText.chunks.get(currentChunk);
            return currentText.text.substring(c.start, c.end);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** المستخدم يعلّم نطق كلمة (تُحفظ وتُطبَّق تلقائيًا في كل قراءة لاحقة). */
    void teachWord(String word, String spoken) {
        SpeechLearner.teach(word, spoken);
        onSpeechSettingChanged();
    }

    String learnerStats() {
        return SpeechLearner.stats() + "\n" + WordVerifier.stats() + "\n" + LocalVoiceModel.stats();
    }

    /** تقرير تغطية القاموس لنص الصفحة الحالية (كم كلمة وُجدت، وما أكثر الكلمات غير الموجودة) - للتشخيص. */
    String dictionaryReport() {
        PdfSpeechText.PageText t = currentText;
        if (t == null || t.text == null || t.text.isEmpty()) return "";
        return WordVerifier.analyze(t.text).toString();
    }

    void resetLearner() {
        SpeechLearner.resetAll();
        onSpeechSettingChanged();
    }

    void previousPage() {
        if (sourceFile == null || state == State.IDLE) return;
        try {
            SpeechLearner.noteRewind(currentChunkRaw()); // رجوع = إشارة أن المقطع لم يكن واضحًا
        } catch (Throwable ignored) {
        }
        boolean wasPaused = state == State.PAUSED;
        // لو تجاوزنا بداية الصفحة نعيدها من أولها، وإلا الصفحة السابقة
        int target = ((currentChunk > 0 || currentWord > 15) && currentPage >= 0) ? currentPage : Math.max(0, currentPage - 1);
        jumpToPage(target, wasPaused);
    }

    /** انتقال المستخدم لصفحة أخرى أثناء القراءة: نعيد تجهيز القراءة من تلك الصفحة. */
    void seekToPage(int page) {
        if (sourceFile == null || state == State.IDLE || pageCount <= 0) return;
        int target = Math.max(0, Math.min(pageCount - 1, page));
        if (target == currentPage && currentText != null) return;
        jumpToPage(target, state == State.PAUSED);
    }

    private void jumpToPage(int page, boolean stayPaused) {
        session++;
        hardStopOutputs();
        resetCloud();
        currentText = null;
        currentWord = -1;
        currentChunk = 0;
        resumeChunk = 0;
        resumeShift = 0;
        final int sess = session;
        if (stayPaused) {
            // نجهّز الصفحة ونبقى على الإيقاف المؤقت
            currentPage = page;
            io.execute(() -> {
                PdfSpeechText.PageText pt = takePage(page);
                main.post(() -> {
                    if (sess != session) return;
                    currentPage = page;
                    currentText = pt;
                    if (pt != null) listener.onPageStarted(page, pt);
                });
            });
            return;
        }
        setState(State.LOADING);
        io.execute(() -> loadPageOnIo(page, sess, 0, 0));
    }

    void setRate(float newRate) {
        rate = Math.max(0.5f, Math.min(2.5f, newRate));
        prefs.edit().putFloat(KEY_RATE, rate).apply();
        if (ttsReady) {
            try {
                tts.setSpeechRate(deviceRate());
            } catch (Throwable ignored) {
            }
        }
        if (player != null && playerPrepared && !playerPaused) {
            applySpeed(player);
        } else if (state == State.PLAYING && !cloudActive) {
            restartFromCurrentPoint();
        }
    }

    /** يعيد القراءة من الموضع الحالي (بعد تغيير السرعة/الصوت/المحرك). */
    private void restartFromCurrentPoint() {
        if (currentText == null || state == State.IDLE) return;
        captureResumePoint();
        hardStopOutputs();
        lastAppliedLang = null;
        if (state == State.PLAYING || state == State.LOADING) {
            setState(State.LOADING);
            speakChunk(resumeChunk, resumeShift);
        }
    }

    // ------------------------------------------------------------------ مرونة النطق (أسلوب، طبقة، خيارات، قاموس)

    int getProfile() {
        return Math.max(0, Math.min(2, prefs.getInt(KEY_PROFILE, 1)));
    }

    String getProfileLabel() {
        return PROFILE_LABELS[getProfile()];
    }

    void cycleProfile() {
        prefs.edit().putInt(KEY_PROFILE, (getProfile() + 1) % 3).apply();
        onSpeechSettingChanged();
    }

    int getPitchIndex() {
        return Math.max(0, Math.min(PITCH_HZ.length - 1, prefs.getInt(KEY_PITCH, 0)));
    }

    String getPitchLabel() {
        return PITCH_LABELS[getPitchIndex()];
    }

    void cyclePitch() {
        prefs.edit().putInt(KEY_PITCH, (getPitchIndex() + 1) % PITCH_HZ.length).apply();
        try {
            if (ttsReady) tts.setPitch(devicePitch());
        } catch (Throwable ignored) {
        }
        onSpeechSettingChanged();
    }

    private float devicePitch() {
        return Math.max(0.6f, Math.min(1.5f, 1f + PITCH_HZ[getPitchIndex()] / 40f));
    }

    /** تبديل الصوت تلقائيًا للكلمات الأجنبية داخل الجملة العربية (والعكس). */
    boolean isMixedVoices() {
        return prefs.getBoolean(KEY_MIXED, true);
    }

    void setMixedVoices(boolean v) {
        prefs.edit().putBoolean(KEY_MIXED, v).apply();
        onSpeechSettingChanged();
    }

    /** تشكيل ذكي (قاموس مصطلحات + وقف بالسكون للنص المشكول). */
    boolean isArabicAssist() {
        return prefs.getBoolean(KEY_ASSIST, true);
    }

    void setArabicAssist(boolean v) {
        prefs.edit().putBoolean(KEY_ASSIST, v).apply();
        SpeechPrep.setArabicAssist(v);
        onSpeechSettingChanged();
    }

    boolean isSpellAcronyms() {
        return prefs.getBoolean(KEY_ACRO, true);
    }

    void setSpellAcronyms(boolean v) {
        prefs.edit().putBoolean(KEY_ACRO, v).apply();
        SpeechPrep.setSpellAcronyms(v);
        onSpeechSettingChanged();
    }

    /** نطق الحروف المنفردة (أ) ب) ع.م النقطة س) باسم الحرف من قاموس الحروف. */
    boolean isLetterNames() {
        return prefs.getBoolean(KEY_LETTERS, true);
    }

    void setLetterNames(boolean v) {
        prefs.edit().putBoolean(KEY_LETTERS, v).apply();
        SpeechPrep.setLetterNames(v);
        onSpeechSettingChanged();
    }

    String getUserLexicon() {
        return prefs.getString(KEY_LEXICON, "");
    }

    void setUserLexicon(String text) {
        String t = text == null ? "" : text;
        prefs.edit().putString(KEY_LEXICON, t).apply();
        SpeechPrep.setUserLexicon(t);
        onSpeechSettingChanged();
    }

    // ---- نطق التاء المربوطة / تصحيح ة-ه / قراءة بلا إعراب

    int getTaaMode() {
        return Math.max(0, Math.min(2, prefs.getInt(KEY_TAA, 1)));
    }

    String getTaaLabel() {
        return TAA_LABELS[getTaaMode()];
    }

    void cycleTaaMode() {
        int m = (getTaaMode() + 1) % 3;
        prefs.edit().putInt(KEY_TAA, m).apply();
        SpeechPrep.setTaaMode(m);
        onSpeechSettingChanged();
    }

    boolean isTaaFix() {
        return prefs.getBoolean(KEY_TAAFIX, true);
    }

    void setTaaFix(boolean v) {
        prefs.edit().putBoolean(KEY_TAAFIX, v).apply();
        SpeechPrep.setTaaTypoFix(v);
        onSpeechSettingChanged();
    }

    boolean isNoIrab() {
        return prefs.getBoolean(KEY_NOIRAB, false);
    }

    void setNoIrab(boolean v) {
        prefs.edit().putBoolean(KEY_NOIRAB, v).apply();
        SpeechPrep.setNoIrab(v);
        onSpeechSettingChanged();
    }

    // ---- صفاء الصوت وتعزيزه (مؤثرات صوتية حقيقية على خرج المشغّل)

    int getEqPreset() {
        return Math.max(0, Math.min(EQ_LABELS.length - 1, prefs.getInt(KEY_EQ, 1)));
    }

    String getEqLabel() {
        return EQ_LABELS[getEqPreset()];
    }

    void cycleEq() {
        prefs.edit().putInt(KEY_EQ, (getEqPreset() + 1) % EQ_LABELS.length).apply();
        applyEffects();
    }

    int getGainIdx() {
        return Math.max(0, Math.min(GAIN_MB.length - 1, prefs.getInt(KEY_GAIN, 0)));
    }

    String getGainLabel() {
        return GAIN_LABELS[getGainIdx()];
    }

    void cycleGain() {
        prefs.edit().putInt(KEY_GAIN, (getGainIdx() + 1) % GAIN_MB.length).apply();
        applyEffects();
    }

    private int fxSessionId() {
        if (fxSession <= 0) {
            try {
                fxSession = audio != null ? audio.generateAudioSessionId() : 0;
            } catch (Throwable t) {
                fxSession = 0;
            }
            if (fxSession < 0) fxSession = 0;
        }
        return fxSession;
    }

    /** يربط المشغّل بجلسة المؤثرات (قبل setDataSource) ويفعّلها عند أول استعمال. */
    private void attachFx(MediaPlayer p) {
        int sid = fxSessionId();
        if (sid <= 0) return;
        try {
            p.setAudioSessionId(sid);
        } catch (Throwable ignored) {
        }
        if (!fxInit) {
            fxInit = true;
            applyEffects();
        }
    }

    private static float eqGainDb(int preset, int hz) {
        float[][] c = EQ_CURVES[preset];
        if (hz <= c[0][0]) return c[0][1];
        for (int i = 1; i < c.length; i++) {
            if (hz <= c[i][0]) {
                double t = (Math.log(hz) - Math.log(c[i - 1][0])) / (Math.log(c[i][0]) - Math.log(c[i - 1][0]));
                return (float) (c[i - 1][1] + (c[i][1] - c[i - 1][1]) * t);
            }
        }
        return c[c.length - 1][1];
    }

    /** يطبّق المعادل والتعزيز الحاليين؛ يفشل بصمت على الأجهزة التي لا تدعمهما (يبقى الصوت طبيعيًا). */
    private void applyEffects() {
        final int sid = fxSessionId();
        if (sid <= 0) return;
        final int preset = getEqPreset();
        try {
            if (preset == 0) {
                if (eq != null) eq.setEnabled(false);
            } else {
                if (eq == null) eq = new android.media.audiofx.Equalizer(0, sid);
                short[] range = eq.getBandLevelRange();
                short bands = eq.getNumberOfBands();
                for (short b = 0; b < bands; b++) {
                    int hz = Math.max(20, eq.getCenterFreq(b) / 1000);
                    int mb = Math.round(eqGainDb(preset, hz) * 100f);
                    mb = Math.max(range[0], Math.min(range[1], mb));
                    eq.setBandLevel(b, (short) mb);
                }
                eq.setEnabled(true);
            }
        } catch (Throwable t) {
            try {
                if (eq != null) eq.release();
            } catch (Throwable ignored) {
            }
            eq = null;
        }
        try {
            int mb = GAIN_MB[getGainIdx()];
            if (mb <= 0) {
                if (loud != null) loud.setEnabled(false);
            } else {
                if (loud == null) loud = new android.media.audiofx.LoudnessEnhancer(sid);
                loud.setTargetGain(mb);
                loud.setEnabled(true);
            }
        } catch (Throwable t) {
            try {
                if (loud != null) loud.release();
            } catch (Throwable ignored) {
            }
            loud = null;
        }
    }

    private void releaseEffects() {
        try {
            if (eq != null) eq.release();
        } catch (Throwable ignored) {
        }
        try {
            if (loud != null) loud.release();
        } catch (Throwable ignored) {
        }
        eq = null;
        loud = null;
        fxInit = false;
    }

    // ---- تجربة النطق: جملة نموذجية بالصوت والإعدادات الحالية (للتحقق من ة/ه والتشكيل والصفاء)

    interface PreviewListener {
        /** النص كما أُرسل للمحرك بعد التشكيل والضبط (على الخيط الرئيسي). */
        void onPrepared(String spokenText);

        void onFailed(String message);
    }

    /** تجربة النطق بصوت الجهاز: نفس تجهيز النص (تشكيل/ة-ه) ثم ينطقه محرك الجهاز المختار. */
    private void previewOnDevice(String sample, PreviewListener cb) {
        if (ttsFailed) {
            cb.onFailed("محرك النطق غير متاح على الجهاز.");
            return;
        }
        if (!ttsReady || tts == null) {
            cb.onFailed("محرك النطق قيد التجهيز، أعد المحاولة بعد لحظات.");
            return;
        }
        if (!applyVoice("ar")) {
            cb.onFailed("لا يوجد صوت عربي مثبّت" + (isDeviceOfflineOnly() ? " يعمل بدون إنترنت." : "."));
            return;
        }
        final SpeechPrep.Spoken spoken = SpeechPrep.prepare(EdgeTtsClient.sanitize(sample), "ar", "en", false);
        try {
            speakToken++; // يُبطل أي ردود متأخرة من قراءة سابقة
            tts.setSpeechRate(deviceRate());
            tts.setPitch(devicePitch());
            int r = tts.speak(spoken.text, TextToSpeech.QUEUE_FLUSH, null, "preview");
            if (r == TextToSpeech.ERROR) {
                cb.onFailed("تعذّر تشغيل التجربة بصوت الجهاز.");
                return;
            }
        } catch (Throwable t) {
            cb.onFailed("تعذّر تشغيل التجربة بصوت الجهاز.");
            return;
        }
        cb.onPrepared(spoken.text);
    }

    void previewSample(final String sample, final PreviewListener cb) {
        if (state == State.PLAYING) pause();
        if (!isCloudEngine()) {
            previewOnDevice(sample, cb);
            return;
        }
        releasePreview();
        final String voice = cloudVoiceFor("ar");
        final SpeechPrep.Spoken spoken = SpeechPrep.prepare(EdgeTtsClient.sanitize(sample), "ar", "en", false);
        final EdgeTtsClient.Style style = cloudStyle();
        final int gen = ++previewGen;
        try {
            synthPool.execute(() -> {
                EdgeTtsClient.Result r = null;
                try {
                    r = EdgeTtsClient.synthesize(spoken.text, voice, style);
                } catch (Throwable ignored) {
                }
                final EdgeTtsClient.Result rr = r;
                main.post(() -> {
                    if (gen != previewGen) return;
                    if (rr == null || rr.audio == null || rr.audio.length < 200) {
                        cb.onFailed("تعذّر تجهيز التجربة (تأكد من الإنترنت).");
                        return;
                    }
                    if (startPreview(rr.audio)) cb.onPrepared(spoken.text);
                    else cb.onFailed("تعذّر تشغيل التجربة.");
                });
            });
        } catch (RejectedExecutionException e) {
            cb.onFailed("تعذّر بدء التجربة.");
        }
    }

    /** إيقاف أي تجربة/درس صوتي جارٍ (قاموس الحروف) وإلغاء ما لم يصل بعد. */
    void stopPreview() {
        previewGen++;
        releasePreview();
    }

    private boolean startPreview(byte[] data) {
        try {
            if (!cacheDir.exists()) //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            File f = File.createTempFile("prev_", ".mp3", cacheDir);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            previewFile = f;
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            attachFx(p);
            p.setDataSource(f.getAbsolutePath());
            p.setOnPreparedListener(MediaPlayer::start);
            p.setOnCompletionListener(mp -> releasePreview());
            p.setOnErrorListener((mp, what, extra) -> {
                releasePreview();
                return true;
            });
            previewPlayer = p;
            p.prepareAsync();
            return true;
        } catch (Throwable t) {
            releasePreview();
            return false;
        }
    }

    private void releasePreview() {
        MediaPlayer p = previewPlayer;
        previewPlayer = null;
        if (p != null) {
            try {
                p.setOnPreparedListener(null);
                p.setOnCompletionListener(null);
                p.setOnErrorListener(null);
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        if (previewFile != null) {
            //noinspection ResultOfMethodCallIgnored
            previewFile.delete();
            previewFile = null;
        }
    }

    /** أي تغيير في النطق يُبطل الأصوات المجهّزة مسبقًا ويعيد القراءة من الموضع الحالي. */
    private void onSpeechSettingChanged() {
        resetCloud();
        if (state != State.IDLE) restartFromCurrentPoint();
    }

    /** السرعة الأساسية + تكيّف تلقائي من سلوك الاستماع (كثرة الرجوع تُبطّئ القراءة قليلًا، حدّه الأدنى -30%). */
    private static int learnedRate(int base) {
        try {
            return Math.max(-30, base + SpeechLearner.rateAdjustPct());
        } catch (Throwable t) {
            return base;
        }
    }

    private EdgeTtsClient.Style cloudStyle() {
        switch (getProfile()) {
            case 0:
                return new EdgeTtsClient.Style(learnedRate(0), PITCH_HZ[getPitchIndex()], 0, 0);
            case 2:
                return new EdgeTtsClient.Style(learnedRate(-12), PITCH_HZ[getPitchIndex()], 350, 140);
            default:
                return new EdgeTtsClient.Style(learnedRate(-5), PITCH_HZ[getPitchIndex()], 150, 60);
        }
    }

    private static boolean isMaleVoice(String voiceName) {
        for (String[] v : CLOUD_VOICES) {
            if (v[1].equals(voiceName)) return v[2].contains("ذكر");
        }
        return false;
    }

    /** صوت الجزء الأجنبي: اختيار المستخدم للغة إن وُجد، وإلا أول صوت بنفس جنس الصوت الأساسي. */
    private String runVoice(String runLang, String chunkLang, String baseVoice) {
        if (runLang.equals(chunkLang)) return baseVoice;
        String saved = prefs.getString("cvoice_" + runLang, null);
        if (saved != null && !badCloudVoices.contains(saved)) return saved;
        boolean male = isMaleVoice(baseVoice);
        for (String[] v : CLOUD_VOICES) {
            if (v[0].equals(runLang) && !badCloudVoices.contains(v[1]) && v[2].contains("ذكر") == male) return v[1];
        }
        return cloudVoiceFor(runLang);
    }

    // ------------------------------------------------------------------ اختيار المحرك والأصوات

    /** true = صوت عصبي أونلاين (الافتراضي)، false = صوت الجهاز. */
    boolean isCloudEngine() {
        return "cloud".equals(prefs.getString(KEY_ENGINE, "cloud"));
    }

    void setCloudEngine(boolean cloud) {
        prefs.edit().putString(KEY_ENGINE, cloud ? "cloud" : "device").apply();
        cloudBroken = false;
        cloudActive = false;
        badCloudVoices.clear();
        badVoices.clear();
        usedVoice.clear();
        lastAppliedLang = null;
        cloudVoiceSwitches = 0;
        resetCloud();
        if (state != State.IDLE) restartFromCurrentPoint();
    }

    List<VoiceOption> listCloudVoices(String lang) {
        List<VoiceOption> out = new ArrayList<>();
        for (String[] v : CLOUD_VOICES) {
            if (v[0].equals(lang)) out.add(new VoiceOption(v[1], v[2]));
        }
        return out;
    }

    String getPreferredCloudVoice(String lang) {
        return prefs.getString("cvoice_" + lang, null);
    }

    /** name = null يعني الصوت الافتراضي للغة. */
    void setPreferredCloudVoice(String lang, String name) {
        SharedPreferences.Editor e = prefs.edit();
        if (name == null) e.remove("cvoice_" + lang);
        else e.putString("cvoice_" + lang, name);
        e.apply();
        badCloudVoices.clear();
        cloudVoiceSwitches = 0;
        // اختيار المستخدم صوتًا جديدًا يعني أنه يريد الصوت العصبي: نلغي أي تحويل سابق لصوت الجهاز
        // (كان يبقى مفعّلًا طوال الجلسة فيبدو أن تبديل الصوت لا يعمل).
        cloudBroken = false;
        cloudActive = false;
        resetCloud();
        if (state != State.IDLE) restartFromCurrentPoint();
    }

    /** الصوت المختار للغة، مع تجاوز الأصوات التي فشلت؛ null لو فشلت كل أصوات اللغة. */
    private String pickCloudVoice(String lang) {
        String saved = prefs.getString("cvoice_" + lang, null);
        if (saved != null && !badCloudVoices.contains(saved)) {
            for (String[] v : CLOUD_VOICES) {
                if (v[0].equals(lang) && v[1].equals(saved)) return saved;
            }
        }
        for (String[] v : CLOUD_VOICES) {
            if (v[0].equals(lang) && !badCloudVoices.contains(v[1])) return v[1];
        }
        return null;
    }

    private String cloudVoiceFor(String lang) {
        String v = pickCloudVoice(lang);
        if (v != null) return v;
        for (String[] cv : CLOUD_VOICES) {
            if (cv[0].equals(lang)) return cv[1];
        }
        return "en-US-EmmaMultilingualNeural";
    }

    private boolean useCloud() {
        if (cloudBroken && System.currentTimeMillis() - cloudBrokenAt > CLOUD_RETRY_MS) {
            cloudBroken = false;
            badCloudVoices.clear();
            cloudVoiceSwitches = 0;
        }
        return isCloudEngine() && !cloudBroken;
    }

    // ------------------------------------------------------------------ أصوات الجهاز

    String getEngineName() {
        try {
            return tts != null ? tts.getDefaultEngine() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- التحكم بمحرك النطق المحلي (بدون إنترنت): المحرك، الأصوات، السرعة، الرجوع التلقائي

    /** سرعة صوت الجهاز = السرعة العامة × معايرة الجهاز (لأن محركات الجهاز تختلف سرعتها عن العصبي). */
    private float deviceRate() {
        return Math.max(0.3f, Math.min(3.0f, rate * DEV_SPEED_MUL[getDeviceSpeedIndex()]));
    }

    /** اسم حزمة محرك الجهاز الفعلي (المختار أو الافتراضي) - مفتاح للتعلّم. */
    private String enginePkg() {
        String p = getDeviceEnginePackage();
        if (p != null) return p;
        String d = getEngineName();
        return d == null ? "default" : d;
    }

    private String usedVoiceName(String lang) {
        Voice v = usedVoice.get(lang);
        return v == null ? null : v.getName();
    }

    /** سرعة صوت الجهاز لهذا المقطع: في الوضع التلقائي يضبطها النموذج المحلي لتطابق إيقاع العصبي. */
    private float deviceRateFor(String lang) {
        float r = deviceRate();
        if (getDeviceSpeedIndex() == 0 && isVoiceLearning()) {
            try {
                int pct = "ar".equals(lang) ? cloudStyle().arRatePct : 0;
                r *= LocalVoiceModel.deviceRateMultiplier(enginePkg(), usedVoiceName(lang), lang, pct);
            } catch (Throwable ignored) {
            }
        }
        return Math.max(0.3f, Math.min(3.0f, r));
    }

    boolean isVoiceLearning() {
        return prefs.getBoolean(KEY_LEARN, true);
    }

    void setVoiceLearning(boolean v) {
        prefs.edit().putBoolean(KEY_LEARN, v).apply();
    }

    String voiceModelStats() {
        return LocalVoiceModel.stats();
    }

    void resetVoiceModel() {
        LocalVoiceModel.reset();
        if (ttsReady) {
            try {
                tts.setSpeechRate(deviceRate());
            } catch (Throwable ignored) {
            }
        }
    }

    // قياس نطق الجهاز الجاري (للتعلّم): يُملأ في handleStart ويُستهلك في handleDone
    private int devMeasToken = -1;
    private long devMeasAt = 0L;
    private int devMeasChars = 0;
    private String devMeasLang = null;
    private String devMeasVoice = null;
    private float devMeasRate = 1f;

    int getDeviceSpeedIndex() {
        return Math.max(0, Math.min(DEV_SPEED_MUL.length - 1, prefs.getInt(KEY_DEV_SPEED, 0)));
    }

    String getDeviceSpeedLabel() {
        return DEV_SPEED_LABELS[getDeviceSpeedIndex()];
    }

    void cycleDeviceSpeed() {
        prefs.edit().putInt(KEY_DEV_SPEED, (getDeviceSpeedIndex() + 1) % DEV_SPEED_MUL.length).apply();
        if (ttsReady) {
            try {
                tts.setSpeechRate(deviceRate());
            } catch (Throwable ignored) {
            }
        }
        if (state == State.PLAYING && !cloudActive) restartFromCurrentPoint();
    }

    boolean isDeviceOfflineOnly() {
        return prefs.getBoolean(KEY_DEV_OFFLINE, false);
    }

    /** true = لا تُستعمل إلا أصوات الجهاز التي تعمل بلا إنترنت (لا أصوات شبكية من محرك النطق). */
    void setDeviceOfflineOnly(boolean v) {
        prefs.edit().putBoolean(KEY_DEV_OFFLINE, v).apply();
        usedVoice.clear();
        badVoices.clear();
        notifiedMissing.clear();
        lastAppliedLang = null;
        if (state != State.IDLE && (!isCloudEngine() || cloudBroken || !cloudActive)) restartFromCurrentPoint();
    }

    boolean isDeviceFallback() {
        return prefs.getBoolean(KEY_DEV_FALLBACK, true);
    }

    void setDeviceFallback(boolean v) {
        prefs.edit().putBoolean(KEY_DEV_FALLBACK, v).apply();
    }

    /** حزمة محرك النطق المحلي المختار، أو null = الافتراضي في النظام. */
    String getDeviceEnginePackage() {
        return prefs.getString(KEY_DEV_ENGINE, null);
    }

    /** محركات النطق المثبّتة على الجهاز (الاسم الظاهر + الحزمة). فارغة لو المحرك لم يجهز بعد. */
    List<VoiceOption> listDeviceEngines() {
        List<VoiceOption> out = new ArrayList<>();
        try {
            if (tts == null || !ttsReady) return out;
            List<TextToSpeech.EngineInfo> infos = tts.getEngines();
            if (infos == null) return out;
            for (TextToSpeech.EngineInfo e : infos) {
                if (e == null || e.name == null) continue;
                String label = (e.label != null && !e.label.isEmpty() ? e.label : e.name);
                out.add(new VoiceOption(e.name, label));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** يبدّل محرك النطق المحلي (pkg = null يعني افتراضي النظام) ويكمل القراءة من نفس الموضع لو كانت على صوت الجهاز. */
    void setDeviceEngine(String pkg) {
        String cur = getDeviceEnginePackage();
        if ((cur == null && pkg == null) || (cur != null && cur.equals(pkg))) return;
        SharedPreferences.Editor e = prefs.edit();
        if (pkg == null) e.remove(KEY_DEV_ENGINE);
        else e.putString(KEY_DEV_ENGINE, pkg);
        e.apply();
        final boolean deviceInUse = state != State.IDLE && currentText != null
                && (!isCloudEngine() || cloudBroken || !cloudActive);
        if (deviceInUse) {
            captureResumePoint();
            hardStopOutputs();
        }
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {
        }
        tts = null;
        ttsReady = false;
        ttsFailed = false;
        usedVoice.clear();
        badVoices.clear();
        notifiedMissing.clear();
        lastAppliedLang = null;
        deviceErrStreak = 0;
        if (deviceInUse) {
            setState(State.LOADING);
            pendingAfterInit = () -> {
                lastAppliedLang = null;
                speakChunk(resumeChunk, resumeShift);
            };
        }
        initTts(pkg, false);
    }

    String getPreferredVoice(String lang) {
        return prefs.getString("voice_" + lang, null);
    }

    /** name = null يعني اختيار تلقائي (أعلى جودة). */
    void setPreferredVoice(String lang, String name) {
        SharedPreferences.Editor e = prefs.edit();
        if (name == null) e.remove("voice_" + lang);
        else e.putString("voice_" + lang, name);
        e.apply();
        usedVoice.remove(lang);
        if (name != null) badVoices.remove(name); // صوت فشل سابقًا ثم اختاره المستخدم صراحةً: نجرّبه من جديد
        lastAppliedLang = null;
        // نعيد القراءة فورًا بالصوت الجديد لو كانت تعمل على صوت الجهاز (أو متوقفة مؤقتًا)
        if (state != State.IDLE && (!isCloudEngine() || cloudBroken || !cloudActive)) restartFromCurrentPoint();
    }

    List<VoiceOption> listVoices(String lang) {
        List<VoiceOption> out = new ArrayList<>();
        if (!ttsReady) return out;
        List<Voice> voices = installedVoices(lang);
        int i = 1;
        for (Voice v : voices) {
            StringBuilder sb = new StringBuilder("صوت ").append(i++).append(" · ");
            sb.append(v.isNetworkConnectionRequired() ? "يحتاج إنترنت" : "بدون إنترنت");
            sb.append(" · ").append(qualityLabel(v.getQuality()));
            String c = v.getLocale() != null ? v.getLocale().getCountry() : "";
            if (c != null && !c.isEmpty()) sb.append(" · ").append(c);
            out.add(new VoiceOption(v.getName(), sb.toString()));
        }
        return out;
    }

    private static String qualityLabel(int q) {
        if (q >= Voice.QUALITY_VERY_HIGH) return "جودة فائقة";
        if (q >= Voice.QUALITY_HIGH) return "جودة عالية";
        if (q >= Voice.QUALITY_NORMAL) return "جودة عادية";
        return "جودة منخفضة";
    }

    private List<Voice> installedVoices(String lang) {
        List<Voice> list = new ArrayList<>();
        try {
            Set<Voice> all = tts.getVoices();
            if (all == null) return list;
            for (Voice v : all) {
                if (v == null || v.getLocale() == null) continue;
                if (!lang.equals(v.getLocale().getLanguage())) continue;
                Set<String> f = v.getFeatures();
                if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (isDeviceOfflineOnly() && v.isNetworkConnectionRequired()) continue;
                list.add(v);
            }
        } catch (Throwable ignored) {
        }
        final String deviceCountry = Locale.getDefault().getCountry();
        Collections.sort(list, (a, b) -> Integer.compare(voiceScore(b, deviceCountry), voiceScore(a, deviceCountry)));
        return list;
    }

    private int voiceScore(Voice v, String deviceCountry) {
        int s = v.getQuality() * 10;
        if (!v.isNetworkConnectionRequired()) s += 5; // أوثق (يعمل بدون اتصال)
        String c = v.getLocale().getCountry();
        if (c != null && c.equalsIgnoreCase(deviceCountry)) s += 3;
        if ("en".equals(v.getLocale().getLanguage()) && "US".equalsIgnoreCase(c)) s += 1;
        s -= Math.min(4, v.getLatency() / 100);
        if (isVoiceLearning()) s += LocalVoiceModel.voiceScoreBonus(enginePkg(), v.getName()); // الأوثق بحسب التجربة
        return s;
    }

    private Voice chooseVoice(String lang) {
        Voice cached = usedVoice.get(lang);
        if (cached != null) return cached;
        List<Voice> candidates = installedVoices(lang);
        if (candidates.isEmpty()) return null;
        String saved = prefs.getString("voice_" + lang, null);
        Voice pick = null;
        if (saved != null) {
            for (Voice v : candidates) {
                if (v.getName().equals(saved) && !badVoices.contains(v.getName())) {
                    pick = v;
                    break;
                }
            }
        }
        if (pick == null) {
            for (Voice v : candidates) {
                if (!badVoices.contains(v.getName())) {
                    pick = v;
                    break;
                }
            }
        }
        if (pick != null) usedVoice.put(lang, pick);
        return pick;
    }

    private static Locale localeFor(String lang) {
        switch (lang) {
            case "ar":
                return new Locale("ar");
            case "fr":
                return Locale.FRENCH;
            case "tr":
                return new Locale("tr", "TR");
            default:
                return Locale.US;
        }
    }

    /** يضبط صوت اللغة قبل نطق مقطع. يرجّع false لو ما في صوت متاح لها. */
    private boolean applyVoice(String lang) {
        if (lang.equals(lastAppliedLang)) return true;
        boolean ok = false;
        try {
            Voice v = chooseVoice(lang);
            if (v != null) {
                ok = tts.setVoice(v) == TextToSpeech.SUCCESS;
            }
            if (!ok && !(isDeviceOfflineOnly() && v == null)) {
                Locale loc = localeFor(lang);
                int avail = tts.isLanguageAvailable(loc);
                if (avail >= TextToSpeech.LANG_AVAILABLE) {
                    ok = tts.setLanguage(loc) >= TextToSpeech.LANG_AVAILABLE;
                }
            }
        } catch (Throwable ignored) {
        }
        if (ok) {
            lastAppliedLang = lang;
        } else if (notifiedMissing.add(lang)) {
            listener.onVoiceMissing(lang);
        }
        return ok;
    }

    // ------------------------------------------------------------------ تحميل الصفحات

    private void runWhenReady(Runnable r) {
        if (ttsFailed) {
            listener.onEngineUnavailable();
            return;
        }
        if (ttsReady) r.run();
        else {
            setState(State.LOADING);
            pendingAfterInit = r;
        }
    }

    private PdfSpeechText.PageText takePage(int page) {
        if (source == null || page < 0 || page >= pageCount) return null;
        PdfSpeechText.PageText pt = prefetched;
        if (pt != null && pt.pageIndex == page) {
            prefetched = null;
            return pt;
        }
        return source.page(page);
    }

    /** يُنفَّذ على خيط io. */
    private void loadPageOnIo(int page, int sess, int startChunk, int startShift) {
        if (page >= pageCount) {
            main.post(() -> {
                if (sess != session) return;
                finishAll();
            });
            return;
        }
        final PdfSpeechText.PageText pt = takePage(page);
        main.post(() -> beginPage(page, pt, sess, startChunk, startShift));
    }

    private void goToPage(int page) {
        final int sess = session;
        speakToken++;
        releasePlayer();
        if (page >= pageCount) {
            finishAll();
            return;
        }
        currentText = null;
        io.execute(() -> loadPageOnIo(page, sess, 0, 0));
    }

    private void beginPage(int page, PdfSpeechText.PageText pt, int sess, int startChunk, int startShift) {
        if (sess != session) return;
        currentPage = page;
        if (pt == null || pt.isEmpty()) {
            emptyStreak++;
            String ocrError = PdfOcr.takeFatalError();
            if (ocrError != null) {
                stop();
                listener.onError(ocrError);
                return;
            }
            if (!anyText && emptyStreak >= 4) {
                stop();
                listener.onError(PdfOcr.getMode(app) == PdfOcr.MODE_OFF
                        ? "لا يوجد نص قابل للقراءة في هذه الصفحات - الملف صور ممسوحة ضوئيًا. فعّل التعرّف على النص الممسوح (OCR) من إعدادات القراءة الصوتية."
                        : "لم يُعثر على نص قابل للقراءة في هذه الصفحات (حتى بالتعرّف الضوئي).");
                return;
            }
            goToPage(page + 1);
            return;
        }
        emptyStreak = 0;
        anyText = true;
        currentText = pt;
        currentChunk = 0;
        currentWord = -1;
        resumeChunk = 0;
        resumeShift = 0;
        listener.onPageStarted(page, pt);
        speakChunk(startChunk, startShift);
        // تجهيز الصفحة التالية مسبقًا (نصها، وصوت أول جملة فيها) حتى لا يحصل فراغ عند الانتقال
        final int next = page + 1;
        io.execute(() -> {
            if (source != null && next < pageCount) {
                final PdfSpeechText.PageText p = source.page(next, true);
                if (sess == session) {
                    prefetched = p;
                    if (p != null && !p.isEmpty()) {
                        main.post(() -> {
                            if (sess == session && useCloud()) requestCloud(p, 0);
                        });
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ نطق المقاطع (جملة بجملة)

    /** يبدأ نطق المقطع idx (وما بعده لو تعذّر)، وعند نهاية الصفحة ينتقل للتالية. */
    private void speakChunk(int idx, int shift) {
        if (currentText == null) return;
        if (!useCloud() && !ttsReady) {
            if (ttsFailed) {
                stop();
                listener.onEngineUnavailable();
                return;
            }
            setState(State.LOADING);
            final int i = idx;
            final int s = shift;
            final int tok = speakToken;
            pendingAfterInit = () -> {
                if (tok == speakToken) speakChunk(i, s);
            };
            return;
        }
        int n = currentText.chunks.size();
        while (idx < n) {
            if (startChunk(idx, shift)) return;
            idx++;
            shift = 0;
        }
        goToPage(currentPage + 1);
    }

    /** true = بدأ النطق (أو ينتظر تجهيز الصوت)، false = يجب تخطي هذا المقطع. */
    private boolean startChunk(int idx, int shift) {
        PdfSpeechText.Chunk c = currentText.chunks.get(idx);
        if (currentText.text.substring(c.start, c.end).trim().isEmpty()) return false;
        currentChunk = idx;
        currentWord = -1;
        final int tok = ++speakToken;
        releasePlayer();

        if (useCloud()) {
            cloudActive = true;
            String key = requestCloud(currentText, idx);
            prefetchAhead(idx);
            if (nextPlayer != null && !key.equals(nextKey)) releaseNext();
            if (nextPlayer != null && nextPrepared && adoptPreloaded(idx, tok, key)) return true;
            CloudAudio a = cloudReady.remove(key);
            if (a != null) {
                startPlayer(a, idx, tok);
            } else {
                awaitingKey = key;
                awaitingToken = tok;
                awaitingChunk = idx;
                if (state != State.LOADING) setState(State.LOADING);
            }
            return true;
        }

        // صوت الجهاز
        cloudActive = false;
        awaitingKey = null;
        if (!applyVoice(c.lang)) return false;
        int s = Math.min(c.end, c.start + Math.max(0, shift));
        String rawText = currentText.text.substring(s, c.end);
        if (rawText.trim().isEmpty()) return false;
        SpeechPrep.Spoken spoken = SpeechPrep.prepare(EdgeTtsClient.sanitize(rawText), c.lang, currentText.latin, false, c.cont);
        String text = spoken.text;
        if (text.trim().isEmpty()) return false; // رموز فقط
        deviceSpoken = spoken;
        deviceSpokenToken = tok;
        int r;
        try {
            Bundle speakParams = new Bundle();
            int fxSid = fxSessionId();
            if (fxSid > 0) {
                speakParams.putInt("sessionId", fxSid); // TextToSpeech.Engine.KEY_PARAM_SESSION_ID: نفس المؤثرات على صوت الجهاز
                if (!fxInit) {
                    fxInit = true;
                    applyEffects();
                }
            }
            final float devRate = deviceRateFor(c.lang);
            tts.setSpeechRate(devRate);
            devMeasToken = tok;
            devMeasAt = 0L;
            devMeasChars = text.length();
            devMeasLang = c.lang;
            devMeasVoice = usedVoiceName(c.lang);
            devMeasRate = devRate;
            r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, speakParams, makeId(tok, currentPage, idx, shift));
        } catch (Throwable t) {
            r = TextToSpeech.ERROR;
        }
        if (r == TextToSpeech.ERROR) {
            pause();
            listener.onError("تعذّر تشغيل محرك النطق.");
        }
        return true;
    }

    private void advance() {
        if (currentText == null) return;
        try {
            SpeechLearner.noteHeard(currentChunkRaw()); // سُمع المقطع كاملًا
        } catch (Throwable ignored) {
        }
        int next = currentChunk + 1;
        if (next < currentText.chunks.size()) speakChunk(next, 0);
        else goToPage(currentPage + 1);
    }

    private void captureResumePoint() {
        if (currentText == null) return;
        if (currentChunk >= 0 && currentChunk < currentText.chunks.size()) {
            PdfSpeechText.Chunk c = currentText.chunks.get(currentChunk);
            int shift = 0;
            if (currentWord >= 0 && currentWord < currentText.words.size()) {
                shift = Math.max(0, currentText.words.get(currentWord).start - c.start);
            }
            resumeChunk = currentChunk;
            resumeShift = shift;
        } else {
            resumeChunk = 0;
            resumeShift = 0;
        }
    }

    private void finishAll() {
        try {
            SpeechLearner.flush();
            LocalVoiceModel.flush();
        } catch (Throwable ignored) {
        }
        session++;
        hardStopOutputs();
        resetCloud();
        abandonFocus();
        currentText = null;
        currentWord = -1;
        setState(State.IDLE);
        listener.onFinished();
    }

    /** يوقف كل ما يُنطق الآن (المحرك والمشغّل) ويُبطل أي ردود متأخرة. */
    private void hardStopOutputs() {
        speakToken++;
        releaseNext();
        awaitingKey = null;
        stopEngine();
        releasePlayer();
    }

    private void stopEngine() {
        try {
            if (tts != null && ttsReady) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        listener.onStateChanged(s);
    }

    // ------------------------------------------------------------------ الصوت الأونلاين

    private String cloudKey(String voice, int page, int chunk) {
        return voice + "#" + page + "#" + chunk;
    }

    /** يطلب تجهيز صوت المقطع (لو لم يكن جاهزًا أو قيد التجهيز) ويرجّع مفتاحه. */
    private String requestCloud(PdfSpeechText.PageText pt, int idx) {
        PdfSpeechText.Chunk c = pt.chunks.get(idx);
        final String voice = cloudVoiceFor(c.lang);
        final String key = cloudKey(voice, pt.pageIndex, idx);
        if (cloudReady.containsKey(key) || cloudPending.contains(key)) return key;
        final boolean mix = isMixedVoices();
        final SpeechPrep.Spoken spoken = SpeechPrep.prepare(
                EdgeTtsClient.sanitize(pt.text.substring(c.start, c.end)), c.lang, pt.latin, mix, c.cont);
        final String sent = spoken.text;
        final EdgeTtsClient.Style style = cloudStyle();
        List<EdgeTtsClient.Run> runList = null;
        if (mix && spoken.isMixed()) {
            runList = new ArrayList<>();
            for (SpeechPrep.Run r : spoken.runs) {
                runList.add(new EdgeTtsClient.Run(r.start, r.end, runVoice(r.lang, c.lang, voice)));
            }
        }
        final List<EdgeTtsClient.Run> runs = runList;
        final int gen = cloudGen;
        cloudPending.add(key);
        try {
            synthPool.execute(() -> {
                EdgeTtsClient.Result r = null;
                Throwable err = null;
                try {
                    if (sent.trim().isEmpty()) { // مقطع كله رموز: لا يوجد ما يُنطق - نتخطاه بدون اتصال
                        r = new EdgeTtsClient.Result(new byte[0], new int[0], new int[0]);
                    } else {
                        r = runs != null ? EdgeTtsClient.synthesizeRuns(sent, runs, voice, style)
                                : EdgeTtsClient.synthesize(sent, voice, style);
                    }
                } catch (Throwable t) {
                    err = t;
                }
                final EdgeTtsClient.Result rr = r;
                final Throwable ee = err;
                // تعلّم إيقاع الصوت العصبي الحقيقي (للأصوات أحادية اللغة فقط؛ المختلطة تتداخل فيها الأصوات)
                if (runs == null && rr != null && rr.audio != null && rr.audio.length >= 200 && rr.durationMs > 0
                        && isVoiceLearning()) {
                    LocalVoiceModel.learnNeural(c.lang, sent.length(), rr.durationMs,
                            voice.startsWith("ar-") ? style.arRatePct : 0);
                }
                main.post(() -> onCloudResult(key, rr == null ? null : new CloudAudio(rr, spoken), gen, ee));
            });
        } catch (RejectedExecutionException e) {
            cloudPending.remove(key);
        }
        return key;
    }

    private void prefetchAhead(int idx) {
        if (currentText == null) return;
        int n = currentText.chunks.size();
        for (int k = idx + 1; k <= idx + 3 && k < n; k++) requestCloud(currentText, k);
        // المقطع الأخير في الصفحة: نجهّز صوت أول مقطع في الصفحة التالية فورًا حتى لا يحصل انتظار عند الانتقال
        PdfSpeechText.PageText nx = prefetched;
        if (idx + 1 >= n && nx != null && nx.pageIndex == currentText.pageIndex + 1 && !nx.isEmpty()) requestCloud(nx, 0);
    }

    private void onCloudResult(String key, CloudAudio a, int gen, Throwable err) {
        if (gen != cloudGen) return;
        cloudPending.remove(key);
        boolean waiting = key.equals(awaitingKey) && awaitingToken == speakToken;
        if (a == null) {
            if (!waiting) return; // فشل تجهيز مسبق: سنعيد الطلب عند الحاجة
            if (currentText == null) return;
            final String why = EdgeTtsClient.lastError == null || EdgeTtsClient.lastError.isEmpty()
                    ? "" : " (" + EdgeTtsClient.lastError + ")";
            // مشكلة شبكة أو رفض الاتصال نفسه (403/503...) -> تبديل الصوت لا يفيد: صوت الجهاز فورًا
            boolean voiceProblem = err instanceof EdgeTtsClient.ServiceException
                    && ((EdgeTtsClient.ServiceException) err).httpCode == 0;
            if (!voiceProblem) {
                awaitingKey = null;
                fallbackToDevice((EdgeTtsClient.isNetworkFailure(err)
                        ? "تعذّر الاتصال بالصوت العصبي (تأكد من الإنترنت)، تم التحويل لصوت الجهاز تلقائيًا."
                        : "الصوت العصبي غير متاح حاليًا من الخادم، تم التحويل لصوت الجهاز تلقائيًا.") + why);
                return;
            }
            // الخادم اتصل لكن هذا الصوت بالذات فشل: نجرّب صوتًا بديلًا بنفس اللغة (حتى مرتين) قبل صوت الجهاز
            PdfSpeechText.Chunk c = currentText.chunks.get(Math.max(0, Math.min(awaitingChunk, currentText.chunks.size() - 1)));
            String badVoice = key.substring(0, key.indexOf('#'));
            badCloudVoices.add(badVoice);
            String alt = pickCloudVoice(c.lang);
            if (alt != null && cloudVoiceSwitches < 2) {
                cloudVoiceSwitches++;
                awaitingKey = requestCloud(currentText, awaitingChunk);
                prefetchAhead(awaitingChunk);
            } else {
                awaitingKey = null;
                fallbackToDevice("تعذّر تشغيل الصوت العصبي، تم التحويل لصوت الجهاز تلقائيًا." + why);
            }
            return;
        }
        if (waiting) {
            awaitingKey = null;
            startPlayer(a, awaitingChunk, awaitingToken);
        } else {
            cloudReady.put(key, a);
            preloadNext();
        }
    }

    private void fallbackToDevice(String message) {
        if (!isDeviceFallback()) {
            // المستخدم عطّل الرجوع التلقائي: لا نبدّل المحرك بصمت، نوقف مؤقتًا ونخبره ليقرّر
            cloudActive = false;
            pause();
            listener.onError(message.replace("تم التحويل لصوت الجهاز تلقائيًا.",
                    "الرجوع التلقائي لصوت الجهاز معطّل في الإعدادات، فتوقفت القراءة."));
            return;
        }
        cloudBroken = true;
        cloudBrokenAt = System.currentTimeMillis();
        cloudActive = false;
        if (!cloudErrorShown) {
            cloudErrorShown = true;
            listener.onError(message); // أول مرة: الرسالة الكاملة مع التشخيص
        } else {
            listener.onError("تعذّر الصوت العصبي لهذا المقطع، نستخدم صوت الجهاز مؤقتًا ثم نعيد المحاولة تلقائيًا.");
        }
        if (currentText == null) return;
        lastAppliedLang = null;
        speakChunk(currentChunk, 0);
    }

    private void startPlayer(CloudAudio a, int idx, int tok) {
        playerFdMode = false;
        startPlayerInternal(a, idx, tok, false);
    }

    private void startPlayerInternal(CloudAudio a, int idx, int tok, boolean useFd) {
        if (tok != speakToken || currentText == null) return;
        if (a.data == null || a.data.length < 200) { // لا يوجد ما يُنطق (رموز فقط) - نتخطاه
            advance();
            return;
        }
        try {
            if (!cacheDir.exists()) //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            File f = File.createTempFile("tts_", ".mp3", cacheDir);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(a.data);
            }
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            attachFx(p);
            if (useFd) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    p.setDataSource(in.getFD());
                }
            } else {
                p.setDataSource(f.getAbsolutePath());
            }
            p.setOnPreparedListener(mp -> onPlayerPrepared(mp, tok));
            p.setOnCompletionListener(mp -> {
                if (tok == speakToken && mp == player) advance();
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (tok == speakToken && mp == player) {
                    playerDiag = "mp=" + what + "/" + extra + " bytes=" + a.data.length + " head=" + headHex(a.data);
                    retryOrFail(a, idx, tok);
                }
                return true;
            });
            player = p;
            playerFile = f;
            playerAudio = a;
            playerPrepared = false;
            playerPaused = false;
            p.prepareAsync();
        } catch (Throwable t) {
            playerDiag = "ex=" + t.getClass().getSimpleName() + ":" + t.getMessage()
                    + " bytes=" + (a.data == null ? -1 : a.data.length) + " head=" + headHex(a.data);
            retryOrFail(a, idx, tok);
        }
    }

    private static String headHex(byte[] d) {
        if (d == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, d.length); i++) sb.append(String.format(Locale.ROOT, "%02X", d[i]));
        return sb.toString();
    }

    /** أول فشل تشغيل لنفس الجملة: نعيد بمشغّل جديد عبر FileDescriptor؛ الفشل الثاني يُحتسب. */
    private void retryOrFail(CloudAudio a, int idx, int tok) {
        releasePlayer();
        if (!playerFdMode) {
            playerFdMode = true;
            startPlayerInternal(a, idx, tok, true);
            return;
        }
        playerFdMode = false;
        onPlayerError();
    }

    private void onPlayerPrepared(MediaPlayer mp, int tok) {
        if (tok != speakToken || mp != player || currentText == null) return;
        playerPrepared = true;
        cloudPlayErrStreak = 0;
        cloudVoiceSwitches = 0;
        requestFocus();
        try {
            mp.start();
        } catch (Throwable t) {
            playerDiag = "start:" + t.getClass().getSimpleName();
            onPlayerError();
            return;
        }
        applySpeed(mp);
        ensureEnvelope(playerAudio);
        if (state != State.PLAYING) setState(State.PLAYING);
        if (currentChunk >= 0 && currentChunk < currentText.chunks.size()) {
            PdfSpeechText.Chunk c = currentText.chunks.get(currentChunk);
            currentWord = c.firstWord;
            listener.onSpeaking(currentPage, currentText, currentChunk, currentWord);
        }
        startPoll();
        preloadNext();
    }

    private void onPlayerError() {
        releasePlayer();
        cloudPlayErrStreak++;
        if (cloudPlayErrStreak >= 3) {
            cloudPlayErrStreak = 0;
            fallbackToDevice("تعذّر تشغيل الصوت العصبي، تم التحويل لصوت الجهاز تلقائيًا. [" + playerDiag + "]");
        } else {
            advance(); // نتخطى هذه الجملة ونكمل
        }
    }

    private void applySpeed(MediaPlayer mp) {
        try {
            PlaybackParams pp = mp.getPlaybackParams();
            pp.setSpeed(rate);
            mp.setPlaybackParams(pp);
        } catch (Throwable ignored) {
        }
    }

    private void startPoll() {
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_MS);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (player == null || !playerPrepared || playerPaused || state != State.PLAYING) return;
            cloudProgress();
            main.postDelayed(this, POLL_MS);
        }
    };

    /** يحدّد الكلمة المنطوقة من موضع التشغيل (بتوقيت الخادم لو متوفر، وإلا بالتناسب مع طول الصوت). */
    private void cloudProgress() {
        MediaPlayer p = player;
        CloudAudio a = playerAudio;
        PdfSpeechText.PageText t = currentText;
        if (p == null || a == null || t == null) return;
        if (currentChunk < 0 || currentChunk >= t.chunks.size()) return;
        int pos;
        try {
            pos = p.getCurrentPosition();
        } catch (Throwable e) {
            return;
        }
        pos -= (int) (CLOUD_LAG_MS * Math.max(0.5f, rate)); // زمن الوسائط لا الزمن الحقيقي
        PdfSpeechText.Chunk c = t.chunks.get(currentChunk);
        final boolean mapped = a.spoken != null && a.spoken.text.length() > 0;
        int len = Math.max(1, mapped ? a.spoken.text.length() : c.end - c.start);
        int off;
        if (a.wordMs != null && a.wordMs.length > 0) {
            int found = -1;
            for (int k = 0; k < a.wordMs.length; k++) {
                if (a.wordMs[k] <= pos) found = k;
                else break;
            }
            off = found < 0 ? 0 : a.wordChar[found];
        } else {
            int dur = 0;
            try {
                dur = p.getDuration();
            } catch (Throwable ignored) {
            }
            off = dur > 0 ? (int) (len * Math.min(1f, pos / (float) dur)) : 0;
        }
        off = Math.max(0, Math.min(len - 1, off));
        if (mapped) off = a.spoken.toOriginal(off);
        int w = Math.max(c.firstWord, Math.min(c.lastWord, t.wordAtOffset(c.start + off)));
        if (w != currentWord) {
            currentWord = w;
            listener.onSpeaking(currentPage, t, currentChunk, w);
        }
    }

    /** يجهّز مشغّل الجملة التالية (لو صوتها جاهز) أثناء نطق الحالية. */
    private void preloadNext() {
        if (!cloudActive || currentText == null || player == null) return;
        int idx = currentChunk + 1;
        if (idx >= currentText.chunks.size()) return;
        PdfSpeechText.Chunk c = currentText.chunks.get(idx);
        String key = cloudKey(cloudVoiceFor(c.lang), currentText.pageIndex, idx);
        if (nextPlayer != null) {
            if (key.equals(nextKey)) return;
            releaseNext();
        }
        CloudAudio a = cloudReady.get(key);
        if (a == null || a.data == null || a.data.length < 200) return;
        ensureEnvelope(a); // تجهيز غلاف الصوت مسبقًا كي تكون الموجة جاهزة عند بدء الجملة
        try {
            if (!cacheDir.exists()) //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            File f = File.createTempFile("tts_", ".mp3", cacheDir);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(a.data);
            }
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            attachFx(p);
            p.setDataSource(f.getAbsolutePath());
            p.setOnPreparedListener(mp -> {
                if (mp == nextPlayer) nextPrepared = true;
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (mp == nextPlayer) releaseNext();
                return true;
            });
            nextPlayer = p;
            nextFile = f;
            nextKey = key;
            nextAudio = a;
            nextPrepared = false;
            p.prepareAsync();
        } catch (Throwable t) {
            releaseNext();
        }
    }

    /** يبدأ الجملة الجاهزة مسبقًا فورًا (بدون كتابة ملف ولا prepare). false = غير ممكن فنكمل بالطريقة العادية. */
    private boolean adoptPreloaded(int idx, int tok, String key) {
        MediaPlayer p = nextPlayer;
        File f = nextFile;
        CloudAudio a = nextAudio;
        nextPlayer = null;
        nextFile = null;
        nextKey = null;
        nextAudio = null;
        nextPrepared = false;
        if (p == null || a == null) return false;
        try {
            p.setOnPreparedListener(null);
            p.setOnCompletionListener(mp -> {
                if (tok == speakToken && mp == player) advance();
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (tok == speakToken && mp == player) {
                    playerDiag = "mp=" + what + "/" + extra + " bytes=" + a.data.length + " (preloaded)";
                    retryOrFail(a, idx, tok);
                }
                return true;
            });
            cloudReady.remove(key);
            player = p;
            playerFile = f;
            playerAudio = a;
            playerPrepared = true;
            playerPaused = false;
            onPlayerPrepared(p, tok);
            return true;
        } catch (Throwable t) {
            try {
                p.release();
            } catch (Throwable ignored) {
            }
            if (f != null) //noinspection ResultOfMethodCallIgnored
                f.delete();
            player = null;
            playerFile = null;
            playerAudio = null;
            playerPrepared = false;
            return false;
        }
    }

    private void releaseNext() {
        MediaPlayer p = nextPlayer;
        nextPlayer = null;
        if (p != null) {
            try {
                p.setOnPreparedListener(null);
                p.setOnCompletionListener(null);
                p.setOnErrorListener(null);
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        if (nextFile != null) {
            //noinspection ResultOfMethodCallIgnored
            nextFile.delete();
            nextFile = null;
        }
        nextKey = null;
        nextAudio = null;
        nextPrepared = false;
    }

    private void releasePlayer() {
        main.removeCallbacks(poll);
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try {
                p.setOnPreparedListener(null);
                p.setOnCompletionListener(null);
                p.setOnErrorListener(null);
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        if (playerFile != null) {
            //noinspection ResultOfMethodCallIgnored
            playerFile.delete();
            playerFile = null;
        }
        playerAudio = null;
        playerPrepared = false;
        playerPaused = false;
    }

    /** يمسح كل الأصوات المجهّزة/الجارية ويُبطل نتائج الطلبات المتأخرة. */
    private void resetCloud() {
        releaseNext();
        cloudGen++;
        cloudReady.clear();
        cloudPending.clear();
        cloudRetried.clear();
        awaitingKey = null;
        cloudPlayErrStreak = 0;
    }

    private void cleanCacheDir() {
        try {
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            for (File f : files) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ تركيز الصوت (للصوت الأونلاين فقط)

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> main.post(() -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            focusHeld = false;
            resumeOnFocusGain = false;
            if (state == State.PLAYING || state == State.LOADING) pauseInternal(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (state == State.PLAYING || state == State.LOADING) {
                resumeOnFocusGain = true;
                pauseInternal(false); // نُبقي الطلب حتى يصلنا GAIN
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            try {
                if (player != null) player.setVolume(0.25f, 0.25f);
            } catch (Throwable ignored) {
            }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            try {
                if (player != null) player.setVolume(1f, 1f);
            } catch (Throwable ignored) {
            }
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                resume();
            }
        }
    });

    private void requestFocus() {
        if (audio == null || focusHeld) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener, main)
                        .build();
                audio.requestAudioFocus(focusRequest);
            } else {
                //noinspection deprecation
                audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
            focusHeld = true;
        } catch (Throwable ignored) {
        }
    }

    private void abandonFocus() {
        focusHeld = false;
        if (audio == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
            } else {
                //noinspection deprecation
                audio.abandonAudioFocus(focusListener);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ الإغلاق

    void shutdown() {
        try {
            SpeechLearner.flush();
            LocalVoiceModel.flush();
        } catch (Throwable ignored) {
        }
        session++;
        pendingAfterInit = null;
        speakToken++;
        PdfOcr.setStatusSink(null);
        PdfOcr.cancelWarm();
        releasePlayer();
        resetCloud();
        abandonFocus();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {
        }
        tts = null;
        synthPool.shutdownNow();
        envPool.shutdownNow();
        releasePreview();
        releaseEffects();
        releaseNext();
        io.execute(() -> {
            if (source != null) source.close();
            source = null;
        });
        io.shutdown();
        cleanCacheDir();
    }
}
