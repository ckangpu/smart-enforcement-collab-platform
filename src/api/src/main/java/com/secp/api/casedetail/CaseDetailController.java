package com.secp.api.casedetail;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.casedetail.dto.CaseDetailResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/cases")
public class CaseDetailController {

  private final CaseDetailService caseDetailService;

  @GetMapping("/{caseId}/detail")
  public ResponseEntity<CaseDetailResponse> getDetail(@PathVariable UUID caseId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(caseDetailService.getDetail(principal, caseId, httpReq));
  }

  @GetMapping("/{caseId}/a4.pdf")
  public ResponseEntity<byte[]> exportA4(@PathVariable UUID caseId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    CaseDetailService.PdfResult r = caseDetailService.exportA4Pdf(principal, caseId, httpReq);
    byte[] bytes = r.bytes();

    String code = r.code() == null ? String.valueOf(caseId) : String.valueOf(r.code());
    String safe = code.replaceAll("[^A-Za-z0-9_-]", "_");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    String filename = "Case_" + safe + ".pdf";
    headers.add(HttpHeaders.CONTENT_DISPOSITION,
        "inline; filename=\"" + filename + "\"; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8));

    return ResponseEntity.ok().headers(headers).body(bytes);
  }
}
