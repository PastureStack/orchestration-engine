package io.cattle.platform.liquibase;

import java.util.List;

import jakarta.annotation.PostConstruct;

import liquibase.exception.ServiceNotFoundException;
import liquibase.servicelocator.ServiceLocator;
import liquibase.servicelocator.StandardServiceLocator;

public class JarInJarServiceLocator implements ServiceLocator {

    private final ServiceLocator delegate = new StandardServiceLocator();

    @PostConstruct
    public void init() {
        /*
         * Liquibase 4.x/5.x removed the global ServiceLocator.setInstance(...) hook
         * and the package scanner API used by the old Rancher jar-in-jar bridge.
         * Keep this bean for Spring wiring compatibility and delegate discovery to
         * Liquibase's Java 8-compatible StandardServiceLocator.
         */
    }

    @Override
    public int getPriority() {
        return delegate.getPriority();
    }

    @Override
    public <T> List<T> findInstances(Class<T> requiredInterface) throws ServiceNotFoundException {
        return delegate.findInstances(requiredInterface);
    }

}
