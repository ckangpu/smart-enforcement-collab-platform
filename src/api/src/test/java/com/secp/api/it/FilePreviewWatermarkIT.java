package com.secp.api.it;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.auth.JwtService;
import com.secp.api.infra.RlsSessionJdbc;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FilePreviewWatermarkIT extends IntegrationTestBase {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired JwtService jwtService;
  @Autowired RlsSessionJdbc rls;

  @Test
  void testExternalPreviewIsImageBasedAndAudited() throws Exception {
    UUID internalAdmin = UUID.randomUUID();
    UUID externalUser = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();

    seed(internalAdmin, externalUser, group, project, caseId);

    String internalToken = jwtService.sign(new AuthPrincipal(internalAdmin, true, "internal", "internal"));
    String externalToken = jwtService.sign(new AuthPrincipal(externalUser, false, "external_u", "external"));

    HttpHeaders internalHeaders = bearer(internalToken);
    HttpHeaders externalHeaders = bearer(externalToken);

    byte[] rawPdf = createPdfWithText("hello world");

    Upload uploaded = uploadRawPdf(internalHeaders, caseId, rawPdf, "doc.pdf");
    UUID fileId = uploaded.fileId;

    // grant external access via temp_grant
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      rls.applyRlsSession(internalAdmin.toString(), true, "");
      jdbc.update(
          "insert into temp_grant(user_id, object_type, object_id, permission_set, expires_at) values (?,?,?, ?::jsonb, ?)",
          externalUser,
          "file",
          fileId,
          "{\"can_view\":true}",
          OffsetDateTime.now().plusDays(1)
      );
      return null;
    });

    // external create preview token
    ResponseEntity<Map> tokenResp = rest.exchange(
        "/preview/files/" + fileId + "/tokens",
        HttpMethod.POST,
        new HttpEntity<>(externalHeaders),
        Map.class
    );
    assertEquals(200, tokenResp.getStatusCode().value());
    assertNotNull(tokenResp.getBody());
    String token = String.valueOf(tokenResp.getBody().get("token"));
    assertNotNull(token);
    assertFalse(token.isBlank());

    // external view preview (forced external variant)
    ResponseEntity<byte[]> viewResp = rest.exchange(
        "/preview?token=" + token,
        HttpMethod.GET,
        new HttpEntity<>(externalHeaders),
        byte[].class
    );
    assertEquals(200, viewResp.getStatusCode().value());
    assertNotNull(viewResp.getBody());
    assertNotNull(viewResp.getHeaders().getContentType());
    assertEquals(MediaType.APPLICATION_PDF, viewResp.getHeaders().getContentType());

    // Text extraction must be empty (image-based PDF)
    try (PDDocument doc = PDDocument.load(viewResp.getBody())) {
      String text = new PDFTextStripper().getText(doc);
      assertTrue(text == null || text.trim().isEmpty());
    }

    // audit exists for preview_token_create + preview_view
    tx.execute(status -> {
      rls.applyRlsSession(internalAdmin.toString(), true, "");
      Integer c1 = jdbc.queryForObject(
          "select count(1) from audit_log where actor_user_id=? and action='preview_token_create'",
          Integer.class,
          externalUser
      );
      Integer c2 = jdbc.queryForObject(
          "select count(1) from audit_log where actor_user_id=? and action='preview_view' and object_type='file_store' and object_id=?",
          Integer.class,
          externalUser,
          fileId
      );
      assertTrue(c1 != null && c1 > 0);
      assertTrue(c2 != null && c2 > 0);

      String wmVer = jdbc.queryForObject(
          "select summary->>'wmVer' from audit_log where actor_user_id=? and action='preview_view' order by created_at desc limit 1",
          String.class,
          externalUser
      );
      String hash = jdbc.queryForObject(
          "select summary->>'watermarkTextHash' from audit_log where actor_user_id=? and action='preview_view' order by created_at desc limit 1",
          String.class,
          externalUser
      );
      assertEquals("1", wmVer);
      assertNotNull(hash);
      assertFalse(hash.isBlank());
      return null;
    });
  }

  @Test
  void testTokenCannotBeForwardedEvenIfOtherViewerHasAccess() throws Exception {
    UUID internalAdmin = UUID.randomUUID();
    UUID externalUser1 = UUID.randomUUID();
    UUID externalUser2 = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();

    seed3(internalAdmin, externalUser1, externalUser2, group, project, caseId);

    String internalToken = jwtService.sign(new AuthPrincipal(internalAdmin, true, "internal", "internal"));
    String ext1Token = jwtService.sign(new AuthPrincipal(externalUser1, false, "external_1", "external"));
    String ext2Token = jwtService.sign(new AuthPrincipal(externalUser2, false, "external_2", "external"));

    HttpHeaders internalHeaders = bearer(internalToken);
    HttpHeaders ext1Headers = bearer(ext1Token);
    HttpHeaders ext2Headers = bearer(ext2Token);

    Upload uploaded = uploadRawPdf(internalHeaders, caseId, createPdfWithText("hello"), "doc.pdf");

    grantView(internalAdmin, externalUser1, uploaded.fileId);
    grantView(internalAdmin, externalUser2, uploaded.fileId);

    ResponseEntity<Map> tokenResp = rest.exchange(
        "/preview/files/" + uploaded.fileId + "/tokens",
        HttpMethod.POST,
        new HttpEntity<>(ext1Headers),
        Map.class
    );
    assertEquals(200, tokenResp.getStatusCode().value());
    String token = String.valueOf(Objects.requireNonNull(tokenResp.getBody()).get("token"));

    // Forwarded token used by different viewer must 404 even if they have access to the file.
    ResponseEntity<byte[]> viewResp = rest.exchange(
        "/preview?token=" + token,
        HttpMethod.GET,
        new HttpEntity<>(ext2Headers),
        byte[].class
    );
    assertEquals(404, viewResp.getStatusCode().value());
  }

  @Test
  void testExpiredTokenReturnsNotFound() throws Exception {
    UUID internalAdmin = UUID.randomUUID();
    UUID externalUser = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();

    seed(internalAdmin, externalUser, group, project, caseId);

    String internalToken = jwtService.sign(new AuthPrincipal(internalAdmin, true, "internal", "internal"));
    String externalToken = jwtService.sign(new AuthPrincipal(externalUser, false, "external_u", "external"));
    HttpHeaders internalHeaders = bearer(internalToken);
    HttpHeaders externalHeaders = bearer(externalToken);

    Upload uploaded = uploadRawPdf(internalHeaders, caseId, createPdfWithText("hello"), "doc.pdf");
    grantView(internalAdmin, externalUser, uploaded.fileId);

    ResponseEntity<Map> tokenResp = rest.exchange(
        "/preview/files/" + uploaded.fileId + "/tokens",
        HttpMethod.POST,
        new HttpEntity<>(externalHeaders),
        Map.class
    );
    assertEquals(200, tokenResp.getStatusCode().value());
    String token = String.valueOf(Objects.requireNonNull(tokenResp.getBody()).get("token"));

    // Expire token in DB
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      rls.applyRlsSession(internalAdmin.toString(), true, "");
      jdbc.update(
          "update file_preview_token set expires_at = now() - interval '1 minute' where viewer_user_id=? and file_id=?",
          externalUser,
          uploaded.fileId
      );
      return null;
    });

    ResponseEntity<byte[]> viewResp = rest.exchange(
        "/preview?token=" + token,
        HttpMethod.GET,
        new HttpEntity<>(externalHeaders),
        byte[].class
    );
    assertEquals(404, viewResp.getStatusCode().value());
  }

  @Test
  void testCacheIndexHitOnSecondView() throws Exception {
    UUID internalAdmin = UUID.randomUUID();
    UUID externalUser = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();

    seed(internalAdmin, externalUser, group, project, caseId);

    String internalToken = jwtService.sign(new AuthPrincipal(internalAdmin, true, "internal", "internal"));
    String externalToken = jwtService.sign(new AuthPrincipal(externalUser, false, "external_u", "external"));
    HttpHeaders internalHeaders = bearer(internalToken);
    HttpHeaders externalHeaders = bearer(externalToken);

    Upload uploaded = uploadRawPdf(internalHeaders, caseId, createPdfWithText("hello"), "doc.pdf");
    grantView(internalAdmin, externalUser, uploaded.fileId);

    // token 1 -> view (cache miss)
    String t1 = createPreviewToken(uploaded.fileId, externalHeaders);
    ResponseEntity<byte[]> v1 = rest.exchange(
        "/preview?token=" + t1,
        HttpMethod.GET,
        new HttpEntity<>(externalHeaders),
        byte[].class
    );
    assertEquals(200, v1.getStatusCode().value());

    // token 2 -> view (should be cache hit)
    String t2 = createPreviewToken(uploaded.fileId, externalHeaders);
    ResponseEntity<byte[]> v2 = rest.exchange(
        "/preview?token=" + t2,
        HttpMethod.GET,
        new HttpEntity<>(externalHeaders),
        byte[].class
    );
    assertEquals(200, v2.getStatusCode().value());

    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      rls.applyRlsSession(internalAdmin.toString(), true, "");
      Integer idxCount = jdbc.queryForObject(
          "select count(1) from preview_index where file_id=? and viewer_user_id=?",
          Integer.class,
          uploaded.fileId,
          externalUser
      );
      assertNotNull(idxCount);
      assertTrue(idxCount >= 1);

      String cacheHit = jdbc.queryForObject(
          "select summary->>'cacheHit' from audit_log where actor_user_id=? and action='preview_view' order by created_at desc limit 1",
          String.class,
          externalUser
      );
      assertEquals("true", cacheHit);

        String wmVer = jdbc.queryForObject(
          "select summary->>'wmVer' from audit_log where actor_user_id=? and action='preview_view' order by created_at desc limit 1",
          String.class,
          externalUser
        );
        String hash = jdbc.queryForObject(
          "select summary->>'watermarkTextHash' from audit_log where actor_user_id=? and action='preview_view' order by created_at desc limit 1",
          String.class,
          externalUser
        );
        assertEquals("1", wmVer);
        assertNotNull(hash);
        assertFalse(hash.isBlank());
      return null;
    });
  }

  @Test
  void testUploadCompleteIsIdempotentNoDuplicateAuditOutbox() throws Exception {
    UUID internalAdmin = UUID.randomUUID();
    UUID externalUser = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();

    seed(internalAdmin, externalUser, group, project, caseId);

    String internalToken = jwtService.sign(new AuthPrincipal(internalAdmin, true, "internal", "internal"));
    HttpHeaders internalHeaders = bearer(internalToken);

    byte[] rawPdf = createPdfWithText("hello");

    // upload-init
    ResponseEntity<Map> initResp = rest.exchange(
        "/files/upload-init",
        HttpMethod.POST,
        new HttpEntity<>(Map.of(
            "caseId", caseId.toString(),
            "filename", "doc.pdf",
            "contentType", "application/pdf",
            "sizeBytes", rawPdf.length
        ), json(internalHeaders)),
        Map.class
    );
    assertEquals(200, initResp.getStatusCode().value());
    UUID fileId = UUID.fromString(String.valueOf(Objects.requireNonNull(initResp.getBody()).get("fileId")));
    String s3KeyRaw = String.valueOf(initResp.getBody().get("s3KeyRaw"));
    String putUrl = String.valueOf(initResp.getBody().get("presignedPutUrl"));
    httpPut(putUrl, "application/pdf", rawPdf);

    Map<String, Object> completeReq = new java.util.HashMap<>();
    completeReq.put("fileId", fileId.toString());
    completeReq.put("caseId", caseId.toString());
    completeReq.put("filename", "doc.pdf");
    completeReq.put("contentType", "application/pdf");
    completeReq.put("sizeBytes", rawPdf.length);
    completeReq.put("s3KeyRaw", s3KeyRaw);

    ResponseEntity<Map> c1 = rest.exchange(
        "/files/upload-complete",
        HttpMethod.POST,
        new HttpEntity<>(completeReq, json(internalHeaders)),
        Map.class
    );
    assertEquals(200, c1.getStatusCode().value());

    ResponseEntity<Map> c2 = rest.exchange(
        "/files/upload-complete",
        HttpMethod.POST,
        new HttpEntity<>(completeReq, json(internalHeaders)),
        Map.class
    );
    assertEquals(200, c2.getStatusCode().value());

    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      rls.applyRlsSession(internalAdmin.toString(), true, "");
      String st = jdbc.queryForObject("select status from file_store where id=?", String.class, fileId);
      assertEquals("READY", st);

      Integer a = jdbc.queryForObject(
          "select count(1) from audit_log where actor_user_id=? and action='upload_complete' and object_id=?",
          Integer.class,
          internalAdmin,
          fileId
      );
      Integer o = jdbc.queryForObject(
          "select count(1) from event_outbox where dedupe_key=?",
          Integer.class,
          "File.Uploaded:file:" + fileId + ":v1"
      );
      assertEquals(1, a);
      assertEquals(1, o);
      return null;
    });
  }

  @Test
  void testPreviewStripsEmbeddedFilesAndMetadata() throws Exception {
    UUID internalAdmin = UUID.randomUUID();
    UUID externalUser = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();

    seed(internalAdmin, externalUser, group, project, caseId);

    String internalToken = jwtService.sign(new AuthPrincipal(internalAdmin, true, "internal", "internal"));
    HttpHeaders internalHeaders = bearer(internalToken);

    byte[] rawPdf = createPdfWithEmbeddedFileAndMetadata();
    Upload uploaded = uploadRawPdf(internalHeaders, caseId, rawPdf, "doc.pdf");

    String token = createPreviewToken(uploaded.fileId, internalHeaders);
    ResponseEntity<byte[]> viewResp = rest.exchange(
        "/preview?token=" + token,
        HttpMethod.GET,
        new HttpEntity<>(internalHeaders),
        byte[].class
    );
    assertEquals(200, viewResp.getStatusCode().value());

    try (PDDocument doc = PDDocument.load(viewResp.getBody())) {
      // metadata cleared
      assertTrue(doc.getDocumentInformation() == null
          || doc.getDocumentInformation().getTitle() == null
          || doc.getDocumentInformation().getTitle().isBlank());

      // embedded files removed
      PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
      PDEmbeddedFilesNameTreeNode embedded = names.getEmbeddedFiles();
      assertTrue(embedded == null || embedded.getNames() == null || embedded.getNames().isEmpty());
      assertNull(doc.getDocumentCatalog().getCOSObject().getItem("AF"));
    }
  }

  private void seed(UUID internalAdmin, UUID externalUser, UUID group, UUID project, UUID caseId) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("insert into app_group(id, name) values (?,?)", group, "G");
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalAdmin, "13900009999", "internal", "internal", true);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          externalUser, "13900001234", "external_u", "external", false);

      rls.applyRlsSession(internalAdmin.toString(), true, "");
      jdbc.update("insert into project(id, group_id, name, status, created_by, customer_id) values (?,?,?,?,?,?)",
          project, group, "P", "ACTIVE", internalAdmin, null);
      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseId, group, project, "Case", "OPEN", internalAdmin);
      return null;
    });
  }

  private void seed3(UUID internalAdmin, UUID externalUser1, UUID externalUser2, UUID group, UUID project, UUID caseId) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("insert into app_group(id, name) values (?,?)", group, "G");
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalAdmin, "13900009999", "internal", "internal", true);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          externalUser1, "13900001234", "external_1", "external", false);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          externalUser2, "13900005678", "external_2", "external", false);

      rls.applyRlsSession(internalAdmin.toString(), true, "");
      jdbc.update("insert into project(id, group_id, name, status, created_by, customer_id) values (?,?,?,?,?,?)",
          project, group, "P", "ACTIVE", internalAdmin, null);
      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseId, group, project, "Case", "OPEN", internalAdmin);
      return null;
    });
  }

  private void grantView(UUID adminUserId, UUID granteeUserId, UUID fileId) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      rls.applyRlsSession(adminUserId.toString(), true, "");
      jdbc.update(
          "insert into temp_grant(user_id, object_type, object_id, permission_set, expires_at) values (?,?,?, ?::jsonb, ?)",
          granteeUserId,
          "file",
          fileId,
          "{\"can_view\":true}",
          OffsetDateTime.now().plusDays(1)
      );
      return null;
    });
  }

  private String createPreviewToken(UUID fileId, HttpHeaders headers) {
    ResponseEntity<Map> tokenResp = rest.exchange(
        "/preview/files/" + fileId + "/tokens",
        HttpMethod.POST,
        new HttpEntity<>(headers),
        Map.class
    );
    assertEquals(200, tokenResp.getStatusCode().value());
    return String.valueOf(Objects.requireNonNull(tokenResp.getBody()).get("token"));
  }

  private record Upload(UUID fileId, String s3KeyRaw) {
  }

  private Upload uploadRawPdf(HttpHeaders internalHeaders, UUID caseId, byte[] rawPdf, String filename) throws Exception {
    // upload-init -> presigned PUT
    ResponseEntity<Map> initResp = rest.exchange(
        "/files/upload-init",
        HttpMethod.POST,
        new HttpEntity<>(Map.of(
            "caseId", caseId.toString(),
            "filename", filename,
            "contentType", "application/pdf",
            "sizeBytes", rawPdf.length
        ), json(internalHeaders)),
        Map.class
    );
    assertEquals(200, initResp.getStatusCode().value());
    assertNotNull(initResp.getBody());

    UUID fileId = UUID.fromString(String.valueOf(initResp.getBody().get("fileId")));
    String s3KeyRaw = String.valueOf(initResp.getBody().get("s3KeyRaw"));
    String putUrl = String.valueOf(initResp.getBody().get("presignedPutUrl"));
    assertTrue(putUrl.startsWith("http"));

    httpPut(putUrl, "application/pdf", rawPdf);

    // upload-complete -> file_store + audit + outbox
    Map<String, Object> completeReq = new java.util.HashMap<>();
    completeReq.put("fileId", fileId.toString());
    completeReq.put("caseId", caseId.toString());
    completeReq.put("filename", filename);
    completeReq.put("contentType", "application/pdf");
    completeReq.put("sizeBytes", rawPdf.length);
    completeReq.put("s3KeyRaw", s3KeyRaw);
    ResponseEntity<Map> completeResp = rest.exchange(
        "/files/upload-complete",
        HttpMethod.POST,
        new HttpEntity<>(completeReq, json(internalHeaders)),
        Map.class
    );
    assertEquals(200, completeResp.getStatusCode().value());

    return new Upload(fileId, s3KeyRaw);
  }

  private static byte[] createPdfWithText(String text) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 14);
        cs.newLineAtOffset(72, 720);
        cs.showText(text);
        cs.endText();
      }
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      doc.save(baos);
      return baos.toByteArray();
    }
  }

  private static byte[] createPdfWithEmbeddedFileAndMetadata() throws Exception {
    try (PDDocument doc = new PDDocument()) {
      doc.getDocumentInformation().setTitle("SECRET_TITLE");

      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 14);
        cs.newLineAtOffset(72, 720);
        cs.showText("hello");
        cs.endText();
      }

      // Attach embedded file via Names tree
      PDEmbeddedFile ef = new PDEmbeddedFile(doc, new ByteArrayInputStream("top-secret".getBytes(StandardCharsets.UTF_8)));
      ef.setSubtype("text/plain");

      PDComplexFileSpecification fs = new PDComplexFileSpecification();
      fs.setFile("secret.txt");
      fs.setEmbeddedFile(ef);

      PDEmbeddedFilesNameTreeNode embedded = new PDEmbeddedFilesNameTreeNode();
      embedded.setNames(Map.of("secret.txt", fs));

      PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
      names.setEmbeddedFiles(embedded);
      doc.getDocumentCatalog().setNames(names);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      doc.save(baos);
      return baos.toByteArray();
    }
  }

  private static void httpPut(String url, String contentType, byte[] bytes) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod("PUT");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", contentType);
    conn.getOutputStream().write(bytes);
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      byte[] err = conn.getErrorStream() == null ? new byte[0] : conn.getErrorStream().readAllBytes();
      throw new IllegalStateException("PUT failed: " + code + " " + new String(err, StandardCharsets.UTF_8));
    }
    conn.disconnect();
  }

  private static HttpHeaders bearer(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  private static HttpHeaders json(HttpHeaders headers) {
    HttpHeaders h = new HttpHeaders();
    h.putAll(headers);
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }
}
