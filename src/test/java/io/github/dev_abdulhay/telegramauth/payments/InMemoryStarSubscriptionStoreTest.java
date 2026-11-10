package io.github.dev_abdulhay.telegramauth.payments;

class InMemoryStarSubscriptionStoreTest extends StarSubscriptionStoreContract {

    private final StarSubscriptionStore store = new InMemoryStarSubscriptionStore();

    @Override
    protected StarSubscriptionStore store() {
        return store;
    }
}
