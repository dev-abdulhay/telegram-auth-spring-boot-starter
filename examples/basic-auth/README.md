# basic-auth example

A minimal, runnable host application for `telegram-auth-spring-boot-starter`,
demonstrating the **core Telegram-bot login flow only** — one user type, no
contact-share, no inline approve/reject, the default number-matching code
step. Managed bots, white-label tenant bots, JWT issuing and a frontend are
all deliberately left out (see [What this example leaves out](#what-this-example-leaves-out)).

## Prerequisites

- Java 17, Maven.
- A Telegram bot: message [@BotFather](https://t.me/BotFather), `/newbot`, and
  keep the token it gives you and the bot's `@username` (without the `@`).

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

This also builds the library's javadoc and source jars, so the first run is
slower than a plain `compile` — that is expected.

Once a release ships that includes the core auth flow used here, this
example's `pom.xml` can point at the published coordinate instead — see the
comment on the dependency there.

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

The curl commands below use `-i` so the response status line is visible —
that is the actual contract (`AbstractTelegramAuthController`), not just the
JSON body.

**1. Create a session — `200 OK`:**

```bash
curl -i -X POST http://localhost:8080/api/auth/session
```

```
HTTP/1.1 200
Content-Type: application/json

{
  "token": "kXn2...redacted...",
  "botDeepLink": "https://t.me/your_bot_username?start=kXn2...redacted...",
  "expiresAt": "2026-09-03T12:05:00Z",
  "transports": ["POLL"]
}
```

Save the token in a variable for the rest of this walkthrough:

```bash
TOKEN="kXn2...redacted..."
```

**2. Long-poll, opting into the code step** (`since=PENDING`). Each call to
this endpoint waits up to `pollingTimeout` (30 seconds by default —
`TelegramBotModule.Builder`) for something to happen, then answers
**`204 No Content`** with an empty body if nothing did — that is a normal
timeout, not an error, and the client is expected to just call it again. So
run this as a loop, in its own terminal:

```bash
while true; do
  echo "polling..."
  curl -i "http://localhost:8080/api/auth/session/$TOKEN/poll?since=PENDING"
  echo
done
```

You will see `HTTP/1.1 204` print every ~30 seconds with nothing after it —
that is expected until you complete step 3. Leave this loop running.

**3. Open the deep link in Telegram** (on your phone, or via `t.me` in a
desktop client) and send `/start`. The bot asks for a 2-digit code with an
inline keyboard of candidates.

Once the bot sends that keyboard, the next iteration of the loop from step 2
prints **`202 Accepted`** instead of `204`, carrying the browser-visible code.
Stop the loop (Ctrl-C) once you see it:

```
HTTP/1.1 202
Content-Type: application/json

{ "status": "AWAITING_CODE", "payload": {}, "confirmCode": 42 }
```

**4. Long-poll again, this time waiting for the terminal outcome**
(`since=AWAITING_CODE`) — same 30-second-timeout, `204`-until-something-happens
behaviour as step 2, so run it as a loop again:

```bash
while true; do
  echo "polling..."
  curl -i "http://localhost:8080/api/auth/session/$TOKEN/poll?since=AWAITING_CODE"
  echo
done
```

**5. Tap the matching number (`42` in this example) in the bot.** The user
row is created (or updated) at this point, the session is approved, and the
next iteration of the loop from step 4 prints **`200 OK`**:

```
HTTP/1.1 200
Content-Type: application/json

{ "status": "APPROVED", "payload": { "userId": 123456789 } }
```

That `payload` is exactly what `TelegramConfig#telegramBotModule`'s
`approveHandler` returned — see [What to change](#what-to-change-for-a-real-application).

**Other outcomes you can hit at the same poll call:**

- **`403 Forbidden`** — you tapped ❌ in the bot, or ran out of code guesses
  (`DefaultAuthFlow` rejects the session and cools the Telegram user down).
- **`410 Gone`** — the token is unknown, or the session's `sessionTtl`
  (5 minutes by default) expired before you finished.

Other endpoints, for reference:

```bash
# cheap status check — 200 with { status, expiresAt }, or 410 if the token is unknown.
# Never returns the confirmation code.
curl -i "http://localhost:8080/api/auth/session/$TOKEN/status"

# cancel a session that is still PENDING or AWAITING_CODE — always 204, whether
# or not there was anything to cancel
curl -i -X DELETE "http://localhost:8080/api/auth/session/$TOKEN"
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
