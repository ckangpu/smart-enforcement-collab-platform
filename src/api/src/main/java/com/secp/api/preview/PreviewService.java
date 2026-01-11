package com.secp.api.preview;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.idempotency.ResponseJson;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.s3.S3Storage;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.preview.dto.CreatePreviewTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class PreviewService {

  private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
  private static final SecureRandom RNG = new SecureRandom();

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final S3Storage s3;
  private final ResponseJson responseJson;
  private final PreviewProperties props;

  // legacy: keep field to avoid larger refactor; concurrency control is not part of watermark contract.
  private final Semaphore externalSemaphore = null;

  public CreatePreviewTokenResponse createToken(AuthPrincipal viewer, UUID fileId, HttpServletRequest req) {
    return tx.execute(viewer, () -> {
      Map<String, Object> file = findAccessibleFile(fileId);
      UUID groupId = (UUID) file.get("group_id");

      String variant = computeVariant(viewer);

      String token = generateToken();
      String tokenHash = sha256Hex(token);
      OffsetDateTime expiresAt = OffsetDateTime.now().plus(TOKEN_TTL);

      UUID tokenId = UUID.randomUUID();
      jdbc.update(
          """
          insert into file_preview_token(id, file_id, viewer_user_id, token_sha256, expires_at, variant)
          values (?,?,?,?,?,?)
          """,
          tokenId,
          fileId,
          viewer.userId(),
          tokenHash,
          expiresAt,
          variant
      );

      writeAudit(req, viewer.userId(), groupId, "preview_token_create", "file_preview_token", tokenId,
          responseJson.toJson(Map.of(
              "fileId", fileId,
                "expiresAt", expiresAt.toString(),
                "variant", variant
          ))
      );

      return new CreatePreviewTokenResponse(token, expiresAt);
    });
  }

  public byte[] view(AuthPrincipal viewer, String token, HttpServletRequest req) {
    return tx.execute(viewer, () -> {
      String tokenHash = sha256Hex(token);
      String variant = computeVariant(viewer);

      // one-time consume
      var rows = jdbc.queryForList(
          """
          update file_preview_token
             set used_at = now()
           where token_sha256 = ?
             and viewer_user_id = ?
             and variant = ?
             and used_at is null
             and expires_at > now()
          returning id, file_id, variant
          """,
          tokenHash,
          viewer.userId(),
          variant
      );
      if (rows.isEmpty()) {
        throw new PreviewNotFoundException();
      }

      UUID tokenId = (UUID) rows.getFirst().get("id");
      UUID fileId = (UUID) rows.getFirst().get("file_id");

      Map<String, Object> file = findAccessibleFile(fileId);
      UUID groupId = (UUID) file.get("group_id");
      String rawKey = String.valueOf(file.get("s3_key_raw"));

      long rawSizeBytes = 0L;
      Object sizeObj = file.get("size_bytes");
      if (sizeObj instanceof Number n) {
        rawSizeBytes = n.longValue();
      }
      if (rawSizeBytes > 0 && rawSizeBytes > props.maxSizeBytes()) {
        throw new PreviewTooLargeException("FILE_TOO_LARGE");
      }

      int wmVer = props.watermark().wmVer();
      String fileFingerprint = computeFileFingerprint(file);
      String tag = fixedTagForVariant(variant);
      String watermark = buildWatermark(viewer, variant, tag);
      String watermarkTextHash = sha256Hex(watermark);

      String previewS3Key = "preview/" + fileId + "/" + variant + "/" + viewer.userId() + "/wm" + wmVer + ".pdf";

      byte[] pdf;
      boolean cacheHit = false;

      String cachedKey = tryGetCachedPreviewKey(fileId, viewer.userId(), variant, fileFingerprint, wmVer);
      if (cachedKey != null) {
        pdf = s3.getBytes(cachedKey);
        cacheHit = true;
      } else {
        // Ensure object exists and enforce max size by S3 head if available.
        var head = s3.head(rawKey);
        Long headSize = head.contentLength();
        if (headSize != null && headSize > props.maxSizeBytes()) {
          throw new PreviewTooLargeException("FILE_TOO_LARGE");
        }

        byte[] rawPdf = s3.getBytes(rawKey);

        if ("external".equals(variant)) {
          acquireExternalPermitOrThrow();
          try {
            pdf = PdfPreviewRenderer.renderExternalImageBasedPdf(
                rawPdf,
                watermark,
                props.watermark().external(),
                props.watermark().densityMultiplier(),
                props.maxPages()
            );
          } finally {
            releaseExternalPermit();
          }
        } else if ("client".equals(variant)) {
          pdf = PdfPreviewRenderer.renderInternalLikeWatermarkedPdf(
              rawPdf,
              watermark,
              props.watermark().client(),
              props.maxPages()
          );
        } else {
          pdf = PdfPreviewRenderer.renderInternalLikeWatermarkedPdf(
              rawPdf,
              watermark,
              props.watermark().internal(),
              props.maxPages()
          );
        }

        s3.putBytes(previewS3Key, pdf, "application/pdf");
        upsertPreviewIndex(fileId, viewer.userId(), variant, fileFingerprint, wmVer, previewS3Key, pdf.length);
      }

      writeAudit(req, viewer.userId(), groupId, "preview_view", "file_store", fileId,
          responseJson.toJson(Map.of(
              "fileId", fileId,
              "tokenId", tokenId,
              "variant", variant,
            "cacheHit", cacheHit,
            "wmVer", wmVer,
            "watermarkTextHash", watermarkTextHash
          ))
      );

      return pdf;
    });
  }

  private Map<String, Object> findAccessibleFile(UUID fileId) {
    var rows = jdbc.queryForList(
        "select id, group_id, s3_key_raw, size_bytes, sha256, etag, status from file_store where id=? and status='READY'",
        fileId
    );
    if (rows.isEmpty()) {
      throw new PreviewNotFoundException();
    }
    return rows.getFirst();
  }

  private String tryGetCachedPreviewKey(UUID fileId,
                                       UUID viewerUserId,
                                       String variant,
                                       String fileFingerprint,
                                       int wmVer) {
    var rows = jdbc.queryForList(
        """
        select s3_key
          from preview_index
         where file_id = ?
           and viewer_user_id = ?
           and variant = ?
           and file_fingerprint = ?
           and wm_ver = ?
           and expires_at > now()
         order by created_at desc
         limit 1
        """,
        fileId,
        viewerUserId,
        variant,
        fileFingerprint,
        wmVer
    );
    if (rows.isEmpty()) {
      return null;
    }
    return String.valueOf(rows.getFirst().get("s3_key"));
  }

  private void upsertPreviewIndex(UUID fileId,
                                 UUID viewerUserId,
                                 String variant,
                                 String fileFingerprint,
                                 int wmVer,
                                 String s3Key,
                                 long sizeBytes) {
    OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(props.cacheTtlSeconds());
    jdbc.update(
        """
        insert into preview_index(file_id, viewer_user_id, variant, file_fingerprint, wm_ver, s3_key, size_bytes, expires_at)
        values (?,?,?,?,?,?,?,?)
        on conflict (file_id, viewer_user_id, variant, file_fingerprint, wm_ver) do update
          set s3_key = excluded.s3_key,
              size_bytes = excluded.size_bytes,
              expires_at = excluded.expires_at,
              created_at = now()
        """,
        fileId,
        viewerUserId,
        variant,
        fileFingerprint,
        wmVer,
        s3Key,
        sizeBytes,
        expiresAt
    );
  }

  private String buildWatermark(AuthPrincipal viewer, String variant, String tag) {
    String phone = String.valueOf(jdbc.queryForObject(
        "select phone from app_user where id=?",
        String.class,
        viewer.userId()
    ));

    String last4 = phone == null ? "" : phone.replaceAll("\\D", "");
    if (last4.length() >= 4) {
      last4 = last4.substring(last4.length() - 4);
    }

    ZoneId zone = ZoneId.of("Asia/Shanghai");
    String ts = ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern(props.watermark().timeFormat()));
    String template = switch (variant) {
      case "external" -> props.watermark().external().template();
      case "client" -> props.watermark().client().template();
      default -> props.watermark().internal().template();
    };
    return template
        .replace("{tag}", tag)
        .replace("{username}", viewer.username())
        .replace("{phone_last4}", last4)
        .replace("{timestamp}", ts);
  }

  private static String fixedTagForVariant(String variant) {
    return switch (variant) {
      case "external" -> "EXTERNAL";
      case "client" -> "CLIENT";
      default -> "INTERNAL";
    };
  }

  private static String computeVariant(AuthPrincipal viewer) {
    if ("external".equals(viewer.userType())) {
      return "external";
    }
    if ("client".equals(viewer.userType())) {
      return "client";
    }
    return "internal";
  }

  private static String computeFileFingerprint(Map<String, Object> file) {
    Object sha = file.get("sha256");
    if (sha != null) {
      return String.valueOf(sha);
    }
    Object etag = file.get("etag");
    if (etag != null) {
      return String.valueOf(etag);
    }
    Object size = file.get("size_bytes");
    Object rawKey = file.get("s3_key_raw");
    String fp = String.valueOf(size) + ":" + String.valueOf(rawKey);
    return fp.length() <= 128 ? fp : fp.substring(0, 128);
  }

  private void acquireExternalPermitOrThrow() {
    if (externalSemaphore == null) {
      return;
    }
    try {
      boolean ok = externalSemaphore.tryAcquire(30, TimeUnit.SECONDS);
      if (!ok) {
        throw new PreviewRateLimitedException();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PreviewRateLimitedException();
    }
  }

  private void releaseExternalPermit() {
    if (externalSemaphore != null) {
      externalSemaphore.release();
    }
  }

  private void writeAudit(HttpServletRequest req,
                          UUID actorUserId,
                          UUID groupId,
                          String action,
                          String objectType,
                          UUID objectId,
                          String summaryJson) {
    String rid = (String) req.getAttribute(RequestIdFilter.REQ_ID_ATTR);
    jdbc.update(
        """
        insert into audit_log(group_id, actor_user_id, action, object_type, object_id, request_id, ip, user_agent, summary)
        values (?,?,?,?,?,?,?,?, ?::jsonb)
        """,
        groupId,
        actorUserId,
        action,
        objectType,
        objectId,
        rid,
        req.getRemoteAddr(),
        req.getHeader("User-Agent"),
        summaryJson
    );
  }

  private static String generateToken() {
    byte[] bytes = new byte[32];
    RNG.nextBytes(bytes);
    // url-safe base64 without padding
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256Hex(String token) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
