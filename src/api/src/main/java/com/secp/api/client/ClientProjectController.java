package com.secp.api.client;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.client.dto.ClientPaymentDto;
import com.secp.api.client.dto.ClientProjectDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/client/projects")
public class ClientProjectController {

  private final ClientProjectService clientProjectService;

  @GetMapping
  public List<ClientProjectDto> listProjects() {
    AuthPrincipal client = ClientAuthContext.requireClient();
    return clientProjectService.listMyProjects(client);
  }

  @GetMapping("/{projectId}/payments")
  public List<ClientPaymentDto> listPayments(@PathVariable UUID projectId,
                                             @RequestParam(value = "caseId", required = false) UUID caseId) {
    AuthPrincipal client = ClientAuthContext.requireClient();
    return clientProjectService.listProjectPayments(client, projectId, caseId);
  }
}
