package id.soalku.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service) { this.service=service; }
    @GetMapping("/api/dashboard/siswa") @PreAuthorize("hasRole('SISWA')")
    public DashboardDtos.Student student(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return service.student(Long.parseLong(jwt.getSubject()),page,size);
    }
    @GetMapping("/api/mentor/dashboard") @PreAuthorize("hasRole('MENTOR')")
    public DashboardDtos.Mentor mentor(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") int reviewPage,@RequestParam(defaultValue="10") int reviewSize,
            @RequestParam(defaultValue="0") int siswaPage,@RequestParam(defaultValue="20") int siswaSize) {
        return service.mentor(Long.parseLong(jwt.getSubject()),reviewPage,reviewSize,siswaPage,siswaSize);
    }
}
