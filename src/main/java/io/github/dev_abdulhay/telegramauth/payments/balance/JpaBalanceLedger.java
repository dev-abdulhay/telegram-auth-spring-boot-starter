package io.github.dev_abdulhay.telegramauth.payments.balance;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JPA-backed {@link BalanceLedger}. Each operation runs in its own transaction: the entry row
 * (primary key = refKey) is inserted first, then the balance is changed by one {@code UPDATE};
 * a debit that matches no row throws {@link InsufficientBalanceException} and rolls the entry
 * back with it.
 *
 * <p>Concurrent inserts of the same account or refKey surface as
 * {@link DataIntegrityViolationException}. It is caught <em>outside</em> the transaction
 * (catching inside would leave it rollback-only) and the operation is retried in a fresh one,
 * which then sees the committed row: an existing refKey replays as {@code applied=false}.
 *
 * @param <A> the host's concrete {@link BaseBalanceAccount} entity
 * @param <E> the host's concrete {@link BaseLedgerEntry} entity
 */
public class JpaBalanceLedger<A extends BaseBalanceAccount, E extends BaseLedgerEntry> implements BalanceLedger {

    /** One lost race on the account row plus one on the entry row; the third attempt must see both. */
    private static final int MAX_ATTEMPTS = 3;

    private final BaseBalanceAccountRepository<A> accounts;
    private final BaseLedgerEntryRepository<E> entries;
    private final Supplier<A> accountFactory;
    private final Supplier<E> entryFactory;
    private final TransactionTemplate isolated;
    private final AtomicReference<OffsetDateTime> lastStamp = new AtomicReference<>(OffsetDateTime.MIN);

    /**
     * @param accountFactory makes a blank, unsaved account entity, e.g. {@code Account::new}
     * @param entryFactory makes a blank, unsaved entry entity, e.g. {@code Entry::new}
     * @param txManager runs every operation in its own ({@code REQUIRES_NEW}) transaction
     */
    public JpaBalanceLedger(BaseBalanceAccountRepository<A> accounts, BaseLedgerEntryRepository<E> entries,
                            Supplier<A> accountFactory, Supplier<E> entryFactory,
                            PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.entries = entries;
        this.accountFactory = accountFactory;
        this.entryFactory = entryFactory;
        this.isolated = new TransactionTemplate(txManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        return accounts.findBalance(account.asString()).orElse(0L);
    }

    @Override
    public List<LedgerEntry> history(BalanceAccountKey account, int page, int size) {
        LedgerArgs.checkPage(page, size);
        return entries.findByAccountKeyOrderByCreatedAtDesc(account.asString(), PageRequest.of(page, size)).stream()
                .map(e -> new LedgerEntry(e.getRefKey(), account, e.getDelta(), e.getReason(), e.getCreatedAt()))
                .toList();
    }

    private LedgerResult apply(BalanceAccountKey key, long amount, String refKey, String reason, Mode mode) {
        LedgerArgs.check(key, amount, refKey, reason);
        for (int attempt = 1; ; attempt++) {
            try {
                return isolated.execute(status -> attempt(key, amount, refKey, reason, mode));
            } catch (DataIntegrityViolationException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private LedgerResult attempt(BalanceAccountKey key, long amount, String refKey, String reason, Mode mode) {
        String k = key.asString();
        if (entries.existsById(refKey)) {
            return new LedgerResult(false, balance(key));
        }
        OffsetDateTime now = nextStamp();
        if (!accounts.existsById(k)) {
            A a = accountFactory.get();
            a.setAccountKey(k);
            a.setBotId(key.botId());
            a.setUserId(key.userId());
            a.setBalance(0);
            a.setUpdatedAt(now);
            accounts.saveAndFlush(a);
        }
        E e = entryFactory.get();
        e.setRefKey(refKey);
        e.setAccountKey(k);
        e.setDelta(mode == Mode.CREDIT ? amount : -amount);
        e.setReason(reason);
        e.setCreatedAt(now);
        entries.saveAndFlush(e);

        int updated = switch (mode) {
            case CREDIT -> accounts.credit(k, amount, now);
            case DEBIT -> accounts.debit(k, amount, now);
            case DEBIT_ALLOW_NEGATIVE -> accounts.debitAllowNegative(k, amount, now);
        };
        if (updated == 0) {
            // only the conditional debit can miss; throwing rolls back the entry insert
            throw new InsufficientBalanceException(amount, balance(key));
        }
        return new LedgerResult(true, balance(key));
    }

    /** Strictly increasing timestamps keep {@link #history} ordering stable for same-instant writes. */
    private OffsetDateTime nextStamp() {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return lastStamp.accumulateAndGet(now, (prev, cur) -> cur.isAfter(prev) ? cur : prev.plusNanos(1000));
    }

    private enum Mode { CREDIT, DEBIT, DEBIT_ALLOW_NEGATIVE }
}
