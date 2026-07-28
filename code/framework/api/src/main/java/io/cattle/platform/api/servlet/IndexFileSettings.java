package io.cattle.platform.api.servlet;

interface IndexFileSettings {
    String indexUrl();

    void addIndexUrlCallback(Runnable callback);
}
