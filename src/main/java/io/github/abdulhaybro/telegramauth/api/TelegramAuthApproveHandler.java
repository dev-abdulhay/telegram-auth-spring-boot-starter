package io.github.abdulhaybro.telegramauth.api;

import io.github.abdulhaybro.telegramauth.api.dto.AuthApproveResult;
import io.github.abdulhaybro.telegramauth.api.dto.AuthContext;
import io.github.abdulhaybro.telegramauth.api.dto.TelegramUserInfo;

/**
 * Host-application extension point. <strong>Required</strong>: the host must
 * expose exactly one bean of this type. Invoked once per successful approval;
 * the returned payload is forwarded verbatim to the waiting client.
 */
@FunctionalInterface
public interface TelegramAuthApproveHandler {

    AuthApproveResult onApprove(TelegramUserInfo user, AuthContext ctx);
}
