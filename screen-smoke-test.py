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
database = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt")
web_api = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/TelecomWebApi.kt")
receiver = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SmsReceiver.kt")
processor = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SmsCommandProcessor.kt")
analysis = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SmsTextAnalysis.kt")
store = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SmsActivationStore.kt")
webhook = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/FinancialConflictWebhook.kt")
webhook_worker = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/FinancialConflictWebhookWorker.kt")
manifest = read("app/src/main/AndroidManifest.xml")
gradle_build = read("app/build.gradle.kts")
strings = read("app/src/main/res/values/strings.xml")
application = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MyApplication.kt")
backup_worker = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/BackupWorker.kt")
compatibility = read("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseLegacyCompatibility.kt")

checks = {
    "installer label and application id identify the telecom activation app": (
        '<string name="app_name">تفعيل خدمات شركات الاتصالات</string>' in strings
        and 'applicationId = "com.aistudio.telecomactivation"' in gradle_build
        and 'android:label="@string/app_name"' in manifest
    ),
    "SMS bank/account settings are present without legacy balance controls": all(
        f'id="{element}"' in html
        for element in ("systemBankName", "systemBankAccountName", "systemBankSenderPhone")
    ) and 'id="serviceActivationCommand"' in html
    and "system_bank_expected_balance" not in processor
    and "system_bank_expected_balance" not in store,
    "telecom company save is scoped to its own form and the name ID is unique": (
        html.count('id="telecomName"') == 1
        and "new FormData(form).get('name')" in html
        and 'form.querySelector(`#${id}`)' in html
        and "save_telecom_company" in web_api
    ),
    "service form retains the full USSD template editor": all(
        token in html for token in ('id="serviceActivationCommand"', "async function saveService", "activation_command:")
    ) and '"activation_command"' in web_api,
    "incoming SMS receiver is registered and protected by Android SMS permissions": all(
        token in manifest for token in (
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.CALL_PHONE",
            "android.provider.Telephony.SMS_RECEIVED",
            "android.permission.BROADCAST_SMS",
        )
    ) and "SmsInboundWorker" in receiver and "enqueueUniqueWork" in receiver,
    "a strictly matched bank payment dispatches the authorized request to the USSD path": (
        "store.acceptBankPayment(sender, bankName, notice," in processor
        and 'dispatchUssd(context, request.getLong("id"), store)' in processor
        and "sendUssdRequest(command" in processor
    ),
    "the full USSD template substitutes the customer phone and rejects unsafe characters": all(
        token in analysis for token in ("fun buildUssdCommand(", "normalizePhone(phone)", "marker.replace(command")
    ) and "foundMarker" in analysis and "command.matches(Regex" in analysis,
    "activation orders and bank reconciliation persist in SQLite": all(
        token in store for token in ("sms_activation_requests", "fun acceptBankPayment(", "status='awaiting_payment'")
    ),
    "visible reports use dedicated database queries and safe DOM rendering": all(
        token in html for token in ("function renderReportData", "generate_report", "reportModalBody")
    ) and all(
        token in web_api for token in ("getOperationalReport(type)", "full_financial")
    ) and all(
        token in database for token in ("fun getOperationalReport(", "sms_summary", "customers_credit", "services_pricing")
    ),
    "service/customer/SMS exports stream complete SQLite rows to Android file sharing": all(
        token in html for token in ("function exportCollection", "exportCollection('services'", "exportCollection('customers'", "exportCollection('sms'")
    ) and "fun exportCollection(collection: String)" in activity
    and "fun writeExportRows(collection: String" in database,
    "NanoHTTPD and the persistent foreground SMS service are absent": (
        "nanohttpd" not in gradle_build.lower()
        and ".SMSService" not in manifest
        and "FOREGROUND_SERVICE" not in manifest
        and not (ROOT / "app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SMSService.kt").exists()
    ),
    "SMS reception remains broadcast-driven and periodic backup uses WorkManager": (
        "android.provider.Telephony.SMS_RECEIVED" in manifest
        and "enqueueUniquePeriodicWork" in application
        and "auto_backup_work" in application
        and "PeriodicWorkRequestBuilder<BackupWorker>" in application
        and all(token in backup_worker for token in ("services", "suppliers", "transfers", "activations", "settings"))
        and all(token in compatibility for token in ("put(\"services\"", "put(\"suppliers\"", "put(\"transfers\"", "put(\"activations\"", "put(\"settings\""))
    ),
    "Dashboard, review and webhook lifecycle are integrated": all(token in store for token in ("sms_webhook_deliveries", "enqueueWebhookDelivery", "markWebhookResult", "webhookStats"))
        and all(token in webhook for token in ("event_id", "DATA_DELIVERY_ID", "enqueueWebhookDelivery"))
        and all(token in webhook_worker for token in ("markWebhookAttempt", "FinancialConflictWebhookPolicy", "Result.retry()"))
        and all(token in web_api for token in ("pending_activation_reviews", "get_webhook_deliveries", "webhook_failed"))
        and all(token in html for token in ("dashPendingReviews", "dashWebhookPending", "webhookDeliveriesTable", "loadWebhookDeliveries")),
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {name}")
if failed:
    sys.exit(f"{len(failed)} SMS activation smoke check(s) failed.")
print(f"All {len(checks)} SMS activation smoke checks passed.")
