package com.secp.api.infra.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;

@Component
public class S3Storage {

  private final S3Client s3;
  private final S3Presigner presigner;

  @Value("${secp.s3.bucket}")
  private String bucket;

  public S3Storage(S3Client s3, S3Presigner presigner) {
    this.s3 = s3;
    this.presigner = presigner;
  }

  public record PresignedPut(String url, Duration expiresIn) {
  }

  public PresignedPut presignPut(String key, String contentType, Duration ttl) {
    var putReq = software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .contentType(contentType)
        .build();

    PresignedPutObjectRequest presigned = presigner.presignPutObject(
        PutObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .putObjectRequest(putReq)
            .build()
    );

    return new PresignedPut(presigned.url().toString(), ttl);
  }

  public HeadObjectResponse head(String key) {
    return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
  }

  public byte[] getBytes(String key) {
    try (ResponseInputStream<?> in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
      return readAllBytes(in);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void putBytes(String key, byte[] bytes, String contentType) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(bytes)
    );
  }

  private static byte[] readAllBytes(ResponseInputStream<?> in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) >= 0) {
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }
}
