package com.secp.api.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticUiPublicAccessIT extends IntegrationTestBase {

  @Autowired TestRestTemplate rest;

  @Test
  void uiLoginHtml_isPublic_andServedAsHtml() {
    ResponseEntity<String> resp = rest.getForEntity("/ui/login.html", String.class);

    assertEquals(200, resp.getStatusCode().value());
    assertNotNull(resp.getHeaders().getContentType());

    String ct = resp.getHeaders().getContentType().toString().toLowerCase();
    assertTrue(ct.contains("text/html"), "expected text/html but got: " + ct);

    String body = resp.getBody();
    assertNotNull(body);
    assertTrue(body.toLowerCase().contains("<!doctype html"), "expected html body but got: " + body);
  }
}
