package dev.perecollet.mailagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"mailagent.accounts[0].id=test",
		"mailagent.accounts[0].host=imap.example.invalid",
		"mailagent.accounts[0].username=user@example.invalid",
		"mailagent.accounts[0].password=not-a-secret",
		"mailagent.accounts[0].addresses=user@example.invalid"
})
class MailAgentApplicationIT {

	@Test
	void contextLoads() {
	}

}
