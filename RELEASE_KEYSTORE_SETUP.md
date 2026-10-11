# إعداد مفتاح توقيع الإنتاج (Release Keystore)

## لماذا نحتاج المفتاح نفسه؟

يتحقق Android عند تحديث التطبيق من تطابق **معرّف الحزمة** وشهادة التوقيع. في هذا المشروع المعرّف هو `com.aistudio.dieselstationsms.kxmpzq`. إذا كانت النسخة الجديدة موقعة بمفتاح مختلف، يرفضها Android كتحديث؛ أما استخدام المفتاح نفسه مع `versionCode` أعلى فيسمح بالتحديث فوق النسخة الحالية مع الاحتفاظ ببيانات التطبيق.

> **مهم لهذه الحالة:** ملف APK الذي يرفعه فرع `feat/sms-activation-bank-reconciliation` هو بناء Debug موقع بمفتاح مؤقت من GitHub Actions. لا يمكن استبداله مباشرة بنسخة Release ذات مفتاح إنتاجي مختلف. يلزم الانتقال إلى مفتاح Release مرة واحدة، وقد يتطلب ذلك إزالة نسخة Debug الموجودة وإعادة تثبيت Release؛ إزالة التطبيق تمسح بياناته المحلية. بعد ذلك، ما دام مفتاح Release نفسه محفوظاً وثابتاً، يمكن تثبيت إصدارات Release اللاحقة كتحديثات عادية دون حذف التطبيق.

إذا كانت النسخة المثبتة موقعة أصلاً بشهادة إنتاج سابقة، فاستعد **ملف keystore الأصلي** بدلاً من إنشاء مفتاح جديد. لا يمكن تغيير توقيع التطبيق المثبت بمفتاح مختلف مع ضمان بقاء بياناته. إذا فُقد مفتاح التوقيع القديم ولم يوجد backup، لا يستطيع هذا الدليل استعادته.

## 1. إنشاء المفتاح مرة واحدة

أنشئه على كمبيوتر موثوق غير مشترك، خارج مجلد المشروع ومستودع Git. لا ترسل المفتاح أو كلمة مروره إلى أي شخص.

```bash
mkdir -p "$HOME/secure-signing/call-serv"
cd "$HOME/secure-signing/call-serv"
umask 077
keytool -genkeypair -v \
  -keystore callserv-release.p12 \
  -storetype PKCS12 \
  -alias callserv-release \
  -keyalg RSA -keysize 4096 -validity 10000
```

أدخل كلمة مرور قوية وفريدة عند مطالبة `keytool` بها. لا تضف كلمة المرور إلى الأمر نفسه أو إلى ملف داخل المشروع. احتفظ باسم الملف والـalias وكلمتي مرور keystore/key في مدير كلمات مرور مؤسسي/موثوق. في PKCS12 قد تكون كلمة مرور المفتاح مطابقة لكلمة مرور keystore حسب طريقة الإنشاء؛ استخدم القيم التي أدخلتها عند إعداد GitHub Secrets.

## 2. فحص المفتاح وحفظ بصمة الشهادة

```bash
keytool -list -v -keystore callserv-release.p12 -alias callserv-release
keytool -exportcert -rfc -keystore callserv-release.p12 -alias callserv-release \
  | openssl x509 -noout -fingerprint -sha256
```

تحقق أن نوع الملف PKCS12 وأن alias صحيح، وسجّل بصمة `SHA256` في مكان آمن. احتفظ بنسختين احتياطيتين مشفرتين من ملف `.p12` في موقعين آمنين منفصلين. **لا ترفع keystore إلى GitHub ولا ترفقه كـartifact ولا ترسله في المحادثة.**

## 3. إضافة GitHub Actions Secrets

من المستودع `gbrhomidi/call-serv` افتح:

**Settings → Secrets and variables → Actions → New repository secret**

أنشئ الأسرار التالية بالأسماء المطابقة تماماً لـ`.github/workflows/android.yml`:

| اسم السر | القيمة |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | محتوى ملف keystore بعد ترميزه Base64 دون التفاف أسطر |
| `RELEASE_KEYSTORE_PASSWORD` | كلمة مرور ملف keystore |
| `RELEASE_KEY_ALIAS` | `callserv-release` أو alias الفعلي الذي أنشأته |
| `RELEASE_KEY_PASSWORD` | كلمة مرور المفتاح/alias التي طلبها Gradle |

على Linux، خذ قيمة Base64 للنسخ إلى حقل السر هكذا:

```bash
base64 -w 0 callserv-release.p12
```

لا تحفظ المخرجات داخل المشروع ولا تلصقها في محادثة أو issue. على PowerShell في Windows يمكن إنتاج القيمة بـ:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\secure\callserv-release.p12"))
```

أدخل القيمة مباشرة في GitHub Secrets ثم أغلق/امسح حافظة النسخ المؤقتة. لا يلزم إظهار قيم الأسرار في logs. إذا كنت تستخدم GitHub CLI على جهاز موثوق، يمكن إرسال Base64 عبر stdin بدلاً من طباعته:

```bash
base64 -w 0 callserv-release.p12 | gh secret set RELEASE_KEYSTORE_BASE64 --repo gbrhomidi/call-serv
```

وأدخل كلمات المرور وalias من مطالبة `gh secret set SECRET_NAME` التفاعلية، لا كوسيط ظاهر في سجل shell.

## 4. اختبار إصدار Release

1. لا تغيّر `applicationId` أو ملف التوقيع/alias في الإصدارات اللاحقة.
2. ارفع `versionCode` إلى قيمة أكبر من النسخة المثبتة في كل إصدار جديد؛ النسخة الحالية بالمشروع `301`.
3. ادفع التغييرات إلى `main` بعد مراجعتها. سير العمل يقصر الوصول لأسرار التوقيع على `main`، ويتحقق من توقيع APK ومعرّف الحزمة ورقم الإصدار قبل رفع artifact `CallServ_SMS_Activation_Release`.
4. نزّل APK وملف `SHA256SUMS.txt` من تشغيل Actions نفسه، وتحقق محلياً:

```bash
sha256sum -c SHA256SUMS.txt
```

5. ثبّت APK على الهاتف. إذا كانت الشهادة والمعرّف متطابقين ورقم الإصدار أعلى، يحدّث Android التطبيق مع بياناته. إذا ظهر `App not installed` أو `signature mismatch`، **لا تمسح بيانات التطبيق فوراً**؛ توقف وتحقق من بصمة شهادة النسخة الحالية ومفتاح Release قبل اتخاذ قرار إزالة النسخة.

## حماية المفتاح مستقبلاً

- لا تنشئ keystore جديداً لكل إصدار، ولا تدوّر المفتاح إذا كان الهدف تحديث التطبيق المثبت.
- احتفظ بملف المفتاح وكلمات مروره والـalias وبصمة الشهادة بشكل منفصل وآمن، مع مالك بديل مخوّل.
- لا تضع الأسرار في `gradle.properties` الملتزم بالمستودع أو في source code أو workflow YAML.
- لا تستخدم APK Debug من فرع الميزة كتحديث إنتاجي. استخدمها فقط للاختبار أو التثبيت النظيف، وانتقل إلى Release بتوقيع ثابت مرة واحدة.
