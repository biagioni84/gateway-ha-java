package uy.plomo.gateway.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects HTTP requests that lack a valid X-Api-Key header.
 *
 * Activated when gateway.api.key is non-blank.
 * When blank (default), all requests are allowed — only safe while server.address=127.0.0.1.
 *
 * Spring Boot auto-registers all Filter beans in the servlet filter chain.
 */
@Component
@Slf4j
public class ApiKeyFilter extends OncePerRequestFilter {

    @Value("${gateway.api.key:}")
    private String apiKey;

    @PostConstruct
    public void logStatus() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("gateway.api.key is not set — REST API is unauthenticated " +
                     "(only safe while server.address=127.0.0.1)");
        } else {
            log.info("REST API key authentication enabled");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        if (apiKey == null || apiKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        // Allow Swagger UI and OpenAPI spec without authentication
        String uri = request.getRequestURI();
        if (uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs")) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader("X-Api-Key");
        if (apiKey.equals(provided)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rejected REST request {} {} from {} — invalid or missing X-Api-Key",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
        }
    }
}
