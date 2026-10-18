package id.soalku.material;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("soalku.storage")
public record StorageProperties(Path root) {
    public StorageProperties {
        if (root == null) throw new IllegalArgumentException("Material storage root is required");
        root = root.toAbsolutePath().normalize();
    }
}
