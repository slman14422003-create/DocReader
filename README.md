# قارئ المستندات (DocReader)

تطبيق أندرويد بلغة **Java بالكامل** لعرض مستندات Word و PDF و Excel و PowerPoint وملفات نصية،
بواجهة مستخدم مستوحاة من تصميم Claude AI (ألوان دافئة، بطاقات ناعمة، لون تمييز برتقالي مطفي).

## بنية المشروع
```
DocReader/
├── app/
│   ├── src/main/java/com/docreader/app/
│   │   ├── MainActivity.java              الشاشة الرئيسية + اختيار الملف
│   │   ├── DocumentViewerActivity.java    منطق العرض حسب نوع الملف
│   │   ├── viewer/   DocxParser, XlsxParser, PptxParser (قراءة مباشرة من XML داخل الملف)
│   │   ├── ui/       المحولات (Adapters) الخاصة بكل نوع عرض
│   │   ├── model/    نموذج RecentFile
│   │   └── util/     أدوات مساعدة (تحديد النوع، تخزين الملفات الأخيرة)
│   └── src/main/res/  التخطيطات والألوان والثيم
└── .github/workflows/android-build.yml   بناء تلقائي عبر GitHub Actions
```

## كيف يعمل عرض كل صيغة
| الصيغة | الطريقة |
|---|---|
| **PDF** | `android.graphics.pdf.PdfRenderer` (مدمجة بالنظام) — يعرض كل صفحة كصورة عالية الدقة داخل `ViewPager2` |
| **Word (docx)** | قراءة `word/document.xml` من داخل الملف (وهو أصلاً أرشيف ZIP) عبر `XmlPullParser`، واستخراج الفقرات والعناوين |
| **Excel (xlsx)** | قراءة `xl/worksheets/sheet1.xml` + `xl/sharedStrings.xml` وعرضها كجدول (`TableLayout`) قابل للتمرير أفقيًا وعموديًا |
| **PowerPoint (pptx)** | قراءة `ppt/slides/slideN.xml` واستخراج نص كل شريحة، تُعرض كبطاقات ضمن `ViewPager2` |
| **نصوص (txt/md)** | قراءة مباشرة وعرضها كنص |

> **لماذا هذا الأسلوب بدل مكتبة مثل Apache POI؟**
> مكتبة Apache POI (المستخدمة عادة لملفات Office على جافا) تسبب مشاكل توافق معروفة على أندرويد
> (تعتمد على أجزاء من `javax.xml.stream` غير مكتملة في نظام أندرويد). لذلك تم بناء قارئ خفيف
> يعتمد فقط على أدوات أندرويد الأساسية (`ZipInputStream` + `XmlPullParser`)، وهذا يضمن أن المشروع
> **يُبنى وينجح دائمًا عبر GitHub Actions** دون مفاجآت. هذا يغطي استخراج **النص والجداول الأساسية**؛
> لا يعرض تنسيقات متقدمة جدًا (كائنات رسومية، أشكال SmartArt، تنسيق خط دقيق) — يمكن تطويره لاحقًا.
> ملفات `.doc/.xls/.ppt` القديمة (بصيغة Binary وليست XML) غير مدعومة حاليًا؛ فقط الصيغ الحديثة `.docx/.xlsx/.pptx`.

## التصميم (ثيم Claude)
- خلفية دافئة فاتحة `#FAF9F6` وبطاقات بيضاء ناعمة بحواف مدورة.
- لون تمييز `#CC785C` (البرتقالي المطفي المميز لهوية Claude).
- عناوين بخط Serif وفقرات بخط Sans-serif نظيف، بذات روح واجهة claude.ai.
- تدعم الواجهة العربية (RTL) بشكل كامل.

## خطوات النشر عبر GitHub

1. أنشئ مستودع (Repository) جديد فارغ على GitHub.
2. من مجلد المشروع محليًا:
   ```bash
   git init
   git add .
   git commit -m "الإصدار الأول من تطبيق قارئ المستندات"
   git branch -M main
   git remote add origin https://github.com/USERNAME/REPO_NAME.git
   git push -u origin main
   ```
3. بمجرد الدفع (push)، سيعمل `GitHub Actions` تلقائيًا (الملف: `.github/workflows/android-build.yml`) لبناء:
   - **APK تجريبي (Debug)** يظهر كـ *Artifact* داخل تبويب Actions لكل تشغيل.
   - **APK غير موقّع (Release)** يُنشر تلقائيًا ضمن تبويب *Releases* عند كل دفعة إلى `main`.
4. لتثبيت الـ APK على جهازك: حمّله من تبويب Actions أو Releases، فعّل "تثبيت من مصادر غير معروفة"، ثم ثبّته.

### ملاحظة مهمة: Gradle Wrapper
هذا المشروع **لا يتضمن** ملفات `gradlew` / `gradle-wrapper.jar` الثنائية (لأنها بحاجة تحميل من الإنترنت
عند توليدها). بدلًا من ذلك، الـ workflow يستخدم إجراء `gradle/actions/setup-gradle` الذي يجهّز Gradle
مباشرة على الخادم — لذلك **البناء عبر GitHub Actions يعمل بدون أي خطوة إضافية منك**.

إن رغبت بفتح المشروع محليًا عبر **Android Studio** لاحقًا: افتح المشروع فيه مباشرة وسيقوم تلقائيًا
بتوليد ملفات الـ wrapper المفقودة عند أول مزامنة (Gradle Sync) — لا حاجة لأي إجراء يدوي.

## التطوير المستقبلي المقترح
- دعم عدة أوراق عمل (Sheets) في ملف Excel وليس فقط الأولى.
- دعم عرض الصور المضمّنة داخل شرائح PowerPoint وفقرات Word.
- إضافة بحث نصي داخل المستند المفتوح.
- دعم صيغ `.doc/.xls/.ppt` القديمة عبر مكتبة تحويل خادمية أو محلية إضافية.
- توقيع إصدار الـ Release تلقائيًا (حاليًا غير موقّع لأغراض الاختبار فقط).
