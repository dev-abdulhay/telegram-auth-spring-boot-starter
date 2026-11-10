package io.github.dev_abdulhay.telegramauth.payments.balance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Map-backed ledger for tests and hosts that do not use JPA. Not durable. */
public class InMemoryBalanceLedger implements BalanceLedger {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBalanceLedger.class);

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    /** refKeys are unique across all accounts, so they are claimed here, not per account. */
    private final Map<String, LedgerEntry> entries = new ConcurrentHashMap<>();

    public InMemoryBalanceLedger() {
        log.warn("BalanceLedger is in-memory — not for production");
    }

    @Override
    public LedgerResult credit(BalanceAccountKey account, long amount, String refKey, String reason) {
        return apply(account, amount, refKey, reason, Mode.CREDIT);
    }

    @Override
    public LedgerResult debit(BalanceAccountKey account, long amount, String refKey, String reason) {
        return apply(account, amount, refKey, reason, Mode.DEBIT);
    }

    @Override
    public LedgerResult debitAllowNegative(BalanceAccountKey account, long amount, String refKey, String reason) {
        return apply(account, amount, refKey, reason, Mode.DEBIT_ALLOW_NEGATIVE);
    }

    @Override
    public long balance(BalanceAccountKey account) {
        Account a = accounts.get(account.asString());
        if (a == null) {
            return 0;
        }
        synchronized (a) {
            return a.balance;
        }
    }

    @Override
    public List<LedgerEntry> history(BalanceAccountKey account, int page, int size) {
        LedgerArgs.checkPage(page, size);
        Account a = accounts.get(account.asString());
        if (a == null) {
            return List.of();
        }
        synchronized (a) {
            List<LedgerEntry> out = new ArrayList<>();
            int from = a.entries.size() - 1 - page * size;
            for (int i = from; i >= 0 && out.size() < size; i--) {
                out.add(a.entries.get(i));
            }
            return out;
        }
    }

    private LedgerResult apply(BalanceAccountKey key, long amount, String refKey, String reason, Mode mode) {
        LedgerArgs.check(key, amount, refKey, reason);
        Account a = accounts.computeIfAbsent(key.asString(), k -> new Account());
        synchronized (a) {
            if (entries.containsKey(refKey)) {
                return new LedgerResult(false, a.balance);
            }
            if (mode == Mode.DEBIT && a.balance < amount) {
                throw new InsufficientBalanceException(amount, a.balance);
            }
            long delta = mode == Mode.CREDIT ? amount : -amount;
            LedgerEntry e = new LedgerEntry(refKey, key, delta, reason, OffsetDateTime.now());
            // the same refKey may be racing on another account's lock
            if (entries.putIfAbsent(refKey, e) != null) {
                return new LedgerResult(false, a.balance);
            }
            a.balance += delta;
            a.entries.add(e);
            return new LedgerResult(true, a.balance);
        }
    }

    private enum Mode { CREDIT, DEBIT, DEBIT_ALLOW_NEGATIVE }

    private static final class Account {
        long balance;
        final List<LedgerEntry> entries = new ArrayList<>();
    }
}
