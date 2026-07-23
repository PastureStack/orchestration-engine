package io.cattle.platform.api.servlet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class IndexFileTest {

    @After
    public void clearProperties() {
        clear("api.ui.index");
    }

    @Test
    public void archaiusSettingsReadDynamicIndexUrl() {
        ConfigurationManager.getConfigInstance().setProperty("api.ui.index", "local");

        IndexFileSettings settings = ArchaiusIndexFileSettings.create();

        assertEquals("local", settings.indexUrl());

        ConfigurationManager.getConfigInstance().setProperty("api.ui.index", "https://ui.example/");

        assertEquals("https://ui.example/", settings.indexUrl());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSettings() {
        new IndexFile(null);
    }

    @Test
    public void localIndexForwardsToLocalIndexHtmlAndDisablesCache() throws Exception {
        IndexFile indexFile = new IndexFile(settings("local"));
        RequestCapture request = new RequestCapture();
        ResponseCapture response = new ResponseCapture();

        indexFile.serveIndex(request.request(), response.response());

        assertEquals("/index.html", request.forwardedPath);
        assertEquals("max-age=0, no-cache", response.headers.get("Cache-Control"));
    }

    @Test
    public void cachedRemoteIndexWritesHtmlResponse() throws Exception {
        IndexFile indexFile = new IndexFile(settings("https://ui.example/index.html"));
        ResponseCapture response = new ResponseCapture();
        byte[] body = "<html>ok</html>".getBytes("UTF-8");
        setCachedIndex(indexFile, body);

        indexFile.serveIndex(new RequestCapture().request(), response.response());

        assertEquals("max-age=0, no-cache", response.headers.get("Cache-Control"));
        assertEquals(Integer.valueOf(body.length), response.contentLength);
        assertEquals("text/html", response.contentType);
        assertArrayEquals(body, response.body.toByteArray());
    }

    @Test
    public void callbackReloadsIndexWithoutChangingSettingKey() {
        MutableIndexFileSettings settings = new MutableIndexFileSettings("local");
        CountingIndexFile indexFile = new CountingIndexFile(settings);

        indexFile.init();
        settings.fire();

        assertEquals(2, indexFile.reloadCount);
        assertEquals(1, settings.callbacks.size());
    }

    @Test
    public void reloadDecisionMatchesLocalAndRemoteCacheState() throws Exception {
        IndexFile local = new IndexFile(settings("local"));
        IndexFile remote = new IndexFile(settings("https://ui.example/index.html"));
        IndexFile cachedRemote = new IndexFile(settings("https://ui.example/index.html"));
        setCachedIndex(cachedRemote, "cached".getBytes("UTF-8"));

        assertTrue(local.isLocal());
        assertFalse(local.shouldReload());
        assertTrue(remote.shouldReload());
        assertFalse(cachedRemote.shouldReload());
    }

    private static IndexFileSettings settings(final String indexUrl) {
        return new IndexFileSettings() {
            @Override
            public String indexUrl() {
                return indexUrl;
            }

            @Override
            public void addIndexUrlCallback(Runnable callback) {
            }
        };
    }

    private void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static void setCachedIndex(IndexFile indexFile, byte[] content) throws Exception {
        Field field = IndexFile.class.getDeclaredField("indexCached");
        field.setAccessible(true);
        field.set(indexFile, content);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (Boolean.TYPE.equals(type)) {
            return false;
        }
        if (Character.TYPE.equals(type)) {
            return Character.valueOf('\0');
        }
        if (Byte.TYPE.equals(type)) {
            return Byte.valueOf((byte) 0);
        }
        if (Short.TYPE.equals(type)) {
            return Short.valueOf((short) 0);
        }
        if (Integer.TYPE.equals(type)) {
            return Integer.valueOf(0);
        }
        if (Long.TYPE.equals(type)) {
            return Long.valueOf(0L);
        }
        if (Float.TYPE.equals(type)) {
            return Float.valueOf(0F);
        }
        if (Double.TYPE.equals(type)) {
            return Double.valueOf(0D);
        }
        return null;
    }

    private static class MutableIndexFileSettings implements IndexFileSettings {
        private final List<Runnable> callbacks = new ArrayList<Runnable>();
        private String indexUrl;

        MutableIndexFileSettings(String indexUrl) {
            this.indexUrl = indexUrl;
        }

        @Override
        public String indexUrl() {
            return indexUrl;
        }

        @Override
        public void addIndexUrlCallback(Runnable callback) {
            callbacks.add(callback);
        }

        void fire() {
            for (Runnable callback : callbacks) {
                callback.run();
            }
        }
    }

    private static class CountingIndexFile extends IndexFile {
        private int reloadCount;

        CountingIndexFile(IndexFileSettings settings) {
            super(settings);
        }

        @Override
        protected void reloadIndex() {
            reloadCount++;
        }
    }

    private static class RequestCapture {
        private String forwardedPath;

        HttpServletRequest request() {
            return (HttpServletRequest) Proxy.newProxyInstance(IndexFileTest.class.getClassLoader(),
                    new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> {
                        if ("getRequestDispatcher".equals(method.getName())) {
                            final String path = String.valueOf(args[0]);
                            return Proxy.newProxyInstance(IndexFileTest.class.getClassLoader(),
                                    new Class<?>[] { RequestDispatcher.class }, (dispatcherProxy, dispatcherMethod,
                                            dispatcherArgs) -> {
                                        if ("forward".equals(dispatcherMethod.getName())) {
                                            forwardedPath = path;
                                            return null;
                                        }
                                        return defaultValue(dispatcherMethod.getReturnType());
                                    });
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    private static class ResponseCapture {
        private final Map<String, String> headers = new LinkedHashMap<String, String>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private Integer contentLength;
        private String contentType;

        HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(IndexFileTest.class.getClassLoader(),
                    new Class<?>[] { HttpServletResponse.class }, (proxy, method, args) -> {
                        if ("addHeader".equals(method.getName()) || "setHeader".equals(method.getName())) {
                            headers.put(String.valueOf(args[0]), String.valueOf(args[1]));
                            return null;
                        }
                        if ("setContentLength".equals(method.getName())) {
                            contentLength = (Integer) args[0];
                            return null;
                        }
                        if ("setContentType".equals(method.getName())) {
                            contentType = String.valueOf(args[0]);
                            return null;
                        }
                        if ("getOutputStream".equals(method.getName())) {
                            return new ServletOutputStream() {
                                @Override
                                public boolean isReady() {
                                    return true;
                                }

                                @Override
                                public void setWriteListener(WriteListener writeListener) {
                                }

                                @Override
                                public void write(int b) throws IOException {
                                    body.write(b);
                                }
                            };
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }
}
