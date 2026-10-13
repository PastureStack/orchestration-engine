package io.github.ibuildthecloud.gdapi.model;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Objects;

public class ListOptions {

    Sort sort;
    Pagination pagination;
    Include include;
    private MutationTarget mutationTarget;

    /** Internal operand lookup, never populated from request body or query parameters. */
    public static ListOptions forMutationTarget(ApiRequest request, String type, String id) {
        return forOperand(request, type, id, new ListOptions(request), MutationTarget.Purpose.MUTATION, null);
    }

    public static ListOptions forResourceActionTarget(ApiRequest request, String type, String id) {
        return forOperand(request, type, id, new ListOptions(), MutationTarget.Purpose.RESOURCE_ACTION, null);
    }

    /** Used only after a manager has matched the actual named LinkHandler. */
    public static ListOptions forNamedLinkTarget(ApiRequest request, String type, String id, String link) {
        return forOperand(request, type, id, new ListOptions(request), MutationTarget.Purpose.NAMED_LINK, link);
    }

    private static ListOptions forOperand(ApiRequest request, String type, String id, ListOptions options,
            MutationTarget.Purpose purpose, String selectedLink) {
        options.mutationTarget = new MutationTarget(request, type, id, purpose, selectedLink);
        return options;
    }

    public MutationTarget getMutationTarget() {
        return mutationTarget;
    }

    /** A local lookup purpose, not an ambient permission for the entire request. */
    public static final class MutationTarget {
        private enum Purpose { MUTATION, RESOURCE_ACTION, NAMED_LINK }
        private final ApiRequest request;
        private final String type, id, method, action, link;
        private final Purpose purpose;
        private final String selectedLink;

        private MutationTarget(ApiRequest request, String type, String id, Purpose purpose, String selectedLink) {
            this.request = Objects.requireNonNull(request);
            this.type = type;
            this.id = id;
            this.method = request.getMethod();
            this.action = request.getAction();
            this.link = request.getLink();
            this.purpose = purpose;
            this.selectedLink = selectedLink;
        }

        public String type() { return type; }
        public String id() { return id; }
        public boolean isNamedLink() { return purpose == Purpose.NAMED_LINK; }

        public boolean matches(ApiRequest current, String lookupType, Object lookupId) {
            return current == request && type != null && id != null
                    && Objects.equals(type, current.getType()) && Objects.equals(id, current.getId())
                    && Objects.equals(type, lookupType) && Objects.equals(id, lookupId)
                    && Objects.equals(method, current.getMethod())
                    && Objects.equals(action, current.getAction()) && Objects.equals(link, current.getLink())
                    && (switch (purpose) {
                        case NAMED_LINK -> "GET".equals(method) && link != null && Objects.equals(selectedLink, link);
                        case RESOURCE_ACTION -> "POST".equals(method) && action != null;
                        case MUTATION -> action != null || "PUT".equalsIgnoreCase(method)
                                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
                    });
        }
    }

    public ListOptions() {
    }

    public ListOptions(Sort sort, Pagination pagination, Include include) {
        super();
        this.sort = sort;
        this.pagination = pagination;
        this.include = include;
    }

    public ListOptions(ApiRequest request) {
        this.sort = request.getSort();
        this.pagination = request.getPagination();
        this.include = request.getInclude();
    }

    public Sort getSort() {
        return sort;
    }

    public Pagination getPagination() {
        return pagination;
    }

    public Include getInclude() {
        return include;
    }

    public void setInclude(Include include) {
        this.include = include;
    }

}
