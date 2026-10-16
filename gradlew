#!/bin/sh

##############################################################################
##
##  نظام تفعيل خدمات الاتصالات - Gradle Wrapper
##  الإصدار 3.0 - تم إنشاؤه بواسطة Gradle
##
##  هذا الملف هو النص البرمجي لتشغيل Gradle باستخدام Gradle Wrapper
##  يتم استخدامه لتشغيل مهام البناء دون الحاجة إلى تثبيت Gradle محلياً.
##
##############################################################################

# محاولة تعيين APP_HOME
APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit

APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}

# إعدادات JVM الافتراضية - يمكن تعديلها حسب احتياجات المشروع
# تم زيادة الذاكرة لتحسين أداء البناء
DEFAULT_JVM_OPTS='"-Xmx512m" "-Xms256m"'

# استخدام الحد الأقصى المتاح، أو تعيين MAX_FD != -1 لاستخدام تلك القيمة
MAX_FD=maximum

# دالة لإظهار التحذيرات
warn () {
    echo "$*"
} >&2

# دالة لإظهار الأخطاء وإنهاء التنفيذ
die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# دعم أنظمة التشغيل المختلفة
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in                #(
  CYGWIN* )         cygwin=true  ;; #(
  Darwin* )         darwin=true  ;; #(
  MSYS* | MINGW* )  msys=true    ;; #(
  NONSTOP* )        nonstop=true ;;
esac

# تحديد مسار Gradle Wrapper JAR
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# تحديد أمر Java لبدء JVM
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD=java
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
fi

# جمع جميع الوسائط لأمر java
set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -classpath "$CLASSPATH" \
        org.gradle.wrapper.GradleWrapperMain \
        "$@"

# التحقق من توفر xargs
if ! command -v xargs >/dev/null 2>&1
then
    die "xargs is not available"
fi

# استخدام "xargs" لتحليل الوسائط المقتبسة
eval "set -- $(
        printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
        xargs -n1 |
        sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' |
        tr '\n' ' '
    )" '"$@"'

# تنفيذ أمر Java
exec "$JAVACMD" "$@"


##############################################################################
##  نهاية الملف
##############################################################################