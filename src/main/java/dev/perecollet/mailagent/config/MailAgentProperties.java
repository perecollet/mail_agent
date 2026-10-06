package dev.perecollet.mailagent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mailagent")
public record MailAgentProperties(
        @NotEmpty List<@Valid Account> accounts,
        @Valid @DefaultValue Sync sync,
        @NotNull @DefaultValue("Europe/Madrid") ZoneId timezone) {

    public record Account(
            @NotBlank @Pattern(regexp = "[a-z0-9-]{1,32}") String id,
            @NotBlank String host,
            @DefaultValue("993") @Min(1) @Max(65535) int port,
            @DefaultValue("true") boolean ssl,
            @NotBlank String username,
            @NotBlank String password,
            @NotEmpty List<@NotBlank String> addresses,
            @DefaultValue({"INBOX", "\\Sent"}) @NotEmpty List<@NotBlank String> folders) {

        /** Never print the password: these records end up in logs and startup failure reports. */
        @Override
        public String toString() {
            return "Account[id=%s, host=%s, port=%d, ssl=%s, username=%s, password=***, addresses=%s, folders=%s]"
                    .formatted(id, host, port, ssl, username, addresses, folders);
        }
    }

    public record Sync(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT10S") Duration initialDelay,
            @DefaultValue("PT15M") Duration interval,
            @DefaultValue("P6M") Period initialWindow,
            @DefaultValue("50") @Min(1) @Max(500) int batchSize,
            @DefaultValue("P7D") Duration flagsWindow,
            @DefaultValue("0 0 4 * * *") String reconcileCron,
            @DefaultValue("P30D") Duration runRetention,
            @DefaultValue("PT30S") Duration imapTimeout,
            @DefaultValue("100000") @Min(1000) int maxBodyChars,
            @DefaultValue("10MB") DataSize maxAttachmentSize) {
    }
}