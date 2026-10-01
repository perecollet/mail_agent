package dev.perecollet.mailagent;

import org.springframework.boot.SpringApplication;

public class TestMailAgentApplication {

	public static void main(String[] args) {
		SpringApplication.from(MailAgentApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
