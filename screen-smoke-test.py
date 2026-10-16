#!/usr/bin/env python3
"""Fast static integration smoke checks for the SMS activation UI and Android wiring."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent

def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise SystemExit(f"FAIL: required project file is missing: {relative}")
    return path.read_text(encoding="utf-8")

html = read("app/src/main/assets/web_interface.html")
activity = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt")
receiver = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SmsReceiver.kt")
processor = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SmsCommandProcessor.kt")
analysis = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SmsTextAnalysis.kt")
store = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SmsActivationStore.kt")
manifest = read("app/src/main/AndroidManifest.xml")
gradle_build = read("app/build.gradle.kts")
strings = read("app/src/main/res/values/strings.xml")

checks = {
    "installer label and application id identify the telecom activation app": (
        '<string name="app_name">تفعيل خدمات شركات الاتصالات</string>' in strings
        and 'applicationId = "com.aistudio.telecomactivation"' in gradle_build
        and 'android:label="@string/app_name"' in manifest
    ),
    "bank name/account/sender/balance and provider settings are on the settings screen": all(
        f'id="{element}"' in html
        for element in (
            "systemBankName",
            "systemBankAccountName",
            "systemBankSenderPhone",
            "systemBankExpectedBalance",
            "activationProviderPhone",
            "activationProviderBalance",
            "supplierBalanceQuery",
            "smsServiceSelect",
            "fullActivationCommand",
        )
    ),
    "the settings screen loads/saves the service command through the Android bridge": all(
        token in html for token in ("getSmsServiceCatalog", "setServiceActivationTemplate", "saveSelectedSmsCommand")
    ) and all(token in activity for token in ("fun getSmsServiceCatalog()", "fun setServiceActivationTemplate(")),
    "incoming SMS receiver is registered and protected by Android SMS permissions": all(
        token in manifest for token in (
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.CALL_PHONE",
            "android.provider.Telephony.SMS_RECEIVED",
            "android.permission.BROADCAST_SMS",
        )
    ) and "goAsync()" in receiver,
    "a strictly matched bank payment dispatches the authorized request to the USSD path": (
        "store.acceptBankPayment(sender, bankName, notice)" in processor
        and 'dispatchUssd(context, request.getLong("id"), store)' in processor
        and "sendUssdRequest(command" in processor
    ),
    "the full USSD template substitutes the customer phone and rejects unsafe characters": all(
        token in analysis for token in ("fun buildUssdCommand(", "normalizePhone(phone)", "marker.replace(template)")
    ),
    "activation orders and bank reconciliation persist in SQLite": all(
        token in store for token in ("sms_activation_requests", "fun acceptBankPayment(", "status='awaiting_payment'")
    ),
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {name}")
if failed:
    sys.exit(f"{len(failed)} SMS activation smoke check(s) failed.")
print(f"All {len(checks)} SMS activation smoke checks passed.")
