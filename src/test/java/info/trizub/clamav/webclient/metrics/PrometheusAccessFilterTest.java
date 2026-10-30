package info.trizub.clamav.webclient.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrometheusAccessFilterTest {
    @Test void deniesMissingTokenEvenOnAllowedRequest() throws Exception {
        var filter=new PrometheusAccessFilter("");
        var req=new MockHttpServletRequest("GET","/actuator/prometheus");
        var res=new MockHttpServletResponse();
        FilterChain chain=mock(FilterChain.class);
        filter.doFilter(req,res,chain);
        assertEquals(403,res.getStatus()); verifyNoInteractions(chain);
    }
    @Test void checksTokenAndDoesNotPermitWrongToken() throws Exception {
        var filter=new PrometheusAccessFilter("012345678901234567890123456789abcdef");
        var req=new MockHttpServletRequest("GET","/actuator/prometheus");
        req.addHeader("Authorization","Bearer invalid");
        var res=new MockHttpServletResponse(); FilterChain chain=mock(FilterChain.class);
        filter.doFilter(req,res,chain); assertEquals(403,res.getStatus());
        var valid=new MockHttpServletRequest("GET","/actuator/prometheus");
        valid.addHeader("Authorization","Bearer 012345678901234567890123456789abcdef");
        var okay=new MockHttpServletResponse();
        filter.doFilter(valid,okay,chain); verify(chain).doFilter(valid,okay);
    }
}
