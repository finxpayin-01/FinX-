package com.finx.invoice;
import com.finx.invoice.dto.InvoiceUploadRequest;
import com.finx.invoice.service.InvoiceDocumentService;
import com.finx.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceDocumentServiceTest {
    private final InvoiceDocumentService service=new InvoiceDocumentService();
    private InvoiceUploadRequest request(String name,String mime,byte[] bytes) {
        var r=new InvoiceUploadRequest();r.setFileName(name);r.setMimeType(mime);r.setFileContentBase64(Base64.getEncoder().encodeToString(bytes));return r;
    }
    @Test void detectsBytesRatherThanClaimedMimeOrSize() {
        var r=request("invoice.pdf","application/pdf","%PDF-1.4\nFixture\n%%EOF".getBytes()); r.setFileSizeBytes(0L);
        assertEquals("application/pdf",service.validate(r).mimeType());
        r.setMimeType("image/jpeg"); assertThrows(BadRequestException.class,()->service.validate(r));
        r.setMimeType("application/pdf");r.setFileName("invoice.jpg");assertThrows(BadRequestException.class,()->service.validate(r));
    }
    @Test void rejectsEmptyInvalidOversizedAndPathNames() {
        assertThrows(BadRequestException.class,()->service.validate(request("invoice.pdf","application/pdf",new byte[0])));
        assertThrows(BadRequestException.class,()->service.validate(request("invoice.pdf","application/pdf",new byte[InvoiceDocumentService.MAX_BYTES+1])));
        assertThrows(BadRequestException.class,()->service.validate(request("../invoice.pdf","application/pdf","%PDF-1.4\n%%EOF".getBytes())));
        var r=request("invoice.pdf","application/pdf","html".getBytes()); assertThrows(BadRequestException.class,()->service.validate(r));
        r.setFileContentBase64("bad!base64");assertThrows(BadRequestException.class,()->service.validate(r));
    }
    @Test void acceptsPngAndJpegSignaturesAndHashesIndependentlyOfName() {
        var png=request("invoice.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10,0});
        var first=service.validate(png);png.setFileName("renamed.png");assertEquals(first.hash(),service.validate(png).hash());
        assertEquals("image/png",first.mimeType());
        assertEquals("image/jpeg",service.validate(request("invoice.jpeg","image/jpeg",new byte[]{(byte)255,(byte)216,(byte)255,0})).mimeType());
    }
}
