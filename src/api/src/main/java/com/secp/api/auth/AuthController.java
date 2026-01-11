package com.secp.api.auth;

import com.secp.api.auth.dto.SendSmsRequest;
import com.secp.api.auth.dto.VerifySmsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

  private final AuthService authService;

  @PostMapping("/sms/send")
  public ResponseEntity<?> send(@Valid @RequestBody SendSmsRequest req) {
    authService.sendSms(req.phone());
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @PostMapping("/sms/verify")
  public ResponseEntity<?> verify(@Valid @RequestBody VerifySmsRequest req) {
    String jwt = authService.verifyAndIssueJwt(req.phone(), req.code());
    return ResponseEntity.ok(Map.of("token", jwt));
  }
}
