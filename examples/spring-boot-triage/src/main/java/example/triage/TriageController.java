package example.triage;

import io.github.maxsumrall.jev4j.JevEvaluationException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TriageController {
  private final TriageService triageService;

  TriageController(TriageService triageService) {
    this.triageService = triageService;
  }

  @PostMapping("/triage")
  TriageResponse triage(@Valid @RequestBody TriageRequest request) {
    return triageService.triage(request.message());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> invalidInput() {
    return ResponseEntity.badRequest()
        .body(
            new ApiError("INVALID_INPUT", "message must be nonblank and at most 1000 characters"));
  }

  @ExceptionHandler(JevEvaluationException.class)
  ResponseEntity<ApiError> providerFailure() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            new ApiError("EVALUATION_UNAVAILABLE", "triage evaluation is temporarily unavailable"));
  }
}
