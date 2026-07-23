package io.cattle.platform.metrics.web;

import io.cattle.platform.metrics.util.MetricsUtil;
import io.dropwizard.metrics.servlets.HealthCheckServlet;
import io.dropwizard.metrics.servlets.MetricsServlet;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

public class RancherMetricsServletContextListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        context.setAttribute(MetricsServlet.METRICS_REGISTRY, MetricsUtil.getRegistry());
        context.setAttribute(HealthCheckServlet.HEALTH_CHECK_REGISTRY, MetricsUtil.getHealthCheckRegistry());
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        context.removeAttribute(MetricsServlet.METRICS_REGISTRY);
        context.removeAttribute(HealthCheckServlet.HEALTH_CHECK_REGISTRY);
    }
}
