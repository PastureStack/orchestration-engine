/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.managed.threadlocal;

import java.util.HashSet;
import java.util.Set;

import org.apache.cloudstack.managed.context.ManagedContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManagedThreadLocal<T> extends ThreadLocal<T> {

    private static final ThreadLocal<Set<ManagedThreadLocal<?>>> MANAGED_THREAD_LOCALS = new ThreadLocal<Set<ManagedThreadLocal<?>>>() {
        @Override
        protected Set<ManagedThreadLocal<?>> initialValue() {
            return new HashSet<ManagedThreadLocal<?>>();
        }
    };

    private static boolean VALIDATE_CONTEXT = false;
    private static final Logger log = LoggerFactory.getLogger(ManagedThreadLocal.class);
    private final ThreadLocal<LocalValue<T>> value = new ThreadLocal<LocalValue<T>>();

    @Override
    public T get() {
        validateInContext(this);
        MANAGED_THREAD_LOCALS.get().add(this);
        LocalValue<T> result = value.get();
        if (result == null || result.value == null) {
            result = new LocalValue<T>(initialValue());
            value.set(result);
        }
        return result.value;
    }

    @Override
    public void set(T value) {
        validateInContext(this);
        MANAGED_THREAD_LOCALS.get().add(this);
        this.value.set(new LocalValue<T>(value));
    }

    public static void reset() {
        validateInContext(null);
        for (ManagedThreadLocal<?> local : new HashSet<ManagedThreadLocal<?>>(MANAGED_THREAD_LOCALS.get())) {
            local.clear();
        }
        MANAGED_THREAD_LOCALS.remove();
    }

    @Override
    public void remove() {
        clear();
        MANAGED_THREAD_LOCALS.get().remove(this);
    }

    private void clear() {
        value.remove();
    }

    private static void validateInContext(Object tl) {
        if (VALIDATE_CONTEXT && !ManagedContextUtils.isInContext()) {
            String msg = "Using a managed thread local in a non managed context this WILL cause errors at runtime. TL [" + tl + "]";
            log.error(msg, new IllegalStateException(msg));
        }
    }

    public static void setValidateInContext(boolean validate) {
        VALIDATE_CONTEXT = validate;
    }

    private static class LocalValue<T> {
        T value;

        LocalValue(T value) {
            this.value = value;
        }
    }
}
