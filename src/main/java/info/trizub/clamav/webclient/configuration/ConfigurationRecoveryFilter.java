package info.trizub.clamav.webclient.configuration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;

/** Always available after startup, including when SQL/LDAP/OIDC become unavailable. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public final class ConfigurationRecoveryFilter extends OncePerRequestFilter {
    private final ConfigurationSetupProtocol protocol;
    public ConfigurationRecoveryFilter(ConfigurableEnvironment environment) {
        protocol=new ConfigurationSetupProtocol(new ConfigurationEngine(environment,ConfigurationSource.install(environment),Map::of),false);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/setup") && !path.startsWith("/setup/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length()+"/setup".length());
        if(path.isEmpty()) { response.sendRedirect(request.getContextPath()+"/setup/");return; }
        Map<String,String> headers=new HashMap<>();
        for(String name:List.of("authorization","origin","host","content-type")) {
            String value=request.getHeader(name);if(value!=null) headers.put(name,value);
        }
        byte[] body=request.getInputStream().readNBytes(65537);
        var result=protocol.handle(new ConfigurationSetupProtocol.Request(request.getMethod(),path,headers,body,request.getRemoteAddr(),request.isSecure()));
        response.setStatus(result.status());response.setContentType(result.contentType());
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("X-Frame-Options","DENY");response.setHeader("Referrer-Policy","no-referrer");
        if(result.status()==429) response.setHeader("Retry-After","60");
        response.setHeader("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'");
        response.getOutputStream().write(result.body());
    }
    @PreDestroy public void close() { protocol.close(); }
}
