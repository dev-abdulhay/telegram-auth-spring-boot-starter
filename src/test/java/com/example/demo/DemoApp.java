package com.example.demo;

import org.springframework.boot.autoconfigure.SpringBootApplication;

// Equivalent to the previous @SpringBootConfiguration + @EnableAutoConfiguration +
// @ComponentScan trio, but @SpringBootApplication also wires the standard
// TypeExcludeFilter/AutoConfigurationExcludeFilter exclude filters into that scan.
// Those are what keep a test-only @TestConfiguration class declared elsewhere in
// this package (e.g. a nested fixture in another *Test.java) from being picked up
// as if it were real application config when an unrelated @SpringBootTest boots
// this class.
@SpringBootApplication
public class DemoApp {
}
