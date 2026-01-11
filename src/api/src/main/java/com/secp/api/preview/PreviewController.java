package com.secp.api.preview;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.preview.dto.CreatePreviewTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/preview")
public class PreviewController {

  private final PreviewService previewService;

  @PostMapping("/files/{fileId}/tokens")
  public CreatePreviewTokenResponse createToken(@PathVariable UUID fileId, HttpServletRequest req) {
    AuthPrincipal principal = AuthContext.getRequired();
    return previewService.createToken(principal, fileId, req);
  }

  @GetMapping(produces = "application/pdf")
  public ResponseEntity<byte[]> view(@RequestParam("token") String token, HttpServletRequest req) {
    AuthPrincipal principal = AuthContext.getRequired();
    byte[] pdf = previewService.view(principal, token, req);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
        .body(pdf);
  }
}
