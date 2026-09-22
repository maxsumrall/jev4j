package example.triage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("synthetic")
class TriageEndpointTest {
  @Autowired private MockMvc mvc;

  @Test
  void routesTheThreeDocumentedFixtures() throws Exception {
    assertRoute("I was charged twice for my groceries.", "BILLING", "BILLING_SUPPORT");
    assertRoute("My delivery is late and has not arrived.", "DELIVERY", "DELIVERY_SUPPORT");
    assertRoute("How do I update my loyalty card name?", "OTHER", "GENERAL_SUPPORT");
  }

  @Test
  void unfamiliarTextUsesAnExplicitLowConfidenceManualReview() throws Exception {
    mvc.perform(
            post("/triage")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("Unlisted fixture")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.selectedCategory").value("OTHER"))
        .andExpect(jsonPath("$.queue").value("MANUAL_REVIEW"))
        .andExpect(jsonPath("$.decision").value("MANUAL_REVIEW_LOW_CONFIDENCE"))
        .andExpect(jsonPath("$.confidence").value(0.41))
        .andExpect(jsonPath("$.answerSource").value("synthetic-offline-fixture"));
  }

  @Test
  void rejectsBlankAndOversizedInput() throws Exception {
    mvc.perform(post("/triage").contentType(MediaType.APPLICATION_JSON).content(json("  ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    mvc.perform(
            post("/triage").contentType(MediaType.APPLICATION_JSON).content(json("x".repeat(1001))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @Test
  void acceptsMaximumLengthInput() throws Exception {
    mvc.perform(
            post("/triage").contentType(MediaType.APPLICATION_JSON).content(json("x".repeat(1000))))
        .andExpect(status().isOk());
  }

  private void assertRoute(String message, String category, String queue) throws Exception {
    mvc.perform(post("/triage").contentType(MediaType.APPLICATION_JSON).content(json(message)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.selectedCategory").value(category))
        .andExpect(jsonPath("$.queue").value(queue))
        .andExpect(jsonPath("$.decision").value("ROUTE"))
        .andExpect(jsonPath("$.categoryProbabilities").isMap())
        .andExpect(jsonPath("$.answerSource").value("synthetic-offline-fixture"));
  }

  private static String json(String message) {
    return "{\"message\":\"" + message + "\"}";
  }
}
