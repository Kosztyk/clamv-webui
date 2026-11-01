package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.configuration.*;
import info.trizub.clamav.webclient.service.AuditService;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** Admin authorization and browser CSRF remain in the application's security chain. */
@Controller
@RequestMapping("/admin/configuration-center")
public final class ConfigurationCenterController {
    private static final String REVIEW=ConfigurationCenterController.class.getName()+".review";
    private final ConfigurationCenterService service;
    private final ConfigurationConnectionTester tests;
    private final AuditService audit;
    public ConfigurationCenterController(ConfigurationCenterService service,ConfigurationConnectionTester tests,AuditService audit) {
        this.service=service;this.tests=tests;this.audit=audit;
    }
    public record Draft(String version,Map<String,String> changes,Set<String> clearSecrets,String section,boolean acknowledgeHttp) {
        public Draft { changes=changes==null?Map.of():Map.copyOf(changes);clearSecrets=clearSecrets==null?Set.of():Set.copyOf(clearSecrets); }
    }
    public record Apply(String reviewId,boolean acknowledgeDatabase,String verificationUsername,String verificationPassword,boolean acknowledgeHttp) { }
    @GetMapping public String page(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return "pages/configuration-center";
    }
    @GetMapping("/api/view") @ResponseBody
    public ResponseEntity<?> view() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.engine().view()); }
    @PostMapping("/api/review") @ResponseBody
    public ResponseEntity<?> review(@RequestBody Draft draft,HttpServletRequest request) {
        try {
            requireCredentialTransport(draft,request);
            var review=service.engine().review(draft.version(),draft.changes(),draft.clearSecrets());
            service.validateRuntimeChanges(review);
            request.getSession().setAttribute(REVIEW,review);
            return ok(Map.of("reviewId",review.id(),"changes",review.summary(),"sensitive",review.sensitive(),
                    "databaseChange",review.databaseChange(),"expires",review.expires().toString()));
        } catch(Exception e) { return failure(e); }
    }
    @PostMapping("/api/apply") @ResponseBody
    public ResponseEntity<?> apply(@RequestBody Apply input,HttpServletRequest request,Authentication actor) {
        try {
            HttpSession session=request.getSession(false);
            Object saved=session==null?null:session.getAttribute(REVIEW);
            if(!(saved instanceof ConfigurationEngine.Review review) || !review.id().equals(input.reviewId())
                    || review.expires().isBefore(Instant.now())) throw new IllegalArgumentException("Review expired; review the changes again");
            if(review.sensitive()) {
                ConfigurationTransport.requireConfirmation(request.isSecure(),input.acknowledgeHttp());
                service.verifyAdministrator(input.verificationUsername(),input.verificationPassword());
            }
            service.validateRuntimeChanges(review);
            session.removeAttribute(REVIEW);
            service.engine().apply(review,input.acknowledgeDatabase(),actor.getName());
            String message="Configuration saved.";
            try { service.applyLive(review); }
            catch(RuntimeException e) { message="Configuration saved. A live update could not complete; restart the Web UI to load the saved values."; }
            try { audit.record(actor,request,"CONFIGURATION_CENTER_UPDATE","keys="+review.summary().stream().map(ConfigurationEngine.Change::key).toList(),"SUCCESS",null); }
            catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(ConfigurationCenterController.class).warn("Configuration saved and independently audited; database audit is temporarily unavailable."); }
            return ok(Map.of("ok",true,"message",message,"view",service.engine().view()));
        } catch(Exception e) { return failure(e); }
    }
    @PostMapping("/api/test") @ResponseBody
    public ResponseEntity<?> test(@RequestBody Draft draft,HttpServletRequest request,Authentication actor) {
        try {
            requireCredentialTransport(draft,request);
            var review=service.engine().review(draft.version(),draft.changes(),draft.clearSecrets());
            service.validateRuntimeChanges(review);
            var result=tests.test(draft.section(),service.engine().draftValues(review));
            audit.record(actor,request,"CONFIGURATION_CONNECTION_TEST","section="+draft.section(),
                Boolean.TRUE.equals(result.get("ok"))?"SUCCESS":"NOT_INITIALIZED",null);
            return ok(result);
        } catch(Exception e) { return failure(e); }
    }
    private static void requireCredentialTransport(Draft draft,HttpServletRequest request) {
        if(draft.changes().entrySet().stream().anyMatch(e->
                ConfigurationCatalog.get(e.getKey()).secret() && !e.getValue().isBlank()))
            ConfigurationTransport.requireConfirmation(request.isSecure(),draft.acknowledgeHttp());
    }
    private static ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static ResponseEntity<?> failure(Exception e) {
        boolean conflict=e instanceof ConcurrentModificationException;
        String message=e instanceof IllegalArgumentException || conflict?e.getMessage():"Configuration operation could not complete. Check application health and conf permissions; submitted secrets are not included in this response.";
        return ResponseEntity.status(conflict?409:400).cacheControl(CacheControl.noStore()).body(Map.of("ok",false,"message",message));
    }
}
