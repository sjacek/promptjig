package example;

import example.certificate.CertificateExtractionInput;
import example.certificate.CertificateExtractionOutput;
import example.certificate.CertificateExtractionPromptContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GenerationTest {
    @interface AuditVersion { String value(); }
    @AuditVersion(CertificateExtractionPromptContract.VERSION_REF)
    static class AuditedOperation { }

    @Test
    void readsTypedOutputAndExposesCompileTimeVersionMetadata() throws Exception {
        var mapper = JsonMapper.builder().build();
        var output = mapper.readValue("{\"certificateType\":\"PZON_CHILD\",\"issuedAt\":\"2026-11-11\",\"symbolCodes\":[\"05-R\"]}",
                CertificateExtractionOutput.class);
        assertEquals(CertificateExtractionOutput.CertificateType.PZON_CHILD, output.certificateType());
        assertEquals(java.time.LocalDate.of(2026, 11, 11), output.issuedAt());
        assertEquals("05-R", output.symbolCodes().get(0).value());
        assertTrue(output.indicationCodes().isEmpty());
        assertEquals("synthetic OCR", new CertificateExtractionInput("synthetic OCR").ocrText());
        assertTrue(CertificateExtractionPromptContract.FINGERPRINT.startsWith("sha256:"));
        assertThrows(Exception.class, () -> mapper.readValue("{\"certificateType\":\"unknown\"}", CertificateExtractionOutput.class));
    }
}
