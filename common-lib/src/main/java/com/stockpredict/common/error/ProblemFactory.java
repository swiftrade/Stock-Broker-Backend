package com.stockpredict.common.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ProblemDetail;

/** Builds RFC 7807 {@link ProblemDetail} bodies from {@link ErrorCode}s. */
public final class ProblemFactory {

    private ProblemFactory() {
    }

    public static ProblemDetail from(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                code.status(), detail == null ? code.defaultTitle() : detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.defaultTitle());
        problem.setProperty("code", code.name());
        return problem;
    }

    public static ProblemDetail from(ErrorCode code, String detail, Map<String, Object> extensions) {
        ProblemDetail problem = from(code, detail);
        if (extensions != null) {
            extensions.forEach(problem::setProperty);
        }
        return problem;
    }

    /**
     * Plain map form used where the Spring ProblemDetail Jackson mixin is not
     * guaranteed (e.g. a servlet filter writing the response manually).
     */
    public static Map<String, Object> toMap(ErrorCode code, String detail, URI instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.type());
        body.put("title", code.defaultTitle());
        body.put("status", code.status().value());
        body.put("detail", detail == null ? code.defaultTitle() : detail);
        if (instance != null) {
            body.put("instance", instance.toString());
        }
        body.put("code", code.name());
        return body;
    }
}
