package com.secp.api.preview;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class PdfPreviewRenderer {

  private PdfPreviewRenderer() {
  }

  public static byte[] renderExternalImageBasedPdf(byte[] rawPdf,
                                                   String watermarkText,
                                                   PreviewProperties.Variant cfg,
                                                   double densityMultiplier,
                                                   int maxPages) {
    try (PDDocument src = PDDocument.load(new ByteArrayInputStream(rawPdf), MemoryUsageSetting.setupTempFileOnly());
         PDDocument out = new PDDocument()) {

      if (src.getNumberOfPages() > maxPages) {
        throw new PreviewTooLargeException("TOO_MANY_PAGES");
      }

      PDFRenderer renderer = new PDFRenderer(src);
      for (int i = 0; i < src.getNumberOfPages(); i++) {
        PDPage srcPage = src.getPage(i);
        PDRectangle box = srcPage.getMediaBox();

        // 170dpi (within 150-200)
        BufferedImage img = renderer.renderImageWithDPI(i, 170f, ImageType.RGB);
        applyFullScreenWatermark(img, watermarkText, cfg, densityMultiplier);

        PDPage page = new PDPage(box);
        out.addPage(page);

        var pdImage = LosslessFactory.createFromImage(out, img);
        try (PDPageContentStream cs = new PDPageContentStream(out, page, PDPageContentStream.AppendMode.OVERWRITE, false, true)) {
          cs.drawImage(pdImage, 0, 0, box.getWidth(), box.getHeight());
        }
      }

      sanitize(out);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      out.save(baos);
      return baos.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] renderInternalLikeWatermarkedPdf(byte[] rawPdf,
                                                        String watermarkText,
                                                        PreviewProperties.Variant cfg,
                                                        int maxPages) {
    try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(rawPdf), MemoryUsageSetting.setupTempFileOnly())) {
      if (doc.getNumberOfPages() > maxPages) {
        throw new PreviewTooLargeException("TOO_MANY_PAGES");
      }

      for (PDPage page : doc.getPages()) {
        PDRectangle box = page.getMediaBox();

        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setNonStrokingAlphaConstant((float) cfg.opacity());

        try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
          cs.setGraphicsStateParameters(gs);
          cs.beginText();
          cs.setFont(PDType1Font.HELVETICA, cfg.fontSize());
          cs.setNonStrokingColor(150);

          // Diagonal single watermark across the page (light)
          AffineTransform at = new AffineTransform();
          at.translate(box.getWidth() * 0.15, box.getHeight() * 0.25);
          at.rotate(Math.toRadians(cfg.angle()));
          cs.setTextMatrix(at);
          cs.showText(watermarkText);
          cs.endText();
        }
      }

      sanitize(doc);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      doc.save(baos);
      return baos.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void applyFullScreenWatermark(BufferedImage image,
                                               String text,
                                               PreviewProperties.Variant cfg,
                                               double densityMultiplier) {
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) cfg.opacity()));
      g.setColor(new Color(0, 0, 0));

      int base = Math.max(12, cfg.fontSize());
      g.setFont(new Font("SansSerif", Font.BOLD, base));

      double theta = Math.toRadians(cfg.angle());
      g.rotate(theta, image.getWidth() / 2.0, image.getHeight() / 2.0);

      FontMetrics fm = g.getFontMetrics();
      int textW = fm.stringWidth(text);
      int textH = fm.getHeight();
      double density = Math.max(1.0, densityMultiplier);
      int stepX = (int) Math.max(base * 2, textW + base * density);
      int stepY = (int) Math.max(base * 2, textH + base * (density * 0.75));

      for (int y = -image.getHeight(); y < image.getHeight() * 2; y += stepY) {
        for (int x = -image.getWidth(); x < image.getWidth() * 2; x += stepX) {
          g.drawString(text, x, y);
        }
      }
    } finally {
      g.dispose();
    }
  }

  private static void sanitize(PDDocument doc) {
    doc.setDocumentInformation(new PDDocumentInformation());

    PDDocumentCatalog catalog = doc.getDocumentCatalog();
    if (catalog != null) {
      catalog.setMetadata(null);

      // Remove embedded files from Names dictionary
      try {
        PDDocumentNameDictionary names = new PDDocumentNameDictionary(catalog);
        if (names.getEmbeddedFiles() != null) {
          names.setEmbeddedFiles(null);
          catalog.setNames(names);
        }
      } catch (Exception ignored) {
        // best-effort
      }

      // Remove AF (associated files) if present
      try {
        COSDictionary cos = catalog.getCOSObject();
        cos.removeItem(COSName.AF);
      } catch (Exception ignored) {
        // best-effort
      }
    }
  }
}
