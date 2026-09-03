# basic-auth example

A minimal, runnable host application for `telegram-auth-spring-boot-starter`,
demonstrating the **core Telegram-bot login flow only** — one user type, no
contact-share, no inline approve/reject, the default number-matching code
step. Managed bots, white-label tenant bots, JWT issuing and a frontend are
all deliberately left out (see [What this example leaves out](#what-this-example-leaves-out)).

## Before you start: build the library locally

This example depends on `io.github.dev-abdulhay:telegram-auth-spring-boot-starter:0.4.0`.
That version is **not published on Maven Central yet** — this repo's `main` is
ahead of the latest actual release (`0.2.0`; see the
[root README's install section](../../README.md#install)). So this example is
built against your **local** copy of the library on purpose: that is what
keeps it from ever drifting out of sync with the code actually in this repo,
instead of silently depending on a stale published jar.

Before building or running this example, install the library into your local
Maven repository:

```bash
# from the repo root
mvn install -DskipTests
```

Once a release ships that includes the core auth flow used here, this
example's `pom.xml` can point at the published coordinate instead — see the
comment on the dependency there.

## Prerequisites

- Java 17, Maven.
- A Telegram bot: message [@BotFather](https://t.me/BotFather), `/newbot`, and
  keep the token it gives you and the bot's `@username` (without the `@`).

## How to run

```bash
export TELEGRAM_BOT_TOKEN="123456:ABC-your-bot-token"
export TELEGRAM_BOT_USERNAME="your_bot_username"

mvn spring-boot:run
```

The app starts on port 8080, creates its H2 tables (`ddl-auto: update`), and
starts long-polling Telegram for updates on the bot you configured.

## Walk through one login

The example wires `DefaultAuthFlow` with its built-in defaults
(`requireContact=false`, `requireApproval=false`, `codeConfirmation=BUTTON`),
so a login is "one touch": `/start` immediately asks the user to confirm a
2-digit code shown in the browser.

**1. Create a session:**

```bash
curl -s -X POST http://localhost:8080/api/auth/session
```

```json
{
  "token": "kXn2...redacted...",
  "botDeepLink": "https://t.me/your_bot_username?start=kXn2...redacted...",
  "expiresAt": "2026-09-03T12:05:00Z",
  "transports": ["POLL"]
}
```

**2. Long-poll, opting into the code step** (`since=PENDING`) — this call
blocks until something happens or it times out:

```bash
curl -s "http://localhost:8080/api/auth/session/kXn2.../poll?since=PENDING"
```

**3. Open the deep link in Telegram** (on your phone, or via `t.me` in a
desktop client) and send `/start`. The bot asks for a 2-digit code with an
inline keyboard of candidates.

The poll from step 2 returns as soon as the bot sends that keyboard:

```json
{ "status": "AWAITING_CODE", "payload": {}, "confirmCode": 42 }
```

**4. Long-poll again, this time waiting for the terminal outcome**
(`since=AWAITING_CODE`):

```bash
curl -s "http://localhost:8080/api/auth/session/kXn2.../poll?since=AWAITING_CODE"
```

**5. Tap the matching number (`42` in this example) in the bot.** The user
row is created (or updated) at this point, the session is approved, and the
poll from step 4 returns:

```json
{ "status": "APPROVED", "payload": { "userId": 123456789 } }
```

That `payload` is exactly what `TelegramConfig#telegramBotModule`'s
`approveHandler` returned — see [What to change](#what-to-change-for-a-real-application).

Other endpoints, for reference:

```bash
# cheap status check, never returns the confirmation code
curl -s http://localhost:8080/api/auth/session/kXn2.../status

# cancel a session that is still PENDING or AWAITING_CODE
curl -s -X DELETE http://localhost:8080/api/auth/session/kXn2...
```

## What to change for a real application

- **The approve payload.** `TelegramConfig#telegramBotModule`'s
  `approveHandler` returns `Map.of("userId", info.telegramId())` — a real
  application mints its own JWT or session cookie here (or looks up
  additional user data) and returns that instead.
- **The database.** This example uses an in-memory H2 database
  (`ddl-auto: update`) so it starts with zero setup. A real application uses
  a real database with real migrations, and should index
  `ip_address,status` on the session table — see the javadoc on
  `BaseAuthSession` and `AppSession` in this example.
- **`requireApproval`.** It defaults to `false` here to keep the walkthrough
  to one touch. The main README's advice stands: **enable `requireApproval`
  in production** — without it, anyone tricked into tapping a login link
  silently reaches the confirmation step of the sender's browser session.

## What this example leaves out

This example covers the core auth flow only. For managed bots (your bot
creating and holding tokens for other bots), white-label tenant bots (each
managed bot getting its own branded login), and the rest of the
configuration surface, see the [main README](../../README.md).
