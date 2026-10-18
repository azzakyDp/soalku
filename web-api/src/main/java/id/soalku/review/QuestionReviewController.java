package id.soalku.review;

import id.soalku.core.StatusReview;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mentor/soal")
@PreAuthorize("hasRole('MENTOR')")
public class QuestionReviewController {
    private final QuestionReviewService service;
    public QuestionReviewController(QuestionReviewService service) { this.service=service; }

    @GetMapping
    public ReviewDtos.Queue pending(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return service.pending(Long.parseLong(jwt.getSubject()),page,size);
    }
    @PutMapping("/{id}")
    public ReviewDtos.Question edit(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,
            @Valid @RequestBody ReviewDtos.EditRequest request) {
        return service.edit(Long.parseLong(jwt.getSubject()),id,request);
    }
    @PostMapping("/{id}/approve")
    public ReviewDtos.Question approve(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id) {
        return service.decide(Long.parseLong(jwt.getSubject()),id,StatusReview.DISETUJUI);
    }
    @PostMapping("/{id}/reject")
    public ReviewDtos.Question reject(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id) {
        return service.decide(Long.parseLong(jwt.getSubject()),id,StatusReview.DITOLAK);
    }
}
