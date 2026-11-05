package com.example.backend.shortform;

import com.example.backend.shortform.edit.EditPlan;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/shortforms")
public class ShortformEditingController {
    private final ShortformEditingService service;
    public ShortformEditingController(ShortformEditingService service) { this.service = service; }
    @PostMapping("/{id}/edit-plan") public EditPlan preview(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @Valid @RequestBody ShortformDtos.RenderRequest request) {
        return service.preview(authorization, id, request);
    }
    @GetMapping("/{id}/renders/{jobId}/video") public ResponseEntity<Resource> video(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @PathVariable UUID jobId) { return response(service.result(authorization, id, jobId, false)); }
    @GetMapping("/{id}/renders/{jobId}/thumbnail") public ResponseEntity<Resource> thumbnail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @PathVariable UUID jobId) { return response(service.result(authorization, id, jobId, true)); }
    private ResponseEntity<Resource> response(ShortformService.Download download) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(download.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(download.filename()).build().toString())
                .body(download.resource());
    }
}
