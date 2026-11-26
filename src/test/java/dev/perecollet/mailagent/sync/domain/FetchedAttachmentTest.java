package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class FetchedAttachmentTest {

    @Test
    void keepsTheTextOfASuccessfulExtraction() {
        FetchedAttachment pdf = FetchedAttachment.withText("2", "invoice.pdf", "application/pdf", 1024L, false, "Total 42");

        assertThat(pdf.extractionStatus()).isEqualTo(ExtractionStatus.DONE);
        assertThat(pdf.extractedText()).isEqualTo("Total 42");
    }

    @Test
    void hasNoTextWhenExtractionWasSkipped() {
        FetchedAttachment image = FetchedAttachment.withoutText("3", "photo.jpg", "image/jpeg", null, false,
                ExtractionStatus.SKIPPED);

        assertThat(image.extractedText()).isNull();
    }

    @Test
    void rejectsDoneWithoutText() {
        assertThatIllegalArgumentException().isThrownBy(() -> FetchedAttachment.withoutText(
                "2", "invoice.pdf", "application/pdf", 1L, false, ExtractionStatus.DONE));
    }

    @Test
    void rejectsTextForAFailedExtraction() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FetchedAttachment(
                "2", "invoice.pdf", "application/pdf", 1L, false, ExtractionStatus.FAILED, "partial"));
    }

    @Test
    void rejectsANegativeSize() {
        assertThatIllegalArgumentException().isThrownBy(() -> FetchedAttachment.withoutText(
                "2", "a.bin", "application/octet-stream", -1L, false, ExtractionStatus.SKIPPED));
    }

    @Test
    void rejectsABlankPartPath() {
        assertThatIllegalArgumentException().isThrownBy(() -> FetchedAttachment.withoutText(
                " ", "a.bin", "application/octet-stream", 1L, false, ExtractionStatus.SKIPPED));
    }

    @Test
    void doesNotPrintTheFileNameOrText() {
        FetchedAttachment pdf = FetchedAttachment.withText("2", "payslip.pdf", "application/pdf", 1L, false, "Salary");

        assertThat(pdf.toString()).doesNotContain("payslip").doesNotContain("Salary");
    }
}
