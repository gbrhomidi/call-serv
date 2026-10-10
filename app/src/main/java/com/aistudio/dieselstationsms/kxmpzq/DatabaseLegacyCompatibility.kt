package com.aistudio.dieselstationsms.kxmpzq

import org.json.JSONArray
import org.json.JSONObject

/*
 * The checked-in legacy HTTP service references a separate fuel-station schema that is not
 * present in this telecom database. These safe compatibility adapters keep those endpoints
 * read-only/empty rather than inventing successful writes against unrelated tables.
 */
internal fun DatabaseHelper.authenticateUser(username: String, password: String): JSONObject? = null
internal fun DatabaseHelper.getUserByUsername(username: String): JSONObject? = null
internal fun DatabaseHelper.getRefills(): JSONArray = JSONArray()
internal fun DatabaseHelper.getRefill(id: Int): JSONObject? = null
internal fun DatabaseHelper.updateRefillQty(id: Int, quantity: Double, operator: String): Boolean = false
internal fun DatabaseHelper.insertTransaction(
    customerId: Int, refillId: Int, quantity: Double, unitPrice: Double, paid: Double,
    due: Double, method: String, dueDate: String, paymentType: String, operator: String
): Long = -1L
internal fun DatabaseHelper.getTransactionById(id: Long): JSONObject? = null
internal fun DatabaseHelper.processPayment(customerId: Int, amount: Double, operator: String): Boolean = false
internal fun DatabaseHelper.getDailySales(): JSONArray = JSONArray()
internal fun DatabaseHelper.getMonthlySales(): JSONArray = JSONArray()
internal fun DatabaseHelper.getEodReport(): JSONObject = JSONObject()
internal fun DatabaseHelper.getOverdueTransactions(): JSONArray = JSONArray()
internal fun DatabaseHelper.exportAllData(): JSONObject = JSONObject().apply {
    put("customers", getCustomers())
    put("services", getServices())
    put("suppliers", getSuppliers())
    put("transfers", getTransfers())
    put("activations", getActivations())
    put("settings", getAllSettings())
}
internal fun DatabaseHelper.getSmsLogs(): JSONArray = getSmsMetrics()
internal fun DatabaseHelper.getActivityLogs(): JSONArray = getAuditLog()
internal fun DatabaseHelper.getCustomerReport(customerId: Int): JSONArray = getCustomerActivations(customerId)
internal fun DatabaseHelper.searchTransactions(query: String): JSONArray = JSONArray()
internal fun DatabaseHelper.getInventoryAlerts(): JSONArray = JSONArray()
internal fun DatabaseHelper.markAlertRead(alertId: Int): Boolean = false
internal fun DatabaseHelper.getLoyaltyHistory(customerId: Int): JSONArray = JSONArray()
internal fun DatabaseHelper.redeemLoyaltyPoints(customerId: Int, points: Int, description: String): Boolean = false
internal fun DatabaseHelper.logActivity(operator: String, action: String, details: String): Long = recordEvent("LEGACY_$action", details = "$operator: $details")
internal fun DatabaseHelper.getCustomer(customerId: Int): JSONObject? = getCustomerById(customerId)
