package io.cattle.platform.docker.api.transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.model.Instance;
import io.cattle.platform.docker.transform.DockerInspectTransformVolume;
import io.cattle.platform.docker.transform.DockerTransformer;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class TransformInspectTest {

    @Test
    public void transformRequestPassesTypedInspectMapToTransformer() throws IOException {
        TransformInspect handler = new TransformInspect();
        CapturingTransformer transformer = new CapturingTransformer();
        handler.transformer = transformer;
        handler.jsonMapper = new JacksonJsonMapper();

        TestApiRequest request = new TestApiRequest("POST", "transform",
                "{\"Config\":{\"Image\":\"alpine:3.20\"},\"Name\":\"container-a\"}");

        assertTrue(handler.handle(request));
        assertEquals("application/json", request.getResponseContentType());
        assertTrue(request.getResponseObject() instanceof Instance);
        assertEquals("container-a", transformer.inspect.get("Name"));
        assertTrue(transformer.inspect.get("Config") instanceof Map<?, ?>);
    }

    @Test
    public void nonTransformRequestIsIgnored() throws IOException {
        TransformInspect handler = new TransformInspect();

        assertFalse(handler.handle(new TestApiRequest("POST", "other", "{}")));
    }

    private static final class CapturingTransformer implements DockerTransformer {
        private Map<String, Object> inspect;

        @Override
        public void transform(Map<String, Object> fromInspect, Instance toInstance) {
            inspect = fromInspect;
        }

        @Override
        public List<DockerInspectTransformVolume> transformVolumes(Map<String, Object> fromInspect, List<Object> mounts) {
            return Collections.emptyList();
        }

        @Override
        public void setLabels(Instance instance, Map<String, Object> fromInspect) {
        }
    }

    private static final class TestApiRequest extends ApiRequest {
        private final byte[] body;

        TestApiRequest(String method, String id, String body) {
            super(null, null);
            setMethod(method);
            setId(id);
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }
    }
}
