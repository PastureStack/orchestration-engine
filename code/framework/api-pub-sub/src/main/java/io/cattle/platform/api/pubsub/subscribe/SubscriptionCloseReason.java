package io.cattle.platform.api.pubsub.subscribe;

/** Server-classified lifecycle metadata, never a client message or exception. */
public enum SubscriptionCloseReason {
    CLIENT_CLOSED, SERVER_CLOSED, IO_ERROR, AUTHORIZATION_FAILED, SUBSCRIBE_FAILED, INTERNAL_ERROR
}
