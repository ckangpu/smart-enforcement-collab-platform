package com.secp.api.project;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.project.dto.ProjectDetailResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects")
public class ProjectDetailController {

  private final ProjectDetailService projectDetailService;

  @GetMapping("/{projectId}/detail")
  public ResponseEntity<ProjectDetailResponse> getDetail(@PathVariable UUID projectId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    ProjectDetailResponse resp = projectDetailService.getDetail(principal, projectId, httpReq);
    return ResponseEntity.ok(resp);
  }

  @GetMapping("/{projectId}/a4.pdf")
  public ResponseEntity<byte[]> exportA4(@PathVariable UUID projectId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    byte[] bytes = projectDetailService.exportA4Pdf(principal, projectId, httpReq);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=Project_" + projectId + ".pdf");

    return ResponseEntity.ok().headers(headers).body(bytes);
  }
}
