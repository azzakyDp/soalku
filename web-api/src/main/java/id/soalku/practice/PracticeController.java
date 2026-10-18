package id.soalku.practice;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/latihan") @PreAuthorize("hasRole('SISWA')")
public class PracticeController {
    private final PracticeService service;
    private final AnswerService answers;
    private final EvaluationService evaluations;
    public PracticeController(PracticeService service,AnswerService answers,EvaluationService evaluations) {
        this.service=service;this.answers=answers;this.evaluations=evaluations;
    }
    @PostMapping("/sesi") @ResponseStatus(HttpStatus.CREATED)
    public PracticeDtos.Session start(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody PracticeDtos.StartRequest request) {
        return service.start(Long.parseLong(jwt.getSubject()),key,request);
    }
    @PostMapping("/sesi/{id}/jawaban")
    public PracticeDtos.Answer submit(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,
            @Valid @RequestBody PracticeDtos.SubmitRequest request) {
        return answers.submit(Long.parseLong(jwt.getSubject()),id,request);
    }
    @GetMapping("/evaluasi")
    public PracticeDtos.Evaluation evaluate(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) Long sesiId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return evaluations.evaluate(Long.parseLong(jwt.getSubject()),sesiId,page,size);
    }
}
