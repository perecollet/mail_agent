package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MessageIdsTest {

    private static String ids(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> "<id" + i + "@x>").collect(Collectors.joining(" "));
    }

    @Nested
    class Normalize {

        static Stream<Arguments> headers() {
            return Stream.of(
                    arguments("<abc@example.com>", "abc@example.com"),
                    arguments("  <ABC.123@Mail.Example.COM>  \t", "abc.123@mail.example.com"),
                    arguments("abc@example.com", "abc@example.com"),
                    arguments("  abc@example.com\r\n", "abc@example.com"),
                    arguments("<abc@example.com> (Ana's message of Monday)", "abc@example.com"),
                    arguments("abc@example.com (reply to Ana)", "abc@example.com"),
                    arguments("(see <other@example.com>) <abc@example.com>", "abc@example.com"),
                    arguments("<first@example.com> <second@example.com>", "first@example.com"),
                    arguments("<abc@exa\r\n mple.com>", "abc@example.com"),
                    arguments("<abc@exa\n\tmple.com>", "abc@example.com"),
                    arguments("<legacy-id-without-at>", "legacy-id-without-at"));
        }

        @ParameterizedTest
        @MethodSource("headers")
        void extractsTheFirstIdWithoutBracketsOrWhitespaceInLowerCase(String header, String expected) {
            assertThat(MessageIds.normalize(header)).isEqualTo(expected);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "<>", "< >", "<<>>", "N/A", "none", "(comment only)"})
        void returnsNullWhenThereIsNoId(String header) {
            assertThat(MessageIds.normalize(header)).isNull();
        }
    }

    @Nested
    class ParseReferences {

        @Test
        void keepsTheHeaderOrder() {
            assertThat(MessageIds.parseReferences("<a@x> <b@x>\r\n <c@x>")).containsExactly("a@x", "b@x", "c@x");
        }

        @Test
        void keepsTheLastOccurrenceOfARepeatedId() {
            assertThat(MessageIds.parseReferences("<a@x> <B@x> <A@X> <c@x>")).containsExactly("b@x", "a@x", "c@x");
        }

        @Test
        void acceptsMixedBracketedAndBareIds() {
            assertThat(MessageIds.parseReferences("a@x <b@x>, c@x")).containsExactly("a@x", "b@x", "c@x");
        }

        @Test
        void ignoresComments() {
            assertThat(MessageIds.parseReferences("<a@x> (see <z@y>) b@x (reply to Ana)")).containsExactly("a@x", "b@x");
        }

        @Test
        void recoversIdsAroundAnUnterminatedBracket() {
            assertThat(MessageIds.parseReferences("<a@x> <b@x")).containsExactly("a@x", "b@x");
        }

        @Test
        void keepsExactlyFifty() {
            assertThat(MessageIds.parseReferences(ids(1, 50))).hasSize(50).first().isEqualTo("id1@x");
        }

        @Test
        void dropsTheOldestBeyondFifty() {
            List<String> references = MessageIds.parseReferences(ids(1, 51));

            assertThat(references).hasSize(50);
            assertThat(references.getFirst()).isEqualTo("id2@x");
            assertThat(references.getLast()).isEqualTo("id51@x");
        }

        @Test
        void anOldIdRepeatedAtTheEndSurvivesTheCut() {
            List<String> references = MessageIds.parseReferences(ids(1, 60) + " <id1@x>");

            assertThat(references).hasSize(50).contains("id1@x");
            assertThat(references.getLast()).isEqualTo("id1@x");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "<> < >", "<<>>", "(nothing here)"})
        void isEmptyWhenThereAreNoIds(String header) {
            assertThat(MessageIds.parseReferences(header)).isEmpty();
        }

        @Test
        void returnsAnUnmodifiableList() {
            assertThat(MessageIds.parseReferences("<a@x>")).isUnmodifiable();
        }
    }
}
