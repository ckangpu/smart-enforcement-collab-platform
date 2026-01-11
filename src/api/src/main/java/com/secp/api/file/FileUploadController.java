package com.secp.api.file;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.file.dto.UploadCompleteRequest;
import com.secp.api.file.dto.UploadCompleteResponse;
import com.secp.api.file.dto.UploadInitRequest;
import com.secp.api.file.dto.UploadInitResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
public class FileUploadController {

  private final FileUploadService fileUploadService;

  @PostMapping("/upload-init")
  public UploadInitResponse init(@Valid @RequestBody UploadInitRequest req) {
    AuthPrincipal principal = AuthContext.getRequired();
    return fileUploadService.uploadInit(principal, req);
  }

  @PostMapping("/upload-complete")
  public ResponseEntity<UploadCompleteResponse> complete(@Valid @RequestBody UploadCompleteRequest req,
                                                         HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    UploadCompleteResponse resp = fileUploadService.uploadComplete(principal, req, httpReq);
    return ResponseEntity.ok(resp);
  }
}
