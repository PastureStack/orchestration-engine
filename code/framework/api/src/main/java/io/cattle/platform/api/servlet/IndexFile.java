package io.cattle.platform.api.servlet;

import io.cattle.platform.util.net.UrlUtils;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.zip.GZIPInputStream;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class IndexFile {

    private static final Logger log = LoggerFactory.getLogger(IndexFile.class);
    private static final IndexFileSettings DEFAULT_SETTINGS = ArchaiusIndexFileSettings.create();
    private static final String LOCAL = "local";

    private final IndexFileSettings settings;
    private byte[] indexCached = null;

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
        String url = settings.indexUrl();
        return url != null && !url.equalsIgnoreCase(LOCAL) && indexCached == null;
    }

    protected void reloadIndex() {
        String url = settings.indexUrl();
        URL inputUrl = null;
        InputStream is = null;

        try {
            if (LOCAL.equals(settings.indexUrl())) {
                indexCached = null;
                return;
            }

            if (url != null) {
                if (url.startsWith("//")) {
                    url = "https:" + url;
                }
                if (!url.endsWith("index.html")) {
                    if (!url.endsWith("/")) {
                        url += "/";
                    }
                    url += "index.html";
                }
                inputUrl = UrlUtils.toURL(url);
            }

            if (inputUrl == null) {
                indexCached = null;
                return;
            }

            URLConnection connection = inputUrl.openConnection();
            connection.setRequestProperty("Accept-Encoding", "identity");
            is = connection.getInputStream();
            indexCached = decodeIndex(IOUtils.toByteArray(is), connection.getContentEncoding());
        } catch (IOException e) {
            log.error("Failed to load UI from [{}]", url, e);
            return;
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    protected byte[] decodeIndex(byte[] data, String contentEncoding) throws IOException {
        if (!"gzip".equalsIgnoreCase(contentEncoding) && !isGzip(data)) {
            return data;
        }

        GZIPInputStream gzip = null;
        try {
            gzip = new GZIPInputStream(new ByteArrayInputStream(data));
            return IOUtils.toByteArray(gzip);
        } finally {
            IOUtils.closeQuietly(gzip);
        }
    }

    protected boolean isGzip(byte[] data) {
        return data != null && data.length >= 2 && data[0] == (byte) 0x1f && data[1] == (byte) 0x8b;
    }

    public boolean canServeContent() {
        return indexCached != null || isLocal();
    }

    public boolean isLocal() {
        return LOCAL.equalsIgnoreCase(settings.indexUrl());
    }

    public void serveIndex(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (shouldReload()) {
            reloadIndex();
        }

        if (isLocal()) {
            response.addHeader("Cache-Control", "max-age=0, no-cache");
            RequestDispatcher rd = request.getRequestDispatcher("/index.html");
            rd.forward(request, response);
            return;
        }

        if (indexCached == null) {
            return;
        }

        OutputStream os = response.getOutputStream();

        response.addHeader("Cache-Control", "max-age=0, no-cache");
        response.setContentLength(indexCached.length);
        response.setContentType("text/html");

        os.write(indexCached);
    }
}
