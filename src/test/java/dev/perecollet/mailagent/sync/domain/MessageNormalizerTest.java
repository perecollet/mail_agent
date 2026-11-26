package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MessageNormalizerTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-03T17:02:00Z");
    private static final Instant SENT = Instant.parse("2026-11-03T17:01:30Z");

    /** Stands in for the Jsoup adapter: tags become nothing, and quotes are marked with [quote]...[/quote]. */
    private static final HtmlTextConverter FAKE_HTML = new HtmlTextConverter() {
        @Override
        public String toText(String html) {
            return html.replaceAll("\\[/?quote]", "").replaceAll("<[^>]+>", "").strip();
        }

        @Override
        public String toTextWithoutQuotes(String html) {
            return html.replaceAll("(?s)\\[quote].*?\\[/quote]", "").replaceAll("<[^>]+>", "").strip();
        }
    };

    private final MessageNormalizer normalizer = new MessageNormalizer(
            new OwnAddresses(List.of("marc@example.com", "marc.alias@example.net")), FAKE_HTML, 100_000);

    private static Builder message() {
        return new Builder();
    }

    private EmailRecord normalize(Builder builder) {
        return normalizer.normalize(builder.build(), "gmail", 7L, 300L);
    }

    @Nested
    class Direction_ {

        @Test
        void isInboundWhenSomeoneElseSentIt() {
            assertThat(normalize(message().from("laura@example.com")).direction()).isEqualTo(Direction.INBOUND);
        }

        @Test
        void isOutboundWhenTheUserSentItWhateverTheCaseOrAlias() {
            assertThat(normalize(message().from(" Marc.Alias@Example.NET ")).direction()).isEqualTo(Direction.OUTBOUND);
        }

        @Test
        void isInboundWithoutSender() {
            assertThat(normalize(message().from(null)).direction()).isEqualTo(Direction.INBOUND);
        }
    }

    @Nested
    class Identity {

        @Test
        void carriesTheNaturalKeyAndServerFacts() {
            EmailRecord email = normalize(message().uid(42L).seen(true).important(true).listUnsubscribe(true));

            assertThat(email.accountId()).isEqualTo("gmail");
            assertThat(email.folderId()).isEqualTo(7L);
            assertThat(email.uidValidity()).isEqualTo(300L);
            assertThat(email.uid()).isEqualTo(42L);
            assertThat(email.receivedAt()).isEqualTo(RECEIVED);
            assertThat(email.sentAt()).isEqualTo(SENT);
            assertThat(email.seen()).isTrue();
            assertThat(email.providerImportant()).isTrue();
            assertThat(email.hasListUnsubscribe()).isTrue();
        }

        @Test
        void normalizesThreadHeaders() {
            EmailRecord email = normalize(message()
                    .messageId(" <ABC@mail.example.com> ")
                    .inReplyTo("<Parent@mail.example.com> (Laura's message)")
                    .references("<root@x> <Parent@mail.example.com>"));

            assertThat(email.messageId()).isEqualTo("abc@mail.example.com");
            assertThat(email.inReplyTo()).isEqualTo("parent@mail.example.com");
            assertThat(email.referencesIds()).containsExactly("root@x", "parent@mail.example.com");
        }

        @Test
        void normalizesTheSubjectButKeepsTheOriginal() {
            EmailRecord email = normalize(message().subject("RE: [team] Fwd: Offer"));

            assertThat(email.subject()).isEqualTo("RE: [team] Fwd: Offer");
            assertThat(email.subjectNormalized()).isEqualTo("Offer");
        }

        @Test
        void storesABlankSubjectAsMissing() {
            EmailRecord email = normalize(message().subject("   "));

            assertThat(email.subject()).isNull();
            assertThat(email.subjectNormalized()).isNull();
        }

        @Test
        void lowerCasesAndDeduplicatesAddresses() {
            EmailRecord email = normalize(message()
                    .from("Laura@Example.COM")
                    .fromName("  Laura Vidal ")
                    .to(List.of("Marc@example.com", "marc@example.com ", " ", "pau@example.com"))
                    .cc(List.of("Carla@Example.com")));

            assertThat(email.fromAddress()).isEqualTo("laura@example.com");
            assertThat(email.fromName()).isEqualTo("Laura Vidal");
            assertThat(email.toAddresses()).containsExactly("marc@example.com", "pau@example.com");
            assertThat(email.ccAddresses()).containsExactly("carla@example.com");
        }
    }

    @Nested
    class Bodies {

        @Test
        void stripsTheQuotedHistoryOfAPlainTextReply() {
            String body = """
                    Yes, Thursday works.

                    On Mon, 3 Nov 2026 at 09:14, Laura <laura@example.com> wrote:
                    > Can we meet on Thursday?
                    """;

            EmailRecord email = normalize(message().subject("Re: Meeting").plain(body));

            assertThat(email.bodyText()).isEqualTo(body.strip());
            assertThat(email.bodyStripped()).isEqualTo("Yes, Thursday works.");
            assertThat(email.bodyTruncated()).isFalse();
        }

        @Test
        void neverCutsAForward() {
            String body = """
                    FYI

                    On Mon, 3 Nov 2026 at 09:14, Laura <laura@example.com> wrote:
                    > The offer is attached.
                    """;

            EmailRecord email = normalize(message().subject("Fwd: Offer").plain(body));

            assertThat(email.bodyStripped()).isEqualTo(body.strip());
        }

        @Test
        void normalizesLineEndings() {
            assertThat(normalize(message().plain("Line one\r\nLine two\r\n")).bodyText()).isEqualTo("Line one\nLine two");
        }

        @Test
        void convertsAnHtmlOnlyBodyAndDropsQuoteContainers() {
            EmailRecord email = normalize(message().plain(null)
                    .html("<p>Agreed.</p>[quote]<p>Shall we go ahead?</p>[/quote]"));

            assertThat(email.bodyText()).isEqualTo("Agreed.Shall we go ahead?");
            assertThat(email.bodyStripped()).isEqualTo("Agreed.");
        }

        @Test
        void normalizesLineEndingsOfConvertedHtml() {
            assertThat(normalize(message().plain(null).html("<p>One</p>\r\n<p>Two</p>")).bodyText())
                    .isEqualTo("One\nTwo");
        }

        @Test
        void keepsQuoteContainersInAnHtmlForward() {
            EmailRecord email = normalize(message().subject("RV: Oferta").plain(" ")
                    .html("<p>Mira esto</p>[quote]<p>La oferta</p>[/quote]"));

            assertThat(email.bodyStripped()).isEqualTo("Mira estoLa oferta");
        }

        @Test
        void fallsBackToTheFullTextWhenTheHtmlIsOnlyQuotes() {
            EmailRecord email = normalize(message().plain(null).html("[quote]<p>Only quoted</p>[/quote]"));

            assertThat(email.bodyStripped()).isEqualTo("Only quoted");
        }

        @Test
        void hasNoBodyWhenTheMessageHasNone() {
            EmailRecord email = normalize(message().plain(null).html(null));

            assertThat(email.bodyText()).isNull();
            assertThat(email.bodyStripped()).isNull();
        }

        @Test
        void truncatesLongBodiesWithoutSplittingASurrogatePair() {
            MessageNormalizer small = new MessageNormalizer(new OwnAddresses(List.of()), FAKE_HTML, 5);

            EmailRecord email = small.normalize(message().plain("abcd\uD83D\uDE00 tail").build(), "gmail", 7L, 300L);

            assertThat(email.bodyText()).isEqualTo("abcd");
            assertThat(email.bodyTruncated()).isTrue();
        }
    }

    @Nested
    class PostgresSafety {

        @Test
        void removesNulCharactersEverywhere() {
            FetchedAttachment pdf = FetchedAttachment.withText("2", "in\u0000voice.pdf", "application/pdf", 10L, false,
                    "To\u0000tal");

            EmailRecord email = normalize(message()
                    .subject("Hel\u0000lo")
                    .fromName("La\u0000ura")
                    .plain("Bo\u0000dy")
                    .attachments(List.of(pdf)));

            assertThat(email.subject()).isEqualTo("Hello");
            assertThat(email.fromName()).isEqualTo("Laura");
            assertThat(email.bodyText()).isEqualTo("Body");
            assertThat(email.attachments().getFirst().fileName()).isEqualTo("invoice.pdf");
            assertThat(email.attachmentText()).doesNotContain("\u0000");
        }
    }

    @Nested
    class Attachments {

        @Test
        void aggregatesFileNamesSplitOnSeparatorsAndExtractedText() {
            FetchedAttachment invoice = FetchedAttachment.withText(
                    "2", "Factura_agosto-2026.pdf", "application/pdf", 10L, false, "Importe total 42,10 EUR");
            FetchedAttachment photo = FetchedAttachment.withoutText(
                    "3", "IMG_0042.jpg", "image/jpeg", 10L, false, ExtractionStatus.SKIPPED);

            EmailRecord email = normalize(message().attachments(List.of(invoice, photo)));

            assertThat(email.attachmentText())
                    .isEqualTo("Factura agosto 2026 pdf\nImporte total 42,10 EUR\nIMG 0042 jpg");
            assertThat(email.hasAttachments()).isTrue();
        }

        @Test
        void hasNoAttachmentTextWithoutAttachments() {
            assertThat(normalize(message()).attachmentText()).isNull();
        }
    }

    @Test
    void rejectsANonPositiveMaxBodyChars() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MessageNormalizer(new OwnAddresses(List.of()), FAKE_HTML, 0));
    }

    /** Anonymized message with sensible defaults; each test overrides what it checks. */
    private static final class Builder {
        private long uid = 1L;
        private String messageId = "<id@example.com>";
        private String inReplyTo;
        private String references;
        private String from = "laura@example.com";
        private String fromName = "Laura";
        private List<String> to = List.of("marc@example.com");
        private List<String> cc = List.of();
        private String subject = "Meeting";
        private boolean seen;
        private boolean important;
        private boolean listUnsubscribe;
        private String plain = "Hello";
        private String html;
        private List<FetchedAttachment> attachments = List.of();

        Builder uid(long value) { uid = value; return this; }
        Builder messageId(String value) { messageId = value; return this; }
        Builder inReplyTo(String value) { inReplyTo = value; return this; }
        Builder references(String value) { references = value; return this; }
        Builder from(String value) { from = value; return this; }
        Builder fromName(String value) { fromName = value; return this; }
        Builder to(List<String> value) { to = value; return this; }
        Builder cc(List<String> value) { cc = value; return this; }
        Builder subject(String value) { subject = value; return this; }
        Builder seen(boolean value) { seen = value; return this; }
        Builder important(boolean value) { important = value; return this; }
        Builder listUnsubscribe(boolean value) { listUnsubscribe = value; return this; }
        Builder plain(String value) { plain = value; return this; }
        Builder html(String value) { html = value; return this; }
        Builder attachments(List<FetchedAttachment> value) { attachments = value; return this; }

        FetchedMessage build() {
            return new FetchedMessage(uid, RECEIVED, SENT, messageId, inReplyTo, references, from, fromName, to, cc,
                    subject, seen, important, listUnsubscribe, plain, html, attachments);
        }
    }
}
