package com.secp.api.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "secp.preview")
public record PreviewProperties(
    long maxSizeBytes,
    int maxPages,
    long cacheTtlSeconds,
    Watermark watermark
) {
  public record Watermark(
      int wmVer,
      String timeFormat,
      double densityMultiplier,
      Variant external,
      Variant client,
      Variant internal
  ) {
  }

  public record Variant(
      double opacity,
      int fontSize,
      double angle,
      String template
  ) {
  }
}
