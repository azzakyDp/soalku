package id.soalku.material;

import id.soalku.core.Jenjang;
import id.soalku.extraction.DocumentExtractor;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/materi")
public class MaterialUploadController {
    private final MaterialUploadService service;
    public MaterialUploadController(MaterialUploadService service) { this.service=service; }

    @PostMapping(value="/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SISWA')")
    public ResponseEntity<MaterialUploadService.UploadResult> upload(@AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,@RequestParam("judul") String title,@RequestParam("jenjang") Jenjang level) {
        if (file.getSize()>DocumentExtractor.MAX_BYTES)
            throw new MaterialFailure(413,"FILE_TOO_LARGE","Ukuran file maksimal 10 MB.");
        try {
            var result=service.upload(Long.parseLong(jwt.getSubject()),title,level,file.getOriginalFilename(),file.getBytes());
            return ResponseEntity.status(result.duplikat() ? 200 : 201).body(result);
        } catch (IOException failure) {
            throw new MaterialFailure(500,"UPLOAD_READ_FAILED","File unggahan tidak dapat dibaca. Silakan coba kembali.",failure);
        }
    }
}
