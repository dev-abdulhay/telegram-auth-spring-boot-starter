package io.github.dev_abdulhay.telegramauth.payments.balance;

class InMemoryBalanceLedgerTest extends BalanceLedgerContract {

    private final BalanceLedger ledger = new InMemoryBalanceLedger();

    @Override
    protected BalanceLedger ledger() {
        return ledger;
    }
}
