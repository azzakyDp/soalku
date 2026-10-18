package id.soalku.material;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.actuate.health.*;

/** Private, application-owned storage. Paths are generated internally, never from filenames. */
@Component("materialStorage")
public class MaterialStorage implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(MaterialStorage.class);
    private final Path root;
    public MaterialStorage(StorageProperties properties) { root = properties.root(); }

    record Staged(Path directory, Map<String,byte[]> files) { }
    record Published(Path directory, Set<String> files, boolean created) { }

    Staged stage(Map<String,byte[]> files) {
        Path directory = null;
        try {
            Files.createDirectories(root.resolve(".staging"));
            directory = Files.createTempDirectory(root.resolve(".staging"),"upload-");
            for (var file : files.entrySet()) {
                Path destination = directory.resolve(file.getKey());
                Files.createDirectories(destination.getParent());
                Files.write(destination,file.getValue(),StandardOpenOption.CREATE_NEW);
            }
            return new Staged(directory,files);
        } catch (IOException failure) {
            if (directory != null) cleanup(directory,files.keySet());
            throw storageFailure(failure);
        }
    }

    Published publish(Staged staged,String relativeDirectory) {
        Path destination = root.resolve(relativeDirectory).normalize();
        if (!destination.startsWith(root)) throw new IllegalArgumentException("Unsafe internal storage path");
        try {
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(destination)) throw new IOException("Storage destination is a symlink");
                // Recover an identical orphan left by a process crash after moving files, before DB commit.
                for (var file : staged.files.entrySet()) {
                    Path existing = destination.resolve(file.getKey());
                    if (!Files.isRegularFile(existing,LinkOption.NOFOLLOW_LINKS)
                            || Files.size(existing) != file.getValue().length
                            || !Arrays.equals(Files.readAllBytes(existing),file.getValue()))
                        throw new MaterialFailure(409,"STORAGE_CONFLICT","Berkas penyimpanan bertentangan; hubungi pengelola.");
                }
                return new Published(destination,staged.files.keySet(),false);
            }
            Files.move(staged.directory,destination,StandardCopyOption.ATOMIC_MOVE);
            return new Published(destination,staged.files.keySet(),true);
        } catch (IOException failure) { throw storageFailure(failure); }
    }
    void discard(Staged staged) { cleanup(staged.directory,staged.files.keySet()); }
    void rollback(Published published) { if (published.created) cleanup(published.directory,published.files); }
    void transactionCompleted(Published published,int completion) {
        if (completion==org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK) rollback(published);
        else if (completion==org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN)
            log.atError().addKeyValue("event","material.commit_unknown")
                    .log("Commit outcome unknown; preserve upload files for recovery");
    }

    private void cleanup(Path directory,Set<String> files) {
        // Only delete the known files produced by this attempt; never recursively delete an arbitrary tree.
        for (String file : files) {
            try { Files.deleteIfExists(directory.resolve(file)); }
            catch (IOException failure) { log.atError().addKeyValue("event","material.cleanup_failed").setCause(failure).log("Could not clean upload asset"); }
        }
        for (Path path : List.of(directory.resolve("images"),directory)) {
            try { Files.deleteIfExists(path); }
            catch (IOException failure) { log.atError().addKeyValue("event","material.cleanup_failed").setCause(failure).log("Could not clean upload directory"); }
        }
    }
    private static MaterialFailure storageFailure(IOException failure) {
        return new MaterialFailure(500,"STORAGE_UNAVAILABLE","Penyimpanan materi tidak tersedia. Silakan coba kembali.",failure);
    }

    @Override public Health health() {
        Path probe = null;
        try {
            Files.createDirectories(root);
            probe = Files.createTempFile(root,".health-",".tmp");
            byte[] expected = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(probe,expected);
            if (!Arrays.equals(expected,Files.readAllBytes(probe))) throw new IOException("Storage read/write mismatch");
            Files.delete(probe); probe = null;
            return Health.up().build();
        } catch (IOException failure) {
            log.atWarn().addKeyValue("event","material.storage_unhealthy").addKeyValue("cause",failure.getClass().getSimpleName()).log("Material storage is unavailable");
            return Health.down().build();
        } finally {
            if (probe != null) {
                try { Files.deleteIfExists(probe); }
                catch (IOException failure) { log.warn("Could not remove storage health probe",failure); }
            }
        }
    }
}
