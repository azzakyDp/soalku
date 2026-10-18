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
    public PracticeController(PracticeService service) { this.service=service; }
    @PostMapping("/sesi") @ResponseStatus(HttpStatus.CREATED)
    public PracticeDtos.Session start(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody PracticeDtos.StartRequest request) {
        return service.start(Long.parseLong(jwt.getSubject()),key,request);
    }
}
