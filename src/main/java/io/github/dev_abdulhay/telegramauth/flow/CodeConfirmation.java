package io.github.dev_abdulhay.telegramauth.flow;

/**
 * How the browser-visible confirmation code is collected in the bot.
 *
 * <ul>
 *   <li>{@code BUTTON} — an inline keyboard of candidate numbers. With {@code n}
 *       buttons a blind guess succeeds once in {@code n}, so one wrong tap ends
 *       the login.</li>
 *   <li>{@code TYPED} — the user sends the number as text. 100 candidates make
 *       three tries safe, which is far kinder to someone who simply misread.</li>
 *   <li>{@code OFF} — no code step (pre-0.4.0 behaviour).</li>
 * </ul>
 */
public enum CodeConfirmation { BUTTON, TYPED, OFF }
