package example.triage;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.maxsumrall.jev4j.JevEvaluationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("synthetic")
class ProviderFailureEndpointTest {
  @Autowired private MockMvc mvc;
  @MockitoBean private TriageAnswerSource answerSource;

  @Test
  void returnsSanitizedServiceUnavailableInsteadOfInventingAClassification() throws Exception {
    when(answerSource.evaluate(anyString()))
        .thenThrow(new JevEvaluationException("provider said secret request details"));

    mvc.perform(
            post("/triage")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"private customer message\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("EVALUATION_UNAVAILABLE"))
        .andExpect(jsonPath("$.message").value("triage evaluation is temporarily unavailable"))
        .andExpect(jsonPath("$.queue").doesNotExist());
  }
}
