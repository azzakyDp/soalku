package id.soalku.practice;

import id.soalku.core.*;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration @EnableConfigurationProperties(LatihanProperties.class)
class LatihanConfiguration {
    @Bean LatihanStrategy acakStrategy() { return new AcakStrategy(); }
    @Bean LatihanStrategy berwaktuStrategy(LatihanProperties p) { return new BerwaktuStrategy(Duration.ofSeconds(p.secondsPerQuestion())); }
    @Bean LatihanStrategy fokusKelemahanStrategy() { return new FokusKelemahanStrategy(); }
}
