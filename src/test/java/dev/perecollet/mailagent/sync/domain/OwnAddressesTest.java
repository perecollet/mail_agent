package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OwnAddressesTest {

    private final OwnAddresses own = new OwnAddresses(List.of("pere@gmail.com", "pere@icloud.com"));

    @ParameterizedTest
    @ValueSource(strings = {"pere@gmail.com", "Pere@Gmail.com", "  pere@icloud.com  ", "PERE@ICLOUD.COM"})
    void recognizesOwnAddressesRegardlessOfCaseAndSpaces(String address) {
        assertThat(own.contains(address)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ana@gmail.com", "pere@gmail.co", "pere", ""})
    void rejectsAddressesThatAreNotOwn(String address) {
        assertThat(own.contains(address)).isFalse();
    }

    @Test
    void nullIsNotOwn() {
        assertThat(own.contains(null)).isFalse();
    }

    @Test
    void normalizesTheConfiguredAddressesToo() {
        OwnAddresses configuredInUpperCase = new OwnAddresses(List.of("  Pere@Gmail.COM "));

        assertThat(configuredInUpperCase.contains("pere@gmail.com")).isTrue();
    }

    @Test
    void ignoresBlankConfiguredAddresses() {
        OwnAddresses withBlank = new OwnAddresses(List.of("pere@gmail.com", "   "));

        assertThat(withBlank.contains("")).isFalse();
        assertThat(withBlank.contains("   ")).isFalse();
    }

    @Test
    void isNotAffectedByLaterChangesToTheSourceList() {
        List<String> source = new ArrayList<>(List.of("pere@gmail.com"));
        OwnAddresses copy = new OwnAddresses(source);

        source.add("intruder@evil.com");

        assertThat(copy.contains("intruder@evil.com")).isFalse();
    }

    @Test
    void doesNotPrintTheAddresses() {
        assertThat(own.toString()).doesNotContain("pere").contains("count=2");
    }
}
