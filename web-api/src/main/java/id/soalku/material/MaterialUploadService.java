package id.soalku.material;

import id.soalku.core.*;
import id.soalku.extraction.*;
import id.soalku.persistence.auth.PenggunaRepository;
import id.soalku.persistence.material.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.security.access.AccessDeniedException;

@Service
public class MaterialUploadService {
    private static final Logger log = LoggerFactory.getLogger(MaterialUploadService.class);
    private final DocumentExtractor extractor;
    private final MaterialStorage storage;
    private final PenggunaRepository users;
    private final MateriRepository materials;
    private final BagianMateriRepository sections;
    private final GambarMateriRepository images;
    private final TransactionTemplate transactions;

    public record ImageResult(Long id,Long bagianMateriId) { }
    public record SectionResult(Long id,String judul,int urutan,List<ImageResult> gambar) { }
    public record UploadResult(Long id,String judul,Jenjang jenjang,List<SectionResult> bagian,
                               List<String> peringatan,boolean duplikat) { }

    public MaterialUploadService(DocumentExtractor extractor,MaterialStorage storage,PenggunaRepository users,
                                 MateriRepository materials,BagianMateriRepository sections,GambarMateriRepository images,
                                 PlatformTransactionManager manager) {
        this.extractor=extractor; this.storage=storage; this.users=users; this.materials=materials;
        this.sections=sections; this.images=images; this.transactions=new TransactionTemplate(manager);
    }

    public UploadResult upload(Long owner,String title,Jenjang level,String filename,byte[] bytes) {
        if (title == null || title.isBlank() || title.strip().length()>255 || level == null)
            throw new MaterialFailure(400,"INVALID_METADATA","Judul wajib diisi (maksimal 255 karakter); jenjang harus SD atau SMP.");
        String normalizedTitle=title.strip();
        var extracted=extractor.extract(filename,bytes);
        String extension=filename.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "pdf" : "docx";
        String directory="materi/"+owner+"/"+digest(bytes,normalizedTitle,level);
        String originalPath=directory+"/original."+extension;
        var assets=new LinkedHashMap<String,byte[]>();
        assets.put("original."+extension,bytes);
        for (int section=0;section<extracted.sections().size();section++) {
            var pictures=extracted.sections().get(section).images();
            for (int image=0;image<pictures.size();image++) assets.put(imageName(section,image),pictures.get(image));
        }
        var staged=storage.stage(assets);
        try {
            return transactions.execute(status -> {
                // Serialize finalization for one owner. Different owners retain separate material identities.
                var user=users.lockForUpload(owner).orElseThrow(() -> new AccessDeniedException("Account unavailable"));
                if (user.getRole()!=Role.SISWA) throw new AccessDeniedException("Only SISWA can upload");
                var existing=materials.findByFilePath(originalPath);
                if (existing.isPresent()) return response(existing.get(),extracted.warnings(),true);
                var published=storage.publish(staged,directory);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int completion) {
                        storage.transactionCompleted(published,completion);
                    }
                });
                var material=materials.saveAndFlush(new MateriEntity(normalizedTitle,originalPath,owner,level));
                for (int section=0;section<extracted.sections().size();section++) {
                    var parsed=extracted.sections().get(section);
                    var entity=sections.saveAndFlush(new BagianMateriEntity(material.getId(),parsed.title(),parsed.text(),section+1));
                    for (int image=0;image<parsed.images().size();image++)
                        images.save(new GambarMateriEntity(material.getId(),entity.getId(),directory+"/"+imageName(section,image)));
                }
                images.flush();
                return response(material,extracted.warnings(),false);
            });
        } finally { storage.discard(staged); }
    }

    private UploadResult response(MateriEntity material,List<String> warnings,boolean duplicate) {
        var allImages=images.findByMateriIdOrderById(material.getId());
        var allSections=sections.findByMateriIdOrderByUrutan(material.getId());
        var result=allSections.stream().map(section -> new SectionResult(section.getId(),section.getJudulBagian(),section.getUrutan(),
                allImages.stream().filter(image -> section.getId().equals(image.getBagianMateriId()))
                        .map(image -> new ImageResult(image.getId(),image.getBagianMateriId())).toList())).toList();
        log.atInfo().addKeyValue("event","material.upload_persisted").addKeyValue("owner_id",material.getDiunggahOleh())
                .addKeyValue("material_id",material.getId()).addKeyValue("sections",result.size())
                .addKeyValue("images",allImages.size()).addKeyValue("duplicate",duplicate).log("Material upload persisted");
        return new UploadResult(material.getId(),material.getJudul(),material.getJenjang(),result,warnings,duplicate);
    }
    private static String imageName(int section,int image) { return "images/section-"+(section+1)+"-image-"+(image+1)+".png"; }
    private static String digest(byte[] bytes,String title,Jenjang level) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            byte[] titleBytes=title.getBytes(StandardCharsets.UTF_8);
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(titleBytes.length).array()); hash.update(titleBytes);
            hash.update(level.name().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("Java 21 requires SHA-256",failure); }
    }
}
