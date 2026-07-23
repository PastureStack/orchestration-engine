package io.cattle.platform.jmx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class JmxQuery {

    private final String objectName;
    private final String resultAlias;
    private final List<JmxAttributeQuery> attributes;

    JmxQuery(String objectName, String resultAlias, List<JmxAttributeQuery> attributes) {
        if (EmbeddedJmxTransPublisher.isBlank(objectName)) {
            throw new IllegalArgumentException("objectName is required");
        }
        this.objectName = objectName;
        this.resultAlias = EmbeddedJmxTransPublisher.isBlank(resultAlias) ? objectName : resultAlias;
        this.attributes = attributes == null ? Collections.<JmxAttributeQuery>emptyList()
                : Collections.unmodifiableList(new ArrayList<JmxAttributeQuery>(attributes));
    }

    String getObjectName() {
        return objectName;
    }

    String getResultAlias() {
        return resultAlias;
    }

    List<JmxAttributeQuery> getAttributes() {
        return attributes;
    }
}
