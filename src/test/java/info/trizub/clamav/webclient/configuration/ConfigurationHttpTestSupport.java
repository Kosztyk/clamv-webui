package info.trizub.clamav.webclient.configuration;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercise the real central HTTP review/apply boundary in compatibility tests. */
public final class ConfigurationHttpTestSupport {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ConfigurationHttpTestSupport() { }
    public static MockHttpServletRequestBuilder request(String action,Map<String,?> body,MockHttpSession session) throws Exception {
        return post("/admin/configuration-center/api/"+action).with(user("admin").roles("ADMIN")).with(csrf()).secure(true)
                .session(session).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }
    public static JsonNode review(MockMvc mvc,ConfigurationCenterService center,Map<String,String> changes,MockHttpSession session) throws Exception {
        var result=mvc.perform(request("review",Map.of("version",center.engine().store().snapshot().revision(),"changes",changes),session))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
    public static void apply(MockMvc mvc,JsonNode reviewed,MockHttpSession session,String password) throws Exception {
        mvc.perform(request("apply",Map.of("reviewId",reviewed.get("reviewId").asText(),"verificationUsername","admin",
                "verificationPassword",password,"acknowledgeDatabase",true),session)).andExpect(status().isOk());
    }
}
