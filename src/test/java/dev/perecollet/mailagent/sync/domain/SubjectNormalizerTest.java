package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SubjectNormalizerTest {

    @Nested
    class Normalize {

        @ParameterizedTest(name = "[{index}] {0}")
        @CsvSource(delimiter = '|', value = {
                // English
                "Re: Invoice August            | Invoice August",
                "RE: Invoice August            | Invoice August",
                "Fwd: Invoice August           | Invoice August",
                "FW: Invoice August            | Invoice August",
                // Spanish and Catalan (Outlook uses RV for forwards)
                "RV: Factura agosto            | Factura agosto",
                "Reenv: Factura agosto         | Factura agosto",
                // French
                "TR: Facture août              | Facture août",
                "Réponse: Facture août         | Facture août",
                "Transfert: Facture août       | Facture août",
                // German
                "AW: Rechnung August           | Rechnung August",
                "WG: Rechnung August           | Rechnung August",
                "Antw: Rechnung August         | Rechnung August",
                "Antwort: Rechnung August      | Rechnung August",
                "Weitergeleitet: Rechnung      | Rechnung",
                // Full words in Spanish, Catalan, Portuguese and Italian
                "Respuesta: Factura            | Factura",
                "Reenviado: Factura            | Factura",
                "Reenviat: Factura             | Factura",
                "Encaminhado: Fatura           | Fatura",
                "Risposta: Fattura             | Fattura",
                // Portuguese
                "RES: Fatura agosto            | Fatura agosto",
                "ENC: Fatura agosto            | Fatura agosto",
                // Italian
                "R: Fattura agosto             | Fattura agosto",
                "RIF: Fattura agosto           | Fattura agosto",
                "I: Fattura agosto             | Fattura agosto",
                // Stacked, numbered, odd spacing
                "Re: RE: Fwd: Re: Offer        | Offer",
                "Re[2]: Offer                  | Offer",
                "Re(3): Offer                  | Offer",
                "Re^2: Offer                   | Offer",
                "Re : Offer                    | Offer",
                "Re：Offer                      | Offer",
                "re:Offer                      | Offer",
                // Tags before and between prefixes
                "[EXTERNAL] RE: [team] Fwd: Offer | Offer",
                "[ticket #42] Offer            | Offer",
                // Whitespace is collapsed
                "Re:   Offer \t for   you      | Offer for you",
        })
        void stripsPrefixesAndLeadingTags(String subject, String expected) {
            assertThat(SubjectNormalizer.normalize(subject)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "Meeting: agenda for Monday",
                "Update: new prices",
                "Offer [draft] for review",
                "Reunión mañana",
                "Re-launch plan",
                "Invoice Re: August",
                "Réf: 12345 Contrat",
                "i: lower-case single letters are text",
                "r: lower-case single letters are text",
        })
        void keepsSubjectsWithoutReplyOrForwardPrefixes(String subject) {
            assertThat(SubjectNormalizer.normalize(subject)).isEqualTo(subject);
        }

        @Test
        void trimsSurroundingWhitespace() {
            assertThat(SubjectNormalizer.normalize("  Re: Offer \t")).isEqualTo("Offer");
        }

        @Test
        void handlesNonBreakingSpacesAroundTheColon() {
            assertThat(SubjectNormalizer.normalize("RE\u00A0: Facture\u00A0août")).isEqualTo("Facture août");
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   ", "Re:", "Fwd: RE:", "[tag]"})
        void isNullWhenNothingIsLeft(String subject) {
            assertThat(SubjectNormalizer.normalize(subject)).isNull();
        }
    }

    @Nested
    class IsForward {

        @ParameterizedTest(name = "[{index}] {0}")
        @CsvSource(delimiter = '|', value = {
                "Fwd: Offer               | true",
                "FW: Offer                | true",
                "RV: Oferta               | true",
                "WG: Angebot              | true",
                "TR: Offre                | true",
                "ENC: Oferta              | true",
                "[EXTERNAL] Fwd: Offer    | true",
                "Fwd: Re: Offer           | true",
                "Fwd[2]: Offer            | true",
                "Fwd(2): Offer            | true",
                "I: Offerta               | true",
                "Reenviado: Oferta        | true",
                "Re: Fwd: Offer           | false",
                "Re: Offer                | false",
                "Offer                    | false",
                "Forward planning         | false",
        })
        void looksAtTheOutermostPrefix(String subject, boolean expected) {
            assertThat(SubjectNormalizer.isForward(subject)).isEqualTo(expected);
        }

        @Test
        void recognizesAFrenchForwardWithANonBreakingSpace() {
            assertThat(SubjectNormalizer.isForward("TR\u00A0: Offre")).isTrue();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  "})
        void isFalseWithoutSubject(String subject) {
            assertThat(SubjectNormalizer.isForward(subject)).isFalse();
        }
    }
}
