# Telegram Stars Payments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a switchable Telegram Stars payments module (invoices, pre-checkout, idempotent completion, subscriptions, refunds, reconciliation) plus an optional internal balance ledger, working on the main bot and on white-label tenant bots.

**Architecture:** Bot transport gains payment methods/hooks; a core `payments` package (catalog, order/subscription stores, service, update handler, reconciler, auto-config) sits on top; an optional `payments.balance` package (ledger + `TopUpCreditor`) plugs into payment events. Every bean is `@ConditionalOnMissingBean`/`ObjectProvider`-optional, mirroring `managedbots`.

**Tech Stack:** Java 17, Spring Boot 3.3.5 (autoconfigure, Data JPA provided-scope), Jackson, JUnit 5 + AssertJ, WireMock, H2. No Lombok. Build: `mvn -q test` from repo root.

**Spec:** `docs/superpowers/specs/2026-11-10-telegram-stars-payments-design.md`

## Global Constraints

- Root package `io.github.dev_abdulhay.telegramauth`; new packages `payments` and `payments.balance`.
- Currency `XTR`, `provider_token` sent as empty string, exactly one `LabeledPrice`.
- Invoice payload `"so:" + orderId`; orderId = 32 lowercase hex chars (UUID without dashes).
- `subscription_period = 2592000`; subscription price 1…10000 ⭐; product title 1–32 chars, description 1–255 chars.
- `pre-checkout-timeout` default `5s`, must be `< 10s` (fail fast at startup).
- Property defaults: `order-ttl=30m`, `order-retention=90d`, `reconcile.enabled=true`, `reconcile.interval=15m`, `reconcile.subscription-grace=1h`, `support.enabled=true`, `top-up.enabled=false`, `top-up.min-stars=10`, `top-up.max-stars=10000`, `top-up.credits-per-star=1`, `balance.enabled=false` (separate switch), `balance.scope=GLOBAL`, `balance.refund-policy=REQUIRE_BALANCE`.
- In-memory default stores log one WARN: "… is in-memory — not for production".
- No Lombok; plain getters/setters like `BaseManagedBotIntent`. Comments in English, Javadoc on public types.
- Commit messages: Conventional Commits, **no AI attribution trailers** (user rule).
- Version `0.6.0`; README + CHANGELOG updated in the last task.

## Refinements of the spec (decided while planning)

- Events receive `StarOrder` (it already carries credits/product/charge) instead of a separate `PaidOrder`.
- `reconcile()` lives on `StarPaymentReconciler`, not `StarPaymentService`.
- At-least-once delivery: orders get `fulfilled_at`; listeners that throw leave the order unfulfilled and the reconciler redelivers, so listeners must be idempotent (the ledger is, via refKey).
- Refund policy check is a `StarRefundGuard` hook (implemented by `TopUpCreditor`), so core stays ledger-agnostic.
- Renewal vs first payment is decided from stored state (original order already PAID with another charge), not only from `is_recurring`, so reconciler-recovered renewals behave the same.

## Review Focus

1. Duplicate `successful_payment` (Telegram redelivery or reconciler racing the update) must credit exactly once — Task 7 and Task 11 tests.
2. A subscription renewal reuses the **original** payload; it must never re-mark the original order nor re-fire `onPaid` — Task 7 test `renewalCreatesNewOrderAndFiresRenewed`.
3. A listener (ledger, host) throwing in `onPaid` must not lose the credit: the order stays unfulfilled and the reconciler redelivers — Task 8 test `unfulfilledPaidOrderIsRedelivered`.
4. Concurrent `debit`s on the same account never overdraw — Task 10 contract test `concurrentDebitsNeverOverdraw`.
5. With managed bots on, `allowed_updates` must contain `pre_checkout_query` once a payments handler exists — Task 2 test.

---

### Task 1: TelegramBot payment API methods

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBot.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/StarPaymentsApiTest.java` (WireMock, same setup as `ManagedBotApiTest`)

**Interfaces — Produces** (all public, all via existing `postForResult`, throw `TelegramApiException`):
```java
JsonNode sendInvoice(long chatId, String title, String description, String payload, int stars) // returns Message
String   createInvoiceLink(String title, String description, String payload, int stars, Integer subscriptionPeriod) // null = one-off
void     answerPreCheckoutQuery(String queryId, boolean ok, String errorMessage) // errorMessage only when !ok
void     refundStarPayment(long userId, String telegramPaymentChargeId)
void     editUserStarSubscription(long userId, String telegramPaymentChargeId, boolean canceled)
JsonNode getStarTransactions(int offset, int limit) // returns result object {transactions:[...]}
long     getMyStarBalance() // result.amount
```
Form params: `currency=XTR`, `provider_token=` (empty), `prices=[{"label":<title>,"amount":<stars>}]` URL-encoded JSON (build with Jackson `MAPPER`), `subscription_period` only when non-null.

- [ ] Step 1: Tests: `sendInvoiceSendsXtrAndSinglePrice` (body contains `currency=XTR`, `provider_token=&` or trailing, decoded `prices` equals `[{"label":"Pack","amount":100}]`, `payload=so%3Aabc`); `createInvoiceLinkReturnsUrlAndPassesSubscriptionPeriod` (`subscription_period=2592000`); `createInvoiceLinkOmitsPeriodWhenNull`; `answerPreCheckoutRejectionCarriesMessage` (`ok=false`, `error_message=...`); `answerPreCheckoutOkOmitsMessage`; `refundAndEditSubscriptionPostUserAndCharge`; `getMyStarBalanceReadsAmount` (`{"ok":true,"result":{"amount":42}}` → 42); `apiErrorThrowsTelegramApiException` (`{"ok":false,"error_code":400,"description":"BAD"}`).
- [ ] Step 2: `mvn -q test -Dtest=StarPaymentsApiTest` → FAIL (methods missing).
- [ ] Step 3: Implement the seven methods.
- [ ] Step 4: Re-run → PASS.
- [ ] Step 5: Commit `feat(bot): add Telegram Stars payment API methods`.

### Task 2: Module hooks, dispatcher routing, allowed_updates

**Files:**
- Modify: `bot/TelegramBotModule.java`, `bot/BotUpdateDispatcher.java`, `bot/TelegramBotRunner.java`
- Test: `bot/StarPaymentRoutingTest.java`; extend `bot/TelegramBotRunnerAllowedUpdatesTest.java`

**Interfaces — Produces** on `TelegramBotModule` (use existing `claimSlot`):
```java
void onPreCheckoutQuery(Consumer<JsonNode> h);   Consumer<JsonNode> getPreCheckoutHandler();
void onSuccessfulPayment(Consumer<JsonNode> h);  Consumer<JsonNode> getSuccessfulPaymentHandler();
void onRefundedPayment(Consumer<JsonNode> h);    Consumer<JsonNode> getRefundedPaymentHandler();
```
Dispatcher `route`: after `managed_bot` and `callback_query`, before command parsing: `update.pre_checkout_query` → pre-checkout handler; `message.successful_payment` → successful handler; `message.refunded_payment` → refunded handler; null handler → fallback. Runner: explicit list stays only when managed-bot handler exists: `[message, callback_query, managed_bot]` + `pre_checkout_query` if pre-checkout handler exists; otherwise `null` (Telegram default already includes `pre_checkout_query`).

- [ ] Step 1: Tests: `preCheckoutUpdateReachesItsHandler`, `successfulPaymentMessageBypassesTextAndCommands` (message has `text:"/start"` too — payment handler wins), `refundedPaymentReachesItsHandler`, `paymentUpdatesWithoutHandlerGoToFallback`, `secondPreCheckoutHandlerIsRejected` (IllegalStateException); runner: `managedBotAndPaymentsRequestPreCheckout` → list `[message, callback_query, managed_bot, pre_checkout_query]`; `paymentsOnlyKeepsTwoArgOverload`.
- [ ] Step 2: Run → FAIL.
- [ ] Step 3: Implement.
- [ ] Step 4: `mvn -q test -Dtest='StarPaymentRoutingTest,TelegramBotRunnerAllowedUpdatesTest,BotUpdateDispatcherTest'` → PASS.
- [ ] Step 5: Commit `feat(bot): route pre-checkout and payment updates`.

### Task 3: Properties, products, catalog

**Files:**
- Create: `payments/TelegramPaymentsProperties.java`, `payments/StarProduct.java`, `payments/StarCatalog.java`, `payments/PropertiesStarCatalog.java`, `payments/BalanceScope.java` (`GLOBAL, PER_BOT`), `payments/RefundPolicy.java` (`REQUIRE_BALANCE, ALLOW_NEGATIVE`)
- Test: `payments/PropertiesStarCatalogTest.java`

**Interfaces — Produces:**
```java
@ConfigurationProperties(prefix = "telegram.payments") class TelegramPaymentsProperties {
  boolean enabled; Duration orderTtl=30m; Duration preCheckoutTimeout=5s;
  String preCheckoutRejectMessage="This order is no longer available, please try again";
  Duration orderRetention=90d;
  Reconcile reconcile {boolean enabled=true; Duration interval=15m; Duration subscriptionGrace=1h;}
  Support support {boolean enabled=true; String paysupportText="For payment issues, reply here and our team will help you. Telegram support cannot help with purchases made in this bot."; String termsText="By paying you agree to the terms of this service.";}
  TopUp topUp {boolean enabled=false; int minStars=10; int maxStars=10000; long creditsPerStar=1; String title="Balance top-up"; String description="Top up your balance";}
  Map<String, Product> products = LinkedHashMap; // Product {String title, description; int stars; long credits; boolean subscription;}
  Balance balance {boolean enabled=false; BalanceScope scope=GLOBAL; RefundPolicy refundPolicy=REQUIRE_BALANCE;}
  void validate(); // throws IllegalStateException naming the offending key
}
record StarProduct(String code, String title, String description, int stars, long credits, boolean subscription)
interface StarCatalog { Optional<StarProduct> find(String code); List<StarProduct> all(); }
class PropertiesStarCatalog implements StarCatalog { PropertiesStarCatalog(TelegramPaymentsProperties p); } // insertion order
```
`validate()` rules: title 1–32, description 1–255, stars ≥ 1, subscription stars ≤ 10000, credits ≥ 0, `preCheckoutTimeout` > 0 and < 10s, `topUp.minStars` ≥ 1 and ≤ `maxStars`, `creditsPerStar` ≥ 1.

- [ ] Step 1: Tests: `productsKeepYamlOrderAndCode`, `unknownCodeIsEmpty`, `validateRejectsLongTitle` (message contains `products.pack.title`), `validateRejectsSubscriptionAbove10000`, `validateRejectsTimeoutOf10s`, `validateRejectsMinAboveMax`, `defaultsAreAsSpecified`.
- [ ] Step 2–4: fail → implement → pass.
- [ ] Step 5: Commit `feat(payments): add payments properties and product catalog`.

### Task 4: Order and subscription model + in-memory stores

**Files:**
- Create: `payments/StarOrder.java`, `StarOrderStatus.java`, `StarOrderKind.java`, `StarOrderStore.java`, `InMemoryStarOrderStore.java`, `StarSubscription.java`, `StarSubscriptionStatus.java`, `StarSubscriptionStore.java`, `InMemoryStarSubscriptionStore.java`, `StarOrderIds.java`
- Test: `payments/StarOrderStoreContract.java` (abstract), `payments/InMemoryStarOrderStoreTest.java`, `payments/StarSubscriptionStoreContract.java`, `payments/InMemoryStarSubscriptionStoreTest.java`

**Interfaces — Produces:**
```java
enum StarOrderStatus { CREATED, PAID, REFUNDED, EXPIRED }
enum StarOrderKind { PURCHASE, TOP_UP, RENEWAL }
record StarOrder(String id, Long botId, long userId, String productCode, StarOrderKind kind, int stars, long credits,
                 StarOrderStatus status, String chargeId, String subscriptionId,
                 OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime paidAt,
                 OffsetDateTime fulfilledAt, OffsetDateTime refundedAt) {
  String payload();                         // "so:" + id
  StarOrder withSubscriptionId(String sid);
}
final class StarOrderIds { static String newId(); static Optional<String> parsePayload(String payload); } // 32 hex; parse rejects anything else
interface StarOrderStore {
  void save(StarOrder o);                                       // upsert by id
  Optional<StarOrder> findById(String id);
  Optional<StarOrder> findByChargeId(String chargeId);
  boolean markPaid(String id, String chargeId, OffsetDateTime at);   // true only for CREATED|EXPIRED -> PAID by this call
  boolean insertPaidIfChargeAbsent(StarOrder paid);                  // renewals; false if chargeId already stored
  boolean markFulfilled(String id, OffsetDateTime at);              // true only if PAID and fulfilledAt == null
  boolean markRefunded(String id, OffsetDateTime at);               // true only for PAID -> REFUNDED
  List<StarOrder> findPaidUnfulfilled(int limit);
  int expireCreatedBefore(OffsetDateTime now);                       // CREATED with expiresAt < now -> EXPIRED
  int deleteExpiredBefore(OffsetDateTime cutoff);                    // EXPIRED with expiresAt < cutoff
}
enum StarSubscriptionStatus { ACTIVE, CANCELED, EXPIRED }
record StarSubscription(String id, Long botId, long userId, String productCode, String firstChargeId,
                        String lastChargeId, StarSubscriptionStatus status, OffsetDateTime expiresAt, OffsetDateTime createdAt) {
  StarSubscription renewed(String chargeId, OffsetDateTime newExpiry);   // status ACTIVE
  StarSubscription withStatus(StarSubscriptionStatus s);
}
interface StarSubscriptionStore {
  void save(StarSubscription s);
  Optional<StarSubscription> findById(String id);
  List<StarSubscription> findByUser(long userId);
  List<StarSubscription> findLiveExpiringBefore(OffsetDateTime cutoff); // ACTIVE|CANCELED with expiresAt < cutoff
}
```
In-memory stores: `ConcurrentHashMap`; `markPaid`/`markRefunded`/`markFulfilled`/`insertPaidIfChargeAbsent` atomic via `synchronized` methods.

- [ ] Step 1: Contract tests: `markPaidTransitionsOnce` (second call false, chargeId stored), `markPaidAcceptsExpired`, `markPaidRejectsRefunded`, `insertPaidIfChargeAbsentIsIdempotent`, `markFulfilledOnce`, `findPaidUnfulfilledSkipsFulfilled`, `expireAndPurge`, `parsePayloadRejectsForeign` (`"x:abc"`, `"so:"+31 hex`, `"so:../"`); subscription contract: `findLiveExpiringBeforeIncludesCanceledExcludesExpired`, `findByUser`.
- [ ] Step 2–4: fail → implement → pass.
- [ ] Step 5: Commit `feat(payments): add order and subscription stores`.

### Task 5: JPA stores

**Files:**
- Create: `payments/BaseStarOrder.java`, `BaseStarOrderRepository.java`, `JpaStarOrderStore.java`, `BaseStarSubscription.java`, `BaseStarSubscriptionRepository.java`, `JpaStarSubscriptionStore.java`
- Test: `src/test/java/com/example/demo/DemoStarOrder.java`, `DemoStarOrderRepository.java`, `DemoStarSubscription.java`, `DemoStarSubscriptionRepository.java`, `JpaStarOrderStoreTest.java`, `JpaStarSubscriptionStoreTest.java` (extend the Task 4 contracts; wiring exactly like `JpaManagedBotIntentStoreTest`, own H2 db names)

**Interfaces — Produces:** `JpaStarOrderStore<O extends BaseStarOrder>(BaseStarOrderRepository<O> repo, Supplier<O> factory)`, `JpaStarSubscriptionStore<S extends BaseStarSubscription>(BaseStarSubscriptionRepository<S> repo, Supplier<S> factory)`.
Columns (`@MappedSuperclass`, snake_case): order `id varchar(32) PK, bot_id, user_id not null, product_code varchar(64), kind varchar(16), stars, credits, status varchar(16), charge_id varchar(128) UNIQUE, subscription_id varchar(32), created_at, expires_at, paid_at, fulfilled_at, refunded_at`; subscription `id varchar(32) PK, bot_id, user_id, product_code, first_charge_id varchar(128) UNIQUE, last_charge_id, status, expires_at, created_at`.
State transitions use `@Modifying @Query("update #{#entityName} o set … where o.id = :id and o.status in :from")` returning `int` (1 = this call won). `insertPaidIfChargeAbsent`: `existsByChargeId` then `saveAndFlush`; catch `DataIntegrityViolationException` → false. All mutating methods `@Transactional`.

- [ ] Step 1: Test classes extending contracts (+ `chargeIdIsUnique`).
- [ ] Step 2–4: fail → implement → `mvn -q test -Dtest='JpaStar*'` pass.
- [ ] Step 5: Commit `feat(payments): add JPA order and subscription stores`.

### Task 6: StarPaymentService

**Files:**
- Create: `payments/StarPaymentService.java`, `InvoiceLink.java`, `StarPaymentException.java`, `StarRefundGuard.java`, `StarPaymentEvents.java`, `RefundSource.java`, `CompositeStarPaymentEvents.java`
- Test: `payments/StarPaymentServiceTest.java` (fake `TelegramBot` subclass recording calls)

**Interfaces — Produces:**
```java
interface StarPaymentEvents { // all default no-op; implementations MUST be idempotent (redelivery)
  default void onPaid(StarOrder order) {}
  default void onRefunded(StarOrder order, RefundSource source) {}
  default void onSubscriptionRenewed(StarSubscription sub, StarOrder renewal) {}
  default void onSubscriptionExpired(StarSubscription sub) {}
}
enum RefundSource { BOT, EXTERNAL }
final class CompositeStarPaymentEvents implements StarPaymentEvents { CompositeStarPaymentEvents(List<StarPaymentEvents>); } // in order; rethrows the first failure after calling all
@FunctionalInterface interface StarRefundGuard { Optional<String> check(StarOrder order); } // empty = allowed
record InvoiceLink(String url, StarOrder order)
class StarPaymentException extends RuntimeException
class StarPaymentService {
  StarPaymentService(TelegramBotModule main, StarCatalog catalog, StarOrderStore orders, StarSubscriptionStore subs,
                     StarRefundGuard guard /*nullable*/, StarPaymentEvents events, TelegramPaymentsProperties props, Clock clock)
  void registerBot(TelegramBotModule tenant);            // keyed by getBotUserId(); replaces on restart
  Map<Long, TelegramBotModule> tenantBots();             // unmodifiable
  TelegramBotModule moduleFor(Long botId);               // null or main id -> main; unknown -> StarPaymentException
  Long botIdOf(TelegramBotModule m);                     // main -> main.getBotUserId() (may be null)
  StarOrder sendInvoice(long chatId, long userId, String productCode);
  StarOrder sendInvoice(Long botId, long chatId, long userId, String productCode);
  StarOrder sendTopUpInvoice(long chatId, long userId, int stars);
  StarOrder sendTopUpInvoice(Long botId, long chatId, long userId, int stars);
  InvoiceLink createInvoiceLink(long userId, String productCode);
  InvoiceLink createInvoiceLink(Long botId, long userId, String productCode);
  void refund(String orderId);
  void cancelSubscription(String subscriptionId);
  void resumeSubscription(String subscriptionId);
  long botStarBalance(Long botId);
  StarPaymentEvents events(); Clock clock(); TelegramPaymentsProperties properties();
}
```
Rules: unknown product → `StarPaymentException("unknown product: x")`; `sendInvoice` on a subscription product → exception ("subscriptions need createInvoiceLink"); top-up disabled or stars outside [min,max] → exception; order saved **before** the API call, kind `PURCHASE`/`TOP_UP`, credits = product.credits or `stars * creditsPerStar`, productCode `"top-up"` for top-ups, `expiresAt = now + orderTtl`. `createInvoiceLink` passes `2592000` for subscription products. `refund`: order must be `PAID` (else exception); guard veto → exception with the reason; then `refundStarPayment(order.userId, chargeId)` → `markRefunded` → `events.onRefunded(order, BOT)`. Cancel/resume: `editUserStarSubscription(userId, lastChargeId, true/false)` then status `CANCELED`/`ACTIVE`.

- [ ] Step 1: Tests: `sendInvoiceSavesOrderThenCallsApi` (payload `so:<id>`, stars 100), `topUpComputesCredits` (stars 50 × 2 = 100), `topUpOutOfRangeRejected`, `subscriptionViaSendInvoiceRejected`, `createInvoiceLinkPassesPeriodForSubscription`, `tenantInvoiceUsesTenantBotAndStoresBotId`, `unknownTenantRejected`, `refundCallsApiMarksRefundedFiresEvent`, `refundVetoedByGuard` (no API call), `refundOfUnpaidRejected`, `cancelSubscriptionUsesLastChargeId`, `compositeCallsAllAndRethrowsFirst`.
- [ ] Step 2–4: fail → implement → pass.
- [ ] Step 5: Commit `feat(payments): add StarPaymentService`.

### Task 7: StarPaymentUpdateHandler

**Files:**
- Create: `payments/StarPaymentUpdateHandler.java`, `payments/PreCheckoutValidator.java`
- Test: `payments/StarPaymentUpdateHandlerTest.java`

**Interfaces — Consumes:** Task 4 stores, Task 6 service. **Produces:**
```java
@FunctionalInterface interface PreCheckoutValidator { Optional<String> validate(StarOrder order, JsonNode preCheckoutQuery); } // empty = ok
class StarPaymentUpdateHandler {
  StarPaymentUpdateHandler(StarPaymentService service, StarOrderStore orders, StarSubscriptionStore subs,
                           PreCheckoutValidator validator /*nullable*/)
  void register(TelegramBotModule module);   // claims the 3 hooks on that module
  /** Idempotent; shared with the reconciler. Returns true if this call recorded the charge. */
  boolean completePayment(Long botId, long userId, String payload, String chargeId, int totalAmount,
                          OffsetDateTime subscriptionExpiresAt /*nullable*/);
  void deliver(StarOrder paidOrder);         // fires onPaid / onSubscriptionRenewed, then markFulfilled on success
}
```
Pre-checkout (answer always sent, exactly once): parse payload → order → checks in spec §4.1 order → validator, all inside `CompletableFuture.supplyAsync(…, validationExecutor).get(preCheckoutTimeout)`; validationExecutor = cached daemon pool named `star-precheckout-*`. Reject text: validator's message if it vetoed, otherwise `preCheckoutRejectMessage`.
`completePayment` algorithm:
1. `findByChargeId(chargeId)` present → return false.
2. order = `findById(parsed id)`; absent → log ERROR (paid but unknown order) and return false.
3. If order is `PAID` with a different chargeId and `subscriptionId != null` → **renewal**: build `StarOrder(newId, botId, userId, productCode, RENEWAL, totalAmount, product credits, PAID, chargeId, subscriptionId, now, now, now, null, null)`; `insertPaidIfChargeAbsent` false → return false; subscription `renewed(chargeId, expiresAt ?: max(sub.expiresAt, now) + 30d)`; save; `deliver(renewal)`; return true.
4. Else `markPaid(order.id, chargeId, now)` false → return false. If product is a subscription and `order.subscriptionId == null` → create `StarSubscription(newId, …, firstChargeId = lastChargeId = chargeId, ACTIVE, expiresAt ?: now + 30d, now)`, save it, save order `withSubscriptionId`. `deliver(reloaded order)`; return true.
`deliver`: RENEWAL → `onSubscriptionRenewed(sub, order)`, else `onPaid(order)`; exception → log WARN, leave unfulfilled; success → `markFulfilled`.
Refunded payment: `findByChargeId` → `markRefunded` true → `onRefunded(order, EXTERNAL)`; unknown charge → WARN.

- [ ] Step 1: Tests: `validPreCheckoutAnsweredOk`; rejections `tamperedAmount`, `foreignUser`, `expiredOrder`, `alreadyPaid`, `wrongCurrency`, `unknownPayload`, `validatorVetoUsesItsMessage`, `slowValidatorTimesOut` (validator sleeps 2s, timeout 200ms → ok=false within 1s), `validatorThrowsRejects`; `successfulPaymentMarksPaidAndFiresOnPaidOnce` (same update twice → one event); `firstSubscriptionPaymentCreatesSubscription`; `renewalCreatesNewOrderAndFiresRenewed` (original order untouched, onPaid count stays 1); `renewalAfterExpiryReactivates`; `externalRefundFiresEventOnce`; `listenerFailureLeavesOrderUnfulfilled`.
- [ ] Step 2–4: fail → implement → pass.
- [ ] Step 5: Commit `feat(payments): handle pre-checkout and payment updates`.

### Task 8: StarPaymentReconciler

**Files:**
- Create: `payments/StarPaymentReconciler.java`, `payments/ReconcileReport.java`
- Test: `payments/StarPaymentReconcilerTest.java`

**Interfaces — Produces:**
```java
record ReconcileReport(int recovered, int redelivered, int expiredOrders, int purgedOrders, int expiredSubscriptions)
class StarPaymentReconciler implements SmartLifecycle {
  StarPaymentReconciler(StarPaymentService service, StarPaymentUpdateHandler handler, StarOrderStore orders, StarSubscriptionStore subs)
  ReconcileReport reconcile();   // synchronized; safe to call manually
  // SmartLifecycle: single daemon thread "star-reconcile", fixed delay = reconcile.interval, first run after one interval
}
```
`reconcile()` steps: (a) for main + every tenant bot: page `getStarTransactions(offset, 100)` starting from an in-memory per-bot cursor (starts 0, advances by page size while full pages come back); for each transaction with `source.type == "user"` and `source.invoice_payload` parseable: `completePayment(botId, source.user.id, payload, tx.id, tx.amount, subscriptionPeriod != null ? txDate + 30d : null)` → count `recovered` when true; API errors per bot logged, other bots continue. (b) `findPaidUnfulfilled(100)` → `handler.deliver` → `redelivered`. (c) `expireCreatedBefore(now)`; `deleteExpiredBefore(now - orderRetention)`. (d) `findLiveExpiringBefore(now - subscriptionGrace)` → status `EXPIRED`, save, `onSubscriptionExpired`.

- [ ] Step 1: Tests: `missedPaymentIsRecovered` (order CREATED, transaction present → PAID + onPaid), `latePaymentOnExpiredOrderHonored`, `alreadyRecordedChargeSkipped`, `unfulfilledPaidOrderIsRedelivered`, `staleOrdersExpireAndPurge`, `subscriptionExpiresAfterGrace` (event once), `oneBotFailingDoesNotStopOthers`.
- [ ] Step 2–4: fail → implement → pass.
- [ ] Step 5: Commit `feat(payments): add payment reconciler`.

### Task 9: Auto-configuration, support commands, tenant wiring

**Files:**
- Create: `payments/PaySupportCommands.java`, `payments/TelegramPaymentsAutoConfiguration.java`
- Modify: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (append), `whitelabel/TelegramWhiteLabelAutoConfiguration.java` (compose **all** `ManagedBotCustomizer` beans via `orderedStream()` into one; single/none behave as before)
- Test: `src/test/java/com/example/demo/PaymentsAutoConfigTest.java` (`ApplicationContextRunner`, `@TestConfiguration HostBeans` like `ManagedBotsAutoConfigTest`), extend `WhiteLabelAutoConfigTest` with `multipleCustomizersAllRun`

**Interfaces — Produces:**
```java
final class PaySupportCommands { static void register(TelegramBotModule m, TelegramPaymentsProperties p); } // only if "/paysupport" / "/terms" not already in getCommands(); replies via sendMessage(chat.id, text)
@AutoConfiguration(after = TelegramAuthAutoConfiguration.class)
@ConditionalOnProperty(prefix="telegram.payments", name="enabled", havingValue="true")
@EnableConfigurationProperties(TelegramPaymentsProperties.class)
class TelegramPaymentsAutoConfiguration {
  @Bean @ConditionalOnMissingBean StarCatalog;                    // PropertiesStarCatalog; properties.validate() called here
  @Bean @ConditionalOnMissingBean StarOrderStore;                 // InMemory + WARN
  @Bean @ConditionalOnMissingBean StarSubscriptionStore;          // InMemory + WARN
  @Bean @ConditionalOnMissingBean StarPaymentService;             // events = composite (below); guard = ObjectProvider<StarRefundGuard>.getIfAvailable(); Clock = systemUTC
  @Bean @ConditionalOnMissingBean StarPaymentUpdateHandler;       // validator = ObjectProvider<PreCheckoutValidator>; registers on the main module; PaySupportCommands if support.enabled
  @Bean @ConditionalOnMissingBean @ConditionalOnProperty(reconcile.enabled, matchIfMissing=true) StarPaymentReconciler;
  @Bean @ConditionalOnProperty(prefix="telegram.white-label", name="enabled", havingValue="true")
  ManagedBotCustomizer starPaymentsTenantCustomizer(StarPaymentService, StarPaymentUpdateHandler, TelegramPaymentsProperties); // registerBot + handler.register + support commands
}
```
Composite = `ObjectProvider<StarPaymentEvents>.orderedStream().toList()`; `TopUpCreditor` (Task 12) implements `Ordered` with `HIGHEST_PRECEDENCE`, so it runs first without this config knowing its type.

- [ ] Step 1: Tests: `offByDefault`, `enabledWiresServiceHandlerAndHooks` (module pre-checkout handler non-null), `invalidProductFailsStartup`, `hostStoreReplacesInMemory`, `reconcilerCanBeDisabled`, `paysupportRegisteredUnlessHostHasOne`, `hostEventsBeanReceivesOnPaid`; white-label `multipleCustomizersAllRun`.
- [ ] Step 2–4: fail → implement → `mvn -q test` (whole suite) pass.
- [ ] Step 5: Commit `feat(payments): auto-configure Stars payments and tenant bots`.

### Task 10: Balance ledger API + in-memory

**Files:**
- Create: `payments/balance/BalanceAccountKey.java`, `LedgerResult.java`, `LedgerEntry.java`, `InsufficientBalanceException.java`, `BalanceLedger.java`, `InMemoryBalanceLedger.java`
- Test: `payments/balance/BalanceLedgerContract.java`, `InMemoryBalanceLedgerTest.java`

**Interfaces — Produces:**
```java
record BalanceAccountKey(Long botId, long userId) { String asString(); } // (botId==null ? "g" : botId) + ":" + userId
record LedgerResult(boolean applied, long balance)
record LedgerEntry(String refKey, BalanceAccountKey account, long delta, String reason, OffsetDateTime createdAt)
class InsufficientBalanceException extends RuntimeException { long required(); long available(); }
interface BalanceLedger {
  LedgerResult credit(BalanceAccountKey a, long amount, String refKey, String reason);
  LedgerResult debit(BalanceAccountKey a, long amount, String refKey, String reason);             // throws Insufficient
  LedgerResult debitAllowNegative(BalanceAccountKey a, long amount, String refKey, String reason);
  long balance(BalanceAccountKey a);                                                              // 0 for unknown
  List<LedgerEntry> history(BalanceAccountKey a, int page, int size);                             // newest first
}
```
`amount <= 0` or blank refKey → `IllegalArgumentException`. A reused refKey returns `applied=false` with current balance and changes nothing (even if amount differs). refKey max 160 chars, reason max 64.

- [ ] Step 1: Contract tests: `creditThenBalance`, `replaySameRefKeyIsNoop`, `debitInsufficientThrowsAndLeavesNoEntry`, `debitAllowNegativeGoesBelowZero`, `historyNewestFirstPaged`, `scopesAreIsolated` (`(null,1)` vs `(5,1)`), `concurrentDebitsNeverOverdraw` (balance 100, 50 threads debit 10 each → exactly 10 succeed, balance 0), `rejectsNonPositiveAmount`.
- [ ] Step 2–4: fail → implement (in-memory: per-account lock) → pass.
- [ ] Step 5: Commit `feat(balance): add balance ledger API`.

### Task 11: JPA ledger

**Files:**
- Create: `payments/balance/BaseBalanceAccount.java`, `BaseLedgerEntry.java`, `BaseBalanceAccountRepository.java`, `BaseLedgerEntryRepository.java`, `JpaBalanceLedger.java`
- Test: `com/example/demo/DemoBalanceAccount.java`, `DemoLedgerEntry.java`, repos, `JpaBalanceLedgerTest.java` (extends contract)

**Interfaces — Produces:** `JpaBalanceLedger<A extends BaseBalanceAccount, E extends BaseLedgerEntry>(BaseBalanceAccountRepository<A>, BaseLedgerEntryRepository<E>, Supplier<A>, Supplier<E>, PlatformTransactionManager)`.
Columns: account `account_key varchar(48) PK, bot_id, user_id, balance bigint, updated_at`; entry `ref_key varchar(160) PK, account_key varchar(48) indexed, delta bigint, reason varchar(64), created_at`.
Algorithm per operation inside a `TransactionTemplate`: if entry exists → replay result. Ensure account row (insert on absence; `DataIntegrityViolationException` from a concurrent insert → retry once in a new tx). Insert entry with `saveAndFlush`. `@Modifying` update: credit `balance = balance + :n`; debit `… where account_key = :k and balance >= :n` → 0 rows → throw `InsufficientBalanceException` (rolls back the entry); allow-negative unconditional. A PK violation on the entry (concurrent replay) caught outside the template → replay result.

- [ ] Step 1: Test class extends contract.
- [ ] Step 2–4: fail → implement → pass.
- [ ] Step 5: Commit `feat(balance): add JPA balance ledger`.

### Task 12: TopUpCreditor + balance auto-config + end-to-end

**Files:**
- Create: `payments/balance/TopUpCreditor.java`, `payments/balance/TelegramPaymentsBalanceAutoConfiguration.java`
- Modify: AutoConfiguration.imports (append)
- Test: `payments/balance/TopUpCreditorTest.java`, `com/example/demo/StarPaymentsEndToEndTest.java`

**Interfaces — Produces:**
```java
class TopUpCreditor implements StarPaymentEvents, StarRefundGuard, Ordered {   // getOrder() = HIGHEST_PRECEDENCE
  TopUpCreditor(BalanceLedger ledger, BalanceScope scope, RefundPolicy policy)
  BalanceAccountKey keyFor(Long botId, long userId);  // GLOBAL -> (null, userId)
}
@AutoConfiguration(before = TelegramPaymentsAutoConfiguration.class)
@ConditionalOnProperty(prefix="telegram.payments", name={"enabled","balance.enabled"}, havingValue="true")
class TelegramPaymentsBalanceAutoConfiguration { @Bean @ConditionalOnMissingBean BalanceLedger (InMemory + WARN); @Bean @ConditionalOnMissingBean TopUpCreditor; }
```
Behavior: `onPaid` / `onSubscriptionRenewed` with credits > 0 → `credit(key, credits, "pay:" + chargeId, kind + ":" + productCode)`. `onRefunded` with credits > 0: EXTERNAL or `ALLOW_NEGATIVE` → `debitAllowNegative`; BOT + `REQUIRE_BALANCE` → `debit`, and on `InsufficientBalanceException` (race after guard) → `debitAllowNegative` + WARN. refKey `"refund:" + chargeId`. `check(order)`: `REQUIRE_BALANCE` and `balance < credits` → `Optional.of("Credits from this payment were already spent")`.

- [ ] Step 1: Unit tests: `paidCreditsOnce`, `renewalCredits`, `zeroCreditsSkipsLedger`, `perBotScopeUsesBotId`, `guardVetoesWhenSpent`, `externalRefundGoesNegative`, `allowNegativePolicyRefunds`. E2E (`ApplicationContextRunner` with fake bot module): send invoice → dispatch pre-checkout JSON → answered ok → dispatch successful_payment twice → balance 100 once → `service.refund` → balance 0; with `scope=PER_BOT` a tenant module registered via `registerBot` credits `(botId, user)`.
- [ ] Step 2–4: fail → implement → `mvn -q test` pass.
- [ ] Step 5: Commit `feat(balance): credit top-ups and auto-configure the ledger`.

### Task 13: Docs and version

**Files:** `README.md`, `CHANGELOG.md`, `pom.xml` (`0.5.1` → `0.6.0`, plus README install snippet)

- [ ] Step 1: README: feature-table row "Telegram Stars payments" (`telegram.payments.enabled=true`); new section covering: digital goods ⇒ Stars only; YAML reference (Global Constraints defaults); `StarPaymentService` usage (bot invoice, Mini App `createInvoiceLink` → `Telegram.WebApp.openInvoice(url)`); JPA entity wiring for orders/subscriptions/ledger; extension-point table; events must be idempotent; balance layer + scope + refund policy; spending credits via `BalanceLedger.debit` with a stable refKey; `/paysupport` + `/terms` duty; tenant bots; reconciler cursor is in-memory (first pass after restart rescans history). Every snippet checked against the code.
- [ ] Step 2: CHANGELOG `## [0.6.0] - 2026-11-10` Added/Changed (customizers composed).
- [ ] Step 3: `mvn -q test` → PASS.
- [ ] Step 4: Commit `docs(payments): document Telegram Stars payments for 0.6.0` and `chore(release): bump version to 0.6.0`.
