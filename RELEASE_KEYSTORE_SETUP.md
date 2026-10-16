# إعداد أسرار توقيع الإنتاج والتحقق منها

## أسماء الأسرار التي يقرأها المشروع الآن

يتحقق GitHub Actions عند الدفع إلى فرع `feat/sms-activation-bank-reconciliation` من أربعة أسرار Actions بالأسماء التالية **كما هي تماماً**:

| اسم السر | المطلوب |
|---|---|
| `KEYSTORE_BASE64` | محتوى ملف keystore الحالي بعد ترميزه Base64 دون التفاف أسطر |
| `KEYSTORE_PASSWORD` | كلمة مرور keystore |
| `KEY_ALIAS` | يجب أن تكون `call-serv` |
| `KEY_PASSWORD` | كلمة مرور المفتاح/alias |

بعد أول تحقق موثوق، يطبع Job `Verify Release Signing Secrets` بصمة شهادة `SHA-256` في **Summary**. راجعها مع بصمة المفتاح الإنتاجي الموثوق، ثم أضف السر:

| اسم السر | القيمة |
|---|---|
| `EXPECTED_CERT_SHA256` | بصمة الشهادة الموثوقة، مع النقطتين أو من دونهما |

على فرع الميزة، يمكن أن يغيب `EXPECTED_CERT_SHA256` في أول تشغيل؛ يعرض Summary البصمة ويطلب تثبيتها. على `main` يصبح هذا السر إلزامياً، ولا يصدر workflow APK Release قبل تطابق البصمة. لا تعتمد بصمة من تشغيل Pull Request أو من فرع/commit غير موثوق.

## استخدم keystore الإنتاج الموجود

لا تنشئ مفتاحاً جديداً ولا تغيّر alias لمجرد مواءمة أسماء الأسرار؛ تغيير شهادة توقيع التطبيق قد يمنع تحديث النسخة المثبتة. استخدم ملف التوقيع الإنتاجي الحالي الذي يعود إليك وتحقق من alias قبل رفع السر:

```bash
keytool -list -v -keystore /path/to/call-serv.jks -alias call-serv
```

أدخل كلمة المرور عند المطالبة بها، ولا تضفها إلى الأمر أو Git. إذا لم يظهر alias `call-serv` أو كانت شهادة الملف مختلفة عن نسخة الإنتاج الحالية، **أوقف الإصدار ولا تعِد تسمية/تستبدل المفتاح عشوائياً**؛ يلزم تحديد مفتاح التوقيع الذي وقّع النسخة المنشورة فعلاً.

## ترميز الملف وإضافة الأسرار

من مستودع `gbrhomidi/call-serv` افتح:

**Settings → Secrets and variables → Actions → New repository secret**

على Linux، ولنسخ Base64 مباشرة إلى حقل السر:

```bash
base64 -w 0 /path/to/call-serv.jks
```

لا تحفظ المخرجات في مجلد المشروع ولا تلصق المفتاح أو كلمات المرور في issue أو محادثة. على PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\secure\call-serv.jks"))
```

أدخل الأسرار الأربعة بالأسماء في الجدول أعلاه. احتفظ بـ`KEY_ALIAS=call-serv` كما هو. بعد مراجعة بصمة Summary من تشغيل موثوق، أضف `EXPECTED_CERT_SHA256` كما هو موضح.

إذا كنت تستخدم GitHub CLI على جهاز موثوق، يمكن إرسال Base64 من stdin دون طباعته:

```bash
base64 -w 0 /path/to/call-serv.jks | gh secret set KEYSTORE_BASE64 --repo gbrhomidi/call-serv
```

أدخل كلمات المرور وalias تفاعلياً عبر `gh secret set SECRET_NAME`، لا كوسيط ظاهر في سجل shell. قيم الأسرار لا يمكن قراءتها مجدداً عبر GitHub؛ يمكن استبدالها فقط.

## سلوك workflow وحدود الأسرار

هذا الفحص يراقب سلامة إعداد توقيع APK فقط، ولا يقرأ Logcat من الهواتف المنتجة. حالة ومتطلبات المراقبة التشغيلية موضحة في [دليل مراقبة Logcat الإنتاجية](PRODUCTION_LOGCAT_MONITORING.md).

- `verify-secrets` يعمل عند الدفع/التشغيل اليدوي على `main` أو فرع الميزة، ويتحقق من وجود الأسرار، وصحة alias، وإمكانية فتح keystore، ثم يحسب SHA-256 ويقارنه بالـpin إن أُضيف.
- Pull Requests تشغّل الاختبارات والبناء العام **من دون إتاحة أسرار التوقيع**؛ هذا حد أمني مقصود، وليس عائقاً لتجاوز التحقق.
- إعداد Gradle يقرأ `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. يستخرج workflow ملف المفتاح إلى مجلد مؤقت خارج مساحة المشروع، ويحذفه بعد الاستخدام.
- `KEYSTORE_FILE` متغير مسار محلي/مؤقت، وليس Secret باسم `KEYSTORE_FILE`.
- APK Debug المرفوعة من فرع الميزة موقعة بمفتاح تطوير مؤقت، وهي للتثبيت النظيف فقط. الإصدار الموقع للإنتاج يصدر على `main` بعد نجاح التحقق.
- لا تضع ملف keystore أو `keystore.properties` أو Base64 في Git. الملف `keystore.properties.example` نموذج بلا بيانات حقيقية؛ انسخه محلياً إلى `keystore.properties` عند الحاجة.

## فحص الملفات الحساسة قبل الدفع

```bash
git ls-files | grep -Ei '(\.jks$|\.keystore$|base64|keystore\.properties$)' || true
git status --short
```

المتوقع ألا يظهر ملف keystore أو خصائص محلية متتبعة. راجع أي نتيجة يدوياً قبل الدفع.

## إصدار Production APK وتحديث التطبيق

1. ادفع التغييرات المراجعة مباشرة إلى `feat/sms-activation-bank-reconciliation`؛ تحقق الأسرار سيظهر في تشغيل Actions لذلك الدفع.
2. راجع Summary، وتأكد أن بصمة SHA-256 تطابق المفتاح الذي وقّع نسخة الإنتاج، ثم أضفها كسر `EXPECTED_CERT_SHA256`.
3. بعد الدمج/التحديث على `main`، ينفذ release job البناء ويقارن شهادة APK الناتجة بالبصمة المثبتة، ثم يرفع `CallServ_SMS_Activation_Release` و`SHA256SUMS.txt`.
4. تحقّق من checksum بعد تنزيل الملف:

```bash
sha256sum -c SHA256SUMS.txt
```

إذا ظل `applicationId` كما هو (`com.aistudio.dieselstationsms.kxmpzq`)، وظل مفتاح التوقيع نفسه وارتفع `versionCode`، يثبت Android التحديث مع الاحتفاظ بالبيانات. إذا ظهر `signature mismatch` فتوقف ولا تحذف التطبيق قبل معرفة الشهادة المثبتة؛ إزالة التطبيق تمحو بياناته المحلية.
