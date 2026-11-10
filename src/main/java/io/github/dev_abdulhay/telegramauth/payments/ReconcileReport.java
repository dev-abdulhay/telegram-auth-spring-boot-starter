package io.github.dev_abdulhay.telegramauth.payments;

/** Counts from one {@link StarPaymentReconciler#reconcile()} run. */
public record ReconcileReport(int recovered, int redelivered, int expiredOrders, int purgedOrders,
                              int expiredSubscriptions) {
}
