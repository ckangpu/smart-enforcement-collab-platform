package com.secp.api.client;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.client.dto.ClientComplaintDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/client/complaints")
public class ClientComplaintController {

  private final ClientComplaintService clientComplaintService;

  @GetMapping
  public List<ClientComplaintDto> list() {
    AuthPrincipal client = ClientAuthContext.requireClient();
    return clientComplaintService.listMyComplaints(client);
  }
}
