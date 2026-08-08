package io.cattle.platform.api.servlet;

import java.io.IOException;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexFile {

    private static final Logger log = LoggerFactory.getLogger(IndexFile.class);
    private static final IndexFileSettings DEFAULT_SETTINGS = ArchaiusIndexFileSettings.create();
    private static final String LOCAL = "local";

    private final IndexFileSettings settings;

    public IndexFile() {
        this(DEFAULT_SETTINGS);
    }

    IndexFile(IndexFileSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Index file settings are required");
        }
        this.settings = settings;
    }

    @PostConstruct
    public void init() {
        reloadIndex();
        settings.addIndexUrlCallback(new Runnable() {
            @Override
            public void run() {
                reloadIndex();
            }
        });
    }

    protected boolean shouldReload() {
        return false;
    }

    protected void reloadIndex() {
        if (!isLocal()) {
            log.error("Remote UI loading is disabled; set api.ui.index=local to use the bundled UI");
        }
    }

    public boolean canServeContent() {
        return isLocal();
    }

    public boolean isLocal() {
        return LOCAL.equalsIgnoreCase(settings.indexUrl());
    }

    private void disableIndexCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
    }

    public void serveIndex(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (isLocal()) {
            disableIndexCaching(response);
            RequestDispatcher dispatcher = request.getRequestDispatcher("/index.html");
            dispatcher.forward(request, response);
            return;
        }

        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
