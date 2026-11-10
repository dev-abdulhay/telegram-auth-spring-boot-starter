# Telegram Stars payments — design (0.6.0)

Status: approved in brainstorming, 2026-11-10. Next step: implementation plan.

## 1. Goal and context

Host apps built on this starter sell **digital services of the platform** (the platform is
the seller; users do not pay each other). Users top up an internal balance with Telegram
Stars and spend it on services; some products are 30-day subscriptions. Payments must also
work inside white-label tenant bots.

Telegram constraints this design is built around (Bot API, verified 2026-11-10):

- Digital goods/services **must** be paid in Stars (`currency = "XTR"`, empty
  `provider_token`, exactly one `LabeledPrice`). Third-party providers (Click, Payme, …) are
  out of scope here — they are only allowed for physical goods/services.
- A bot can **receive** Stars but cannot pay them out to users; the only outgoing flow is
  `refundStarPayment`. Any user-to-user value transfer is internal credits only.
- `pre_checkout_query` must be answered within **10 s** or the payment is cancelled.
- Store `telegram_payment_charge_id` — it is the refund/subscription handle and
  `StarTransaction.id` for incoming payments.
- Subscriptions: `createInvoiceLink` with `subscription_period = 2592000` (always 30 days),
  price ≤ 10000 ⭐. Renewals arrive as `successful_payment` with `is_recurring = true`
  (`is_first_recurring = true` on the first). Telegram sends **no update when a user cancels**;
  only the absence of a renewal before `subscription_expiration_date`.
- `message.refunded_payment` arrives when a payment is refunded outside the bot.
  Chargebacks (Apple/Google) are deducted from the bot's Stars balance.
- Bots selling with Stars must answer `/paysupport` and should offer `/terms`.
- Telegram's default `allowed_updates` includes `pre_checkout_query`, but
  `TelegramBotRunner` currently sends an explicit list `[message, callback_query, managed_bot]`
  when managed bots are on — that list would silently drop pre-checkout queries.

## 2. Approach (chosen: C — layered)

| Option | Summary | Verdict |
|---|---|---|
| A | Payment plumbing + events only; host writes its own balance | rejected: every host re-implements an idempotent ledger |
| B | Full ledger always bundled with payments | rejected: starter would dictate the money model |
| **C** | **Core `payments` + optional `payments.balance` ledger, each switchable and replaceable** | **chosen** — mirrors the managed-bots core + optional intents layering |

## 3. Components

### 3.1 Bot transport (`bot` package, no auto-configuration)

`TelegramBot` — new methods (thin HTTP wrappers, `TelegramApiException` on failure):

| Method | Bot API |
|---|---|
| `sendInvoice(chatId, title, description, payload, stars, extra)` | `sendInvoice` (`currency=XTR`, `provider_token=""`) |
| `createInvoiceLink(title, description, payload, stars, subscriptionPeriod)` | `createInvoiceLink` |
| `answerPreCheckoutQuery(queryId, ok, errorMessage)` | `answerPreCheckoutQuery` |
| `refundStarPayment(userId, chargeId)` | `refundStarPayment` |
| `editUserStarSubscription(userId, chargeId, canceled)` | `editUserStarSubscription` |
| `getStarTransactions(offset, limit)` | `getStarTransactions` (returns raw `JsonNode`) |
| `getMyStarBalance()` | `getMyStarBalance` |

`TelegramBotModule` — new hooks, same style as `onManagedBot`:
`onPreCheckoutQuery(Consumer<JsonNode>)`, `onSuccessfulPayment(Consumer<JsonNode>)`,
`onRefundedPayment(Consumer<JsonNode>)` + getters.

`BotUpdateDispatcher.route` — checked **before** command/text routing:
1. `update.pre_checkout_query` → pre-checkout handler;
2. `message.successful_payment` → successful-payment handler;
3. `message.refunded_payment` → refunded-payment handler.
Missing handler → existing fallback.

`TelegramBotRunner` — `allowed_updates` is derived from registered hooks on every poll
iteration (keeps the existing "recompute, don't snapshot" rule): base
`[message, callback_query]`, `+ managed_bot` if that handler exists,
`+ pre_checkout_query` if that handler exists. No special hooks → `null` (Telegram default),
so hosts overriding only `getUpdates(long, int)` keep working.

### 3.2 Core `payments` package — `telegram.payments.enabled=true`

| Unit | Purpose |
|---|---|
| `StarProduct` (record) | `code, title, description, stars, credits, subscription` |
| `StarCatalog` (interface) | `Optional<StarProduct> find(code)`, `List<StarProduct> all()`; default `PropertiesStarCatalog` from YAML |
| `StarOrder` (value) + `StarOrderStatus` | `CREATED → PAID → REFUNDED`; `CREATED → EXPIRED` |
| `StarOrderStore` (interface) | `save`, `findById`, `findByChargeId`, `markPaid(id, chargeId, …)` (atomic, returns whether *this* call transitioned), `deleteExpiredBefore` |
| `InMemoryStarOrderStore`, `JpaStarOrderStore<O extends BaseStarOrder>(repo, O::new)` | defaults; in-memory logs a WARN "not for production" |
| `BaseStarOrder` (`@MappedSuperclass`) + `BaseStarOrderRepository<O>` | host subclasses with `@Entity @Table` |
| `StarSubscription` + `StarSubscriptionStore` (+ InMemory/Jpa, `BaseStarSubscription`) | `userId, botId, productCode, firstChargeId, lastChargeId, expiresAt, status (ACTIVE / CANCELED / EXPIRED)` |
| `PreCheckoutValidator` (functional interface) | host veto: returns `Optional<String>` rejection message; default allows |
| `StarPaymentEvents` (interface, default no-ops) | `onPaid(PaidOrder)`, `onRefunded(RefundedOrder, RefundSource)`, `onSubscriptionRenewed(StarSubscription, PaidOrder)`, `onSubscriptionExpired(StarSubscription)` |
| `StarPaymentService` | public API (below) |
| `StarPaymentUpdateHandler` | registers the three module hooks; does validation, idempotent completion, event dispatch |
| `StarPaymentReconciler` | scheduled `getStarTransactions` sweep; credits paid-but-unrecorded orders, expires stale orders/subscriptions |
| `PaySupportCommands` | registers `/paysupport` and `/terms` with configurable text (opt-out) |
| `TelegramPaymentsProperties`, `TelegramPaymentsAutoConfiguration` | `@ConditionalOnProperty(prefix="telegram.payments", name="enabled", havingValue="true")` |

`StarPaymentService` API:

```java
StarOrder sendInvoice(long chatId, long userId, String productCode);          // in-chat invoice
StarOrder sendTopUpInvoice(long chatId, long userId, int stars);              // custom amount, if top-up.enabled
InvoiceLink createInvoiceLink(long userId, String productCode);              // Mini App: WebApp.openInvoice(link)
void refund(String orderId);                                                   // refundStarPayment + REFUNDED + event
void cancelSubscription(String subscriptionId);                               // editUserStarSubscription(canceled=true)
void resumeSubscription(String subscriptionId);                               // editUserStarSubscription(canceled=false)
ReconcileReport reconcile();                                                   // also run by the scheduler
int botStarBalance();                                                          // getMyStarBalance
```

### 3.3 Optional `payments.balance` package — `telegram.payments.balance.enabled=true`

| Unit | Purpose |
|---|---|
| `BalanceLedger` (interface) | `credit(account, amount, refKey, reason)`, `debit(account, amount, refKey, reason)`, `debitAllowNegative(...)`, `balance(account)`, `history(account, page)` |
| `BalanceAccountKey` (record) | `(Long botId, long userId)`; `botId == null` when `scope = GLOBAL` |
| `LedgerResult` | applied vs. already-applied (idempotent replay), resulting balance |
| `InsufficientBalanceException` | thrown by `debit` |
| `InMemoryBalanceLedger`, `JpaBalanceLedger<A, E>` | defaults |
| `BaseBalanceAccount`, `BaseLedgerEntry` (`@MappedSuperclass`) + repositories | host entities |
| `TopUpCreditor` | default `StarPaymentEvents` participant: `onPaid → credit(product.credits)`, `onRefunded → debit` per refund policy |

Every bean in 3.2 and 3.3 is `@ConditionalOnMissingBean`. Events fan out: the auto-config
composes `TopUpCreditor` with the host's `StarPaymentEvents` bean (creditor first, so the
host sees an already-credited balance).

### 3.4 White-label tenant bots

The handler is registered per `TelegramBotModule`. The white-label runtime registers it on
each tenant module through the existing `TenantBotFactory` / `ManagedBotCustomizer` path.
Every order and subscription stores `botId` (`TelegramBotModule.getBotUserId()`); Stars land
on the balance of the bot the user paid. The ledger key follows `balance.scope`:
`GLOBAL` → `(null, userId)`, `PER_BOT` → `(botId, userId)`.

## 4. Data flows

### 4.1 Top-up / product purchase

1. `sendInvoice(chatId, userId, code)` → catalog lookup → `StarOrder(CREATED, userId, botId,
   code, stars, credits, expiresAt = now + order-ttl)` saved → invoice sent with payload
   `"so:" + orderId` (opaque, ≤ 128 bytes; no price or user id inside) and an empty
   `start_parameter` (single-chat invoice: payable once in the bot's private chat).
2. `pre_checkout_query` → checks, in order: payload parses, order exists, status `CREATED`,
   not expired, `from.id == order.userId`, `currency == "XTR"`,
   `total_amount == order.stars`; then `PreCheckoutValidator`. All of it runs under a
   `pre-checkout-timeout` budget (default 5 s). Timeout or exception → `ok=false` with
   `pre-checkout-reject-message`. Rejecting is always safe — no money moves.
3. `successful_payment` → `markPaid(orderId, chargeId)`. `telegram_payment_charge_id` is
   UNIQUE, and `markPaid` only transitions from `CREATED`/`EXPIRED`, so a duplicate update is
   a no-op. Only the transitioning call fires `onPaid`; the ledger credits with
   `refKey = chargeId`.
4. Failure after Telegram charged the user (e.g. DB down in step 3): the error is logged and
   the order stays unpaid. `StarPaymentReconciler` lists recent `getStarTransactions`, matches
   incoming user transactions by `id == chargeId` and payload, and completes missing ones via
   the same `markPaid` path. **No paid charge is lost.** A late payment on an `EXPIRED` order
   is still honored.

### 4.2 Subscriptions

- `createInvoiceLink(userId, code)` for a `subscription: true` product → link with
  `subscription_period = 2592000`.
- First payment (`is_first_recurring`) → `StarSubscription(ACTIVE, expiresAt =
  subscription_expiration_date, firstChargeId)` + `onPaid`.
- Renewal (`is_recurring` without `is_first_recurring`) carries the **original** invoice
  payload, so the handler branches on these flags before `markPaid`: it never touches the
  original order and instead inserts a new `StarOrder` row per charge (own `chargeId`,
  idempotent on it), subscription
  `expiresAt` extended, `onSubscriptionRenewed`; product `credits` (if > 0) credited per
  renewal.
- Telegram sends nothing on user cancellation, so the reconciler marks subscriptions whose
  `expiresAt` has passed as `EXPIRED` and fires `onSubscriptionExpired`.
- Bot-side cancel/resume → `editUserStarSubscription` + status `CANCELED`/`ACTIVE`; a canceled
  subscription stays usable until `expiresAt`.

### 4.3 Refunds

- `refund(orderId)` (bot-initiated): ledger check per `refund-policy`:
  `REQUIRE_BALANCE` (default) refuses when credits were already spent;
  `ALLOW_NEGATIVE` lets the balance go negative. Then `refundStarPayment` → `REFUNDED` →
  `onRefunded(source = BOT)` → `debit(refKey = "refund:" + chargeId)`.
- `message.refunded_payment` (external refund) → `REFUNDED` → `onRefunded(source = EXTERNAL)`
  → `debitAllowNegative` — always applied, since the money is already gone.

### 4.4 Spending credits (host code)

`ledger.debit(key, cost, refKey = hostServiceOrderId, reason)` — atomic conditional update
(`UPDATE … SET balance = balance - :n WHERE account = :a AND balance >= :n`) plus an entry
insert in one transaction; `ref_key` UNIQUE makes retries no-ops. Insufficient funds →
`InsufficientBalanceException`. The entry log (`delta, reason, ref_key, created_at`) is the
audit trail; the account row is a cached sum.

### 4.5 Error handling

- Bot API errors → `TelegramApiException` (existing type). Only idempotent reads
  (`getStarTransactions`, `getMyStarBalance`) are retried.
- Handler exceptions never escape the dispatcher (existing `invoke` contract); pre-checkout
  always gets an answer, even on internal failure.

## 5. Configuration

```yaml
telegram:
  payments:
    enabled: true
    order-ttl: 30m
    pre-checkout-timeout: 5s           # must stay below Telegram's 10 s
    pre-checkout-reject-message: "This order is no longer available, please try again"
    order-retention: 90d               # purge of EXPIRED orders
    reconcile:
      enabled: true
      interval: 15m
    support:
      enabled: true                    # /paysupport and /terms
      paysupport-text: "..."
      terms-text: "..."
    top-up:
      enabled: false                   # custom-amount top-ups
      min-stars: 10
      max-stars: 10000
      credits-per-star: 1
    products:
      pack_100:  { title: "100 credits", description: "...", stars: 100, credits: 100 }
      pack_550:  { title: "550 credits", description: "+10% bonus", stars: 500, credits: 550 }
      pro_month: { title: "Pro", description: "...", stars: 250, subscription: true, credits: 0 }
    balance:
      enabled: true
      scope: GLOBAL                    # GLOBAL | PER_BOT
      refund-policy: REQUIRE_BALANCE   # REQUIRE_BALANCE | ALLOW_NEGATIVE
```

Startup validation (fail fast): `stars` 1…10000 for subscriptions, title 1–32 chars,
description 1–255 chars, `pre-checkout-timeout < 10s`, `top-up.min-stars ≤ max-stars`.

Host JPA wiring:

```java
@Entity @Table(name = "star_orders")         class StarOrderEntity  extends BaseStarOrder {}
@Entity @Table(name = "star_subscriptions")  class StarSubEntity    extends BaseStarSubscription {}
@Entity @Table(name = "balance_accounts")    class AccountEntity    extends BaseBalanceAccount {}
@Entity @Table(name = "ledger_entries")      class LedgerEntryEntity extends BaseLedgerEntry {}

@Bean StarOrderStore starOrderStore(StarOrderRepo r) { return new JpaStarOrderStore<>(r, StarOrderEntity::new); }
// same pattern for subscriptions and the ledger
```

Extension points (all `@ConditionalOnMissingBean`): `StarCatalog`, `PreCheckoutValidator`,
`StarPaymentEvents`, `StarOrderStore`, `StarSubscriptionStore`, `BalanceLedger`,
`TopUpCreditor`.

## 6. Testing

- **Unit:** dispatcher routing for the three new update kinds; `allowed_updates` derivation
  (none / managed-bot only / payments only / both); every pre-checkout rejection path
  (tampered amount, foreign user, expired, wrong currency, validator veto, validator timeout);
  duplicate `successful_payment` credits once; reconciler completes a missed payment and a
  late payment on an `EXPIRED` order; concurrent debits never overdraw; both refund policies;
  external refund goes negative; subscription first / renewal / expiry; config validation.
- **Integration:** full flow through `BotUpdateDispatcher` on a fake `TelegramBot` (as in the
  managed-bots tests), JPA stores and ledger on H2, tenant-bot `PER_BOT` scoping.
- **Manual:** Telegram test environment with test Stars.

## 7. Delivery

- Version **0.6.0** (new module, additive API).
- README: new "Telegram Stars payments" section (config, JPA wiring, service usage, Mini App
  `openInvoice`, `/paysupport` duty) + CHANGELOG entry, in the same PR.

## 8. Out of scope

- Fiat providers (Click, Payme, Stripe) and physical goods — a possible separate module or
  starter later.
- Paid media (`sendPaidMedia`), Star gifts, affiliate programs.
- A built-in REST controller — hosts expose `createInvoiceLink` through their own endpoint.
- Withdrawal of Stars (done manually by the bot owner via Fragment).
