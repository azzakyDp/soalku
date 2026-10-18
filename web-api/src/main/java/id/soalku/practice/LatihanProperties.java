package id.soalku.practice;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("soalku.latihan")
public record LatihanProperties(@Min(1) @Max(600) int secondsPerQuestion) { }
