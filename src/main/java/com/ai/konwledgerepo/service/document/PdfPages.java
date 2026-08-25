package com.ai.konwledgerepo.service.document;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * PDF 渲染与图片检测的工具方法（PDDocument 非线程安全，渲染必须在持有文档的线程执行）。
 * 供 {@link PdfParseService}（按需识图）与 {@link VisionPageFiller}（缺页补全）共用。
 */
final class PdfPages {

    private PdfPages() {
    }

    /** 渲染指定页（1-based）为 PNG；失败返回 null（调用方回退纯文本） */
    static byte[] renderPng(PDFRenderer renderer, int page, int dpi) {
        try {
            BufferedImage image = renderer.renderImageWithDPI(page - 1, dpi);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 检测页面资源中是否包含栅格图片；异常时视为无图（识图按 min-text 兜底） */
    static boolean hasEmbeddedImages(PDDocument document, int page) {
        try {
            return containsImage(document.getPage(page - 1).getResources(), 0);
        } catch (Exception e) {
            return false;
        }
    }

    /** 递归检查资源中的图片 XObject（Form XObject 最多向下两层，防循环） */
    private static boolean containsImage(PDResources resources, int depth) {
        if (resources == null || depth > 1) {
            return false;
        }
        try {
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobj = resources.getXObject(name);
                if (xobj instanceof PDImageXObject) {
                    return true;
                }
                if (xobj instanceof PDFormXObject form && containsImage(form.getResources(), depth + 1)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
