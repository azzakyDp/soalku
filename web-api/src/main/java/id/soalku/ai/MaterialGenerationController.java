package id.soalku.ai;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class MaterialGenerationController {
    private final MaterialGenerationService service;
    public MaterialGenerationController(MaterialGenerationService service) { this.service=service; }
    @PostMapping("/api/materi/{id}/generate")
    @PreAuthorize("hasRole('SISWA')")
    public ResponseEntity<MaterialGenerationService.GenerationResult> generate(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id) {
        var outcome=service.generate(Long.parseLong(jwt.getSubject()),id);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }
}
