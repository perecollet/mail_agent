package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ReplyStripperTest {

    private static final String EXPECTED_MARKER = "\n===== expected =====\n";

    /** Anonymized real-world bodies: the input, a marker line, then the expected stripped text. */
    static Stream<Arguments> fixtures() throws IOException, URISyntaxException {
        Path directory = Path.of(Objects.requireNonNull(ReplyStripperTest.class.getResource("/reply-stripper")).toURI());
        try (Stream<Path> files = Files.list(directory)) {
            return files.sorted().map(ReplyStripperTest::fixture).toList().stream();
        }
    }

    private static Arguments fixture(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int marker = content.indexOf(EXPECTED_MARKER);
            return arguments(file.getFileName().toString(),
                    content.substring(0, marker),
                    content.substring(marker + EXPECTED_MARKER.length()).stripTrailing());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void stripsQuotedHistoryAndSignature(String name, String body, String expected) {
        assertThat(ReplyStripper.strip(body, false)).isEqualTo(expected);
    }

    @Test
    void neverCutsAForward() {
        String forward = """
                FYI, see below.

                ---------- Forwarded message ---------
                From: Pau Serra <pau.serra@example.com>
                Date: Mon, 3 Nov 2026 at 18:02
                Subject: Offer
                To: Carla Font <carla.font@example.com>

                The offer is attached.
                """;

        assertThat(ReplyStripper.strip(forward, true)).isEqualTo(forward);
    }

    @Test
    void keepsTheOriginalWhenEverythingWouldBeCut() {
        String onlyQuotes = """
                On Mon, 3 Nov 2026 at 09:14, Laura <laura@example.com> wrote:
                > Hello
                """;

        assertThat(ReplyStripper.strip(onlyQuotes, false)).isEqualTo(onlyQuotes);
    }

    @Test
    void normalizesWindowsLineEndings() {
        assertThat(ReplyStripper.strip("Agreed.\r\n\r\n> Plan B?\r\n", false)).isEqualTo("Agreed.");
    }

    @Test
    void stripsLongBodiesInLinearTime() {
        String longLine = "El " + "1 ".repeat(50_000);

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThat(ReplyStripper.strip(longLine + "\n\n> quoted", false)).isEqualTo(longLine.stripTrailing()));
    }

    @Test
    void cutsTheSignatureAndTheQuotesAfterIt() {
        assertThat(ReplyStripper.strip("Done.\n-- \nMarc\n> old\n", false)).isEqualTo("Done.");
    }

    @Test
    void requiresTwoHeaderFieldsAfterFrom() {
        String body = """
                Thanks for the update.

                From: the finance team
                To: everyone, happy Friday!
                """;

        assertThat(ReplyStripper.strip(body, false)).isEqualTo(body.stripTrailing());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\n"})
    void returnsBlankInputUnchanged(String body) {
        assertThat(ReplyStripper.strip(body, false)).isEqualTo(body);
    }
}
