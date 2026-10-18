package id.soalku.ai;

import id.soalku.core.*;
import id.soalku.persistence.auth.PenggunaRepository;
import id.soalku.persistence.material.*;
import id.soalku.persistence.question.*;
import id.soalku.material.StorageProperties;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static id.soalku.core.QuestionGenerationException.Reason.*;

@Service
public class MaterialGenerationService {
    private static final Logger log=LoggerFactory.getLogger(MaterialGenerationService.class);
    private static final Duration LEASE=Duration.ofMinutes(2);
    private final QuestionGeneratorService generator;
    private final GeminiProperties config;
    private final PenggunaRepository users;
    private final MateriRepository materials;
    private final BagianMateriRepository sections;
    private final GambarMateriRepository images;
    private final SoalRepository questions;
    private final OpsiJawabanRepository options;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Path root;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public record SectionResult(Long bagianId,String status,String ringkasan,List<Long> soalIds,String code,String message) { }
    public record GenerationResult(Long materiId,boolean selesai,List<SectionResult> bagian) { }
    public record Outcome(int httpStatus,GenerationResult body) { }
    private record Claim(BagianMateriEntity section,UUID token,SectionResult existing) { }

    public MaterialGenerationService(QuestionGeneratorService generator,GeminiProperties config,PenggunaRepository users,
            MateriRepository materials,BagianMateriRepository sections,GambarMateriRepository images,SoalRepository questions,
            OpsiJawabanRepository options,PlatformTransactionManager manager,Clock clock,StorageProperties storage,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.generator=generator;this.config=config;this.users=users;this.materials=materials;this.sections=sections;
        this.images=images;this.questions=questions;this.options=options;this.tx=new TransactionTemplate(manager);
        this.clock=clock;this.root=storage.root();this.jdbc=jdbc;this.tx.setTimeout(5);
    }

    public Outcome generate(Long owner,Long materialId) {
        var material=ownedMaterial(owner,materialId);
        var source=sections.findByMateriIdOrderByUrutan(materialId);
        var groupedImages=new HashMap<Long,List<GambarMateriEntity>>();
        for(var image:images.findByMateriIdOrderById(materialId))
            groupedImages.computeIfAbsent(image.getBagianMateriId(),ignored -> new ArrayList<>()).add(image);
        var results=new ArrayList<SectionResult>();int responseStatus=200;
        long deadline=System.nanoTime()+Duration.ofSeconds(55).toNanos();
        for(var section:source) {
            if(System.nanoTime()>=deadline) {
                results.add(rejected(section.getId(),"AI_TIMEOUT","Batas waktu batch tercapai. Retry untuk melanjutkan."));
                responseStatus=504;continue;
            }
            Claim claim;
            try { claim=claim(owner,materialId,section.getId()); }
            catch (GenerationFailure failure) {
                if (failure.status==404 || failure.status==403) throw failure;
                results.add(rejected(section.getId(),failure.code,failure.getMessage()));responseStatus=failure.status;continue;
            }
            if(claim.existing()!=null) { results.add(claim.existing());continue; }
            try {
                if(deadline-System.nanoTime()<config.timeout().toNanos())
                    throw new QuestionGenerationException(TIMEOUT,"Batas waktu batch tercapai. Retry akan melanjutkan bagian yang belum selesai.");
                var requested=prepare(material,claim.section(),groupedImages.getOrDefault(section.getId(),List.of()));
                // No database transaction/row lock is held while doing the network call.
                var generated=generator.generate(requested);
                results.add(complete(owner,materialId,claim,generated));
            } catch (QuestionGenerationException failure) {
                release(claim);
                responseStatus=status(failure.reason());results.add(rejected(section.getId(),"AI_"+failure.reason(),failure.getMessage()));
                log.atWarn().addKeyValue("event","ai.section_failed").addKeyValue("material_id",materialId)
                        .addKeyValue("section_id",section.getId()).addKeyValue("reason",failure.reason()).log("Section generation failed; source preserved");
            } catch (RuntimeException failure) {
                // Keep the original exception for the error handler. A failed DB commit may be ambiguous;
                // conditional release cannot undo a completed generation or a newer owner's lease.
                release(claim);throw failure;
            }
        }
        boolean complete=results.stream().allMatch(result -> result.code()==null);
        log.atInfo().addKeyValue("event","ai.material_completed").addKeyValue("material_id",materialId)
                .addKeyValue("owner_id",owner).addKeyValue("complete",complete).addKeyValue("sections",results.size()).log("Material generation request completed");
        return new Outcome(complete ? 200 : responseStatus,new GenerationResult(materialId,complete,List.copyOf(results)));
    }

    private MateriEntity ownedMaterial(Long owner,Long id) {
        if(users.findById(owner).filter(user -> user.getRole()==Role.SISWA).isEmpty())
            throw new GenerationFailure(403,"FORBIDDEN","Akun SISWA diperlukan.");
        return materials.findById(id).filter(material -> material.getDiunggahOleh().equals(owner))
                .orElseThrow(() -> new GenerationFailure(404,"MATERIAL_NOT_FOUND","Materi tidak ditemukan."));
    }
    private Claim claim(Long owner,Long materialId,Long sectionId) {
        return tx.execute(status -> {
            databaseTimeouts();
            ownedMaterial(owner,materialId);
            var section=sections.lockForGeneration(sectionId).filter(value -> value.getMateriId().equals(materialId))
                    .orElseThrow(() -> new GenerationFailure(404,"SECTION_NOT_FOUND","Bagian materi tidak ditemukan."));
            if(section.getGeneratedAt()!=null) return new Claim(section,null,saved(section,"SUDAH_ADA"));
            if(section.getGenerationToken()!=null && section.getGenerationStartedAt().plus(LEASE).isAfter(clock.instant()))
                throw new GenerationFailure(409,"GENERATION_IN_PROGRESS","Bagian sedang diproses. Silakan coba kembali setelah proses selesai.");
            UUID token=UUID.randomUUID();section.claimGeneration(token,clock.instant());sections.flush();
            return new Claim(section,token,null);
        });
    }
    private QuestionGeneratorService.Request prepare(MateriEntity material,BagianMateriEntity section,List<GambarMateriEntity> pictures) {
        if(section.getTeksAsli().isBlank() || section.getTeksAsli().length()>50_000)
            throw new QuestionGenerationException(INVALID_INPUT,"Bagian memerlukan teks 1–50.000 karakter. OCR belum tersedia untuk bagian tanpa teks.");
        var input=new ArrayList<QuestionGeneratorService.Image>();long remaining=10L*1024*1024;
        for(var picture:pictures) {
            Path path=root.resolve(picture.getFilePath()).normalize();
            if(!path.startsWith(root)) throw new QuestionGenerationException(INVALID_INPUT,"Lokasi gambar materi tidak valid.");
            try {
                Path current=root;
                for(var segment:root.relativize(path)) {
                    current=current.resolve(segment);
                    if(Files.isSymbolicLink(current)) throw new IOException("Symlink asset rejected");
                }
                if(Files.size(path)>remaining) throw new QuestionGenerationException(INVALID_INPUT,"Total gambar per bagian untuk AI maksimal 10 MiB.");
                byte[] bytes;
                try(var stream=Files.newInputStream(path)) { bytes=stream.readNBytes((int)remaining+1); }
                if(bytes.length>remaining) throw new QuestionGenerationException(INVALID_INPUT,"Total gambar per bagian untuk AI maksimal 10 MiB.");
                remaining-=bytes.length;input.add(new QuestionGeneratorService.Image(picture.getId(),bytes));
            } catch(IOException failure) { throw new QuestionGenerationException(UNAVAILABLE,"Gambar materi tidak dapat dibaca. Materi tetap tersimpan.",failure); }
        }
        return new QuestionGeneratorService.Request(material.getJenjang(),section.getJudulBagian(),section.getTeksAsli(),input,config.questionsPerSection());
    }
    private SectionResult complete(Long owner,Long materialId,Claim claim,QuestionGeneratorService.GeneratedSection generated) {
        return tx.execute(status -> {
            databaseTimeouts();
            ownedMaterial(owner,materialId);
            var section=sections.lockForGeneration(claim.section().getId()).orElseThrow();
            if(section.getGeneratedAt()!=null) return saved(section,"SUDAH_ADA");
            if(!claim.token().equals(section.getGenerationToken())) throw new QuestionGenerationException(BUSY,"Lease generate sudah diganti. Retry untuk membaca hasil terbaru.");
            int order=1;
            for(var question:generated.soal()) {
                var entity=questions.saveAndFlush(new SoalEntity(section.getId(),question,order++));
                for(var option:question.getOpsi()) options.save(new OpsiJawabanEntity(entity.getId(),option));
            }
            options.flush();section.completeGeneration(generated.ringkasan(),clock.instant());sections.flush();
            return saved(section,"BERHASIL");
        });
    }
    private void release(Claim claim) {
        try {
            tx.executeWithoutResult(status -> {
                databaseTimeouts();
                sections.lockForGeneration(claim.section().getId()).ifPresent(section -> {
                if(claim.token().equals(section.getGenerationToken())) section.releaseGeneration();
                });
            });
        } catch(RuntimeException failure) {
            log.atError().addKeyValue("event","ai.lease_release_failed").addKeyValue("section_id",claim.section().getId())
                    .addKeyValue("cause",failure.getClass().getSimpleName()).log("Could not release generation lease; it expires automatically");
        }
    }
    private SectionResult saved(BagianMateriEntity section,String status) {
        return new SectionResult(section.getId(),status,section.getTeksRingkasan(),
                questions.findByBagianMateriIdOrderByUrutanGenerasi(section.getId()).stream().map(SoalEntity::getId).toList(),null,null);
    }
    private void databaseTimeouts() {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
    }
    private static SectionResult rejected(Long id,String code,String message) { return new SectionResult(id,"GAGAL",null,List.of(),code,message); }
    static int status(QuestionGenerationException.Reason reason) {
        return switch(reason) {
            case NOT_CONFIGURED,UNAVAILABLE -> 503;
            case TIMEOUT -> 504;
            case BUSY -> 409;
            case INVALID_INPUT -> 422;
            case INVALID_OUTPUT,REJECTED -> 502;
        };
    }
}
