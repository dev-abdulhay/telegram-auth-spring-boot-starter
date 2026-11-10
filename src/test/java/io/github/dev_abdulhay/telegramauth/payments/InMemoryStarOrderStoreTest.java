package io.github.dev_abdulhay.telegramauth.payments;

class InMemoryStarOrderStoreTest extends StarOrderStoreContract {

    private final StarOrderStore store = new InMemoryStarOrderStore();

    @Override
    protected StarOrderStore store() {
        return store;
    }
}
