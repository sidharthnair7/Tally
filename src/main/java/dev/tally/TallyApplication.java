package dev.tally;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Tally: an AI auditor that reads a company's books where they live and remembers what the controller cleared. */
@SpringBootApplication
public class TallyApplication {

    public static void main(String[] args) {
        SpringApplication.run(TallyApplication.class, args);
    }
}
