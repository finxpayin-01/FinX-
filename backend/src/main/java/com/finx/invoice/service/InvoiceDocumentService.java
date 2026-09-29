package com.finx.invoice.service;

import com.finx.exception.BadRequestException;
import com.finx.invoice.dto.InvoiceUploadRequest;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Validates the actual bytes, never remote URLs, client hashes or claimed sizes. */
@Service
public class InvoiceDocumentService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public record Document(byte[] bytes, String hash, String mimeType) {}
    public Document validate(InvoiceUploadRequest request) {
        String encoded = request.getFileContentBase64();
        if (encoded == null || encoded.isBlank() || encoded.length() > (MAX_BYTES + 2L) / 3 * 4)
            throw new BadRequestException("Invoice document content is required, maximum 10 MB");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid invoice document encoding"); }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new BadRequestException("Invoice file size must be between 1 byte and 10 MB");
        String name = request.getFileName();
        if (name == null || name.length() > 255 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl))
            throw new BadRequestException("Invalid invoice file name");
        name = name.toLowerCase(Locale.ROOT);
        String mime;
        if (starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII)) && name.endsWith(".pdf")
                && new String(bytes, Math.max(0, bytes.length - 1024), Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1).contains("%%EOF")) mime = "application/pdf";
        else if (starts(bytes, new byte[]{(byte)137,80,78,71,13,10,26,10}) && name.endsWith(".png")) mime = "image/png";
        else if (starts(bytes, new byte[]{(byte)255,(byte)216,(byte)255}) && (name.endsWith(".jpg") || name.endsWith(".jpeg"))) mime = "image/jpeg";
        else throw new BadRequestException("Invoice bytes and extension must identify a PDF, PNG or JPEG document");
        if (!mime.equalsIgnoreCase(request.getMimeType())) throw new BadRequestException("Invoice MIME type does not match document content");
        return new Document(bytes, sha256(bytes), mime);
    }
    private boolean starts(byte[] bytes, byte[] signature) {
        return bytes.length >= signature.length && Arrays.equals(Arrays.copyOf(bytes, signature.length), signature);
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
