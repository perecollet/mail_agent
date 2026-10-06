package dev.perecollet.mailagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MailAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(MailAgentApplication.class, args);
	}

}
