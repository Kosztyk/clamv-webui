package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import info.trizub.clamav.webclient.config.RestApiConfig;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestReportServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode job(String id, String status, String verdict, String fileStatus, String virus) throws Exception {
        ObjectNode j = (ObjectNode)json.readTree("""
            {"id":"id","status":"completed","verdict":"clean","created_at":"2026-09-01T10:00:00Z",
             "started_at":"2026-09-01T10:00:01Z","finished_at":"2026-09-01T10:00:03Z","files":
             [{"name":"=HYPERLINK(\"https://example.invalid\")","size":12,"status":"clean","result":{"viruses":[]}}]}
            """.replace("=HYPERLINK(\"https://example.invalid\")", "=malicious"));
        j.put("id",id).put("status",status).put("verdict",verdict);
        ObjectNode f=(ObjectNode)j.path("files").get(0);f.put("status",fileStatus);
        if(virus!=null)((ArrayNode)f.path("result").path("viruses")).add(virus);
        return j;
    }
    @Test void countsPartialFailedJobDetectionsWithoutCallingItClean() throws Exception {
        var jobs=List.of(job("1","completed","clean","clean",null), job("2","completed","infected","infected","Eicar"), job("3","failed","","infected","Eicar"));
        var report=RestReportService.summarize(jobs);
        assertThat(report).containsEntry("clean_jobs",1L).containsEntry("infected_jobs",1L).containsEntry("failed_jobs",1L).containsEntry("infected_files",2L).containsEntry("bytes_scanned",36L).containsEntry("average_duration_ms",2000L);
        report.put("jobs",jobs);
        assertThat(RestReportService.csv(report)).contains("\"'=malicious\"").contains("Eicar");
        assertThat(RestReportService.cell("  @SUM(1)")).startsWith("\"'");
    }
    @Test void pagesHistoryAndLabelsTruncatedCoverage() throws Exception {
        var api=mock(RestApiClient.class);
        when(api.config()).thenReturn(new RestApiConfig("http://localhost", "", "FILES",1,1,100));
        ObjectNode page=json.createObjectNode().put("total",105);ArrayNode list=page.putArray("jobs");
        for(int i=0;i<100;i++) list.add(job("id"+i,"completed","clean","clean",null));
        when(api.get(eq("history"),anyMap())).thenReturn(page);
        var report=new RestReportService(api).report(Map.of("mode","async"));
        assertThat(report).containsEntry("included_jobs",100).containsEntry("matching_retained_jobs",105).containsEntry("truncated",true);
        verify(api).get(eq("history"),argThat(q->q.get("mode").equals("async") && q.containsKey("to") && q.get("limit").equals("100")));
    }
    @Test void readsAllHistoryPages() throws Exception {
        var api=mock(RestApiClient.class);
        when(api.config()).thenReturn(new RestApiConfig("http://localhost", "", "FILES",1,1,500));
        when(api.get(eq("history"),anyMap())).thenAnswer(call->{
            Map<String,String> q=call.getArgument(1); int offset=Integer.parseInt(q.get("offset"));
            ObjectNode page=json.createObjectNode().put("total",102);ArrayNode list=page.putArray("jobs");
            for(int i=offset;i<Math.min(102,offset+100);i++)list.add(job("id"+i,"completed","clean","clean",null));
            return page;
        });
        var report=new RestReportService(api).report(Map.of());
        assertThat(report).containsEntry("included_jobs",102).containsEntry("truncated",false);
        verify(api,times(2)).get(eq("history"),anyMap());
    }
}
