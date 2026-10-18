package id.soalku.material;

import id.soalku.extraction.DocumentExtractor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
class MaterialConfiguration {
    @Bean DocumentExtractor documentExtractor(StorageProperties properties) {
        System.setProperty("pdfbox.fontcache",properties.root().resolve(".font-cache").toString());
        return new DocumentExtractor();
    }
}
