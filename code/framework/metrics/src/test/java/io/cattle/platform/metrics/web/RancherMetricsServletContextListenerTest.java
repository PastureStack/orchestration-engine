package io.cattle.platform.metrics.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import io.cattle.platform.metrics.util.MetricsUtil;
import io.dropwizard.metrics.servlets.HealthCheckServlet;
import io.dropwizard.metrics.servlets.MetricsServlet;
import io.dropwizard.metrics.servlets.PingServlet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;

import org.junit.Test;

public class RancherMetricsServletContextListenerTest {

    @Test
    public void healthAndPingServletsDoNotRequireProfilerDependency() throws Exception {
        new HealthCheckServlet();
        new PingServlet();
    }

    @Test
    public void listenerRegistersAndRemovesMetricRegistries() {
        Map<String, Object> attributes = new HashMap<>();
        ServletContext context = servletContext(attributes);
        RancherMetricsServletContextListener listener = new RancherMetricsServletContextListener();

        listener.contextInitialized(new ServletContextEvent(context));

        assertSame(MetricsUtil.getRegistry(), attributes.get(MetricsServlet.METRICS_REGISTRY));
        assertSame(MetricsUtil.getHealthCheckRegistry(), attributes.get(HealthCheckServlet.HEALTH_CHECK_REGISTRY));

        listener.contextDestroyed(new ServletContextEvent(context));

        assertFalse(attributes.containsKey(MetricsServlet.METRICS_REGISTRY));
        assertFalse(attributes.containsKey(HealthCheckServlet.HEALTH_CHECK_REGISTRY));
    }

    private static ServletContext servletContext(final Map<String, Object> attributes) {
        return (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
                new Class<?>[] { ServletContext.class }, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("setAttribute".equals(method.getName())) {
                            attributes.put((String) args[0], args[1]);
                            return null;
                        }
                        if ("removeAttribute".equals(method.getName())) {
                            attributes.remove((String) args[0]);
                            return null;
                        }
                        if ("getAttribute".equals(method.getName())) {
                            return attributes.get((String) args[0]);
                        }
                        if ("getAttributeNames".equals(method.getName())) {
                            return Collections.enumeration(attributes.keySet());
                        }
                        if ("getContextPath".equals(method.getName())) {
                            return "";
                        }
                        if (method.getReturnType() == Boolean.TYPE) {
                            return Boolean.FALSE;
                        }
                        if (method.getReturnType() == Integer.TYPE) {
                            return 0;
                        }
                        if (method.getReturnType() == Long.TYPE) {
                            return 0L;
                        }
                        return null;
                    }
                });
    }
}
