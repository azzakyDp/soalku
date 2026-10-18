package id.soalku.review;

import id.soalku.core.*;
import id.soalku.persistence.auth.PenggunaRepository;
import id.soalku.persistence.question.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@PreAuthorize("hasRole('MENTOR')")
public class QuestionReviewService {
    private static final Logger log=LoggerFactory.getLogger(QuestionReviewService.class);
    private final PenggunaRepository users;
    private final SoalRepository questions;
    private final OpsiJawabanRepository options;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public QuestionReviewService(PenggunaRepository users,SoalRepository questions,OpsiJawabanRepository options,
            Clock clock,JdbcTemplate jdbc) {
        this.users=users;this.questions=questions;this.options=options;this.clock=clock;this.jdbc=jdbc;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public ReviewDtos.Queue pending(Long mentor,int page,int size) {
        requireMentor(mentor);
        if(page<0 || page>1_000_000 || size<1 || size>100)
            throw new ReviewFailure(400,"INVALID_PAGE","Page harus 0–1000000 dan size harus 1–100.");
        var found=questions.findByStatusReview(StatusReview.MENUNGGU,
                PageRequest.of(page,size,Sort.by("createdAt").ascending().and(Sort.by("id").ascending())));
        // One bulk options query for the whole page, rather than a query for every question.
        var grouped=found.isEmpty()?Map.<Long,List<OpsiJawabanEntity>>of():options
                .findBySoalIdInOrderBySoalIdAscUrutanAsc(found.map(SoalEntity::getId).getContent()).stream()
                .collect(Collectors.groupingBy(OpsiJawabanEntity::getSoalId));
        var content=found.stream().map(q->view(q,grouped.getOrDefault(q.getId(),List.of()))).toList();
        log.atInfo().addKeyValue("event","review.queue_listed").addKeyValue("mentor_id",mentor)
                .addKeyValue("page",page).addKeyValue("count",content.size()).log("Pending question queue loaded");
        return new ReviewDtos.Queue(content,page,size,found.getTotalElements(),found.getTotalPages());
    }
    @Transactional(timeout=5)
    public ReviewDtos.Question edit(Long mentor,Long id,ReviewDtos.EditRequest request) {
        requireMentor(mentor);timeouts();
        var question=locked(id);
        if(question.getStatusReview()!=StatusReview.MENUNGGU) throw alreadyReviewed();
        var existing=options.findBySoalIdOrderByUrutan(id);
        var submitted=request.opsi();
        var expectedIds=existing.stream().map(OpsiJawabanEntity::getId).collect(Collectors.toSet());
        var actualIds=submitted.stream().map(ReviewDtos.EditOption::id).collect(Collectors.toSet());
        if(actualIds.size()!=submitted.size() || !expectedIds.equals(actualIds))
            throw new ReviewFailure(400,"INVALID_OPTIONS","Gunakan seluruh ID opsi milik soal, sesuai jenjang (3 SD/4 SMP), dengan tepat satu jawaban benar.");
        var byId=submitted.stream().collect(Collectors.toMap(ReviewDtos.EditOption::id,o->o));
        try {
            new SoalPilihanGanda(jenjang(question),request.pertanyaan(),existing.stream().map(o->{
                var edit=byId.get(o.getId());return new OpsiJawaban(edit.teks(),o.getUrutan(),edit.benar());
            }).toList(),question.getGambarId());
        } catch(IllegalArgumentException failure) {
            throw new ReviewFailure(400,"INVALID_OPTIONS","Teks/opsi tidak valid: gunakan opsi berbeda, sesuai jenjang, dan tepat satu kunci.");
        }
        // Preserve option IDs and release the old partial-unique answer key before assigning a new one.
        existing.forEach(o->o.edit(o.getTeksOpsi(),false));options.flush();
        existing.forEach(o->{var edit=byId.get(o.getId());o.edit(edit.teks().strip(),edit.benar());});
        question.editPertanyaan(request.pertanyaan().strip());options.flush();questions.flush();
        log.atInfo().addKeyValue("event","review.question_edited").addKeyValue("mentor_id",mentor)
                .addKeyValue("question_id",id).log("Pending question edited");
        return view(question,existing);
    }
    @Transactional(timeout=5)
    public ReviewDtos.Question decide(Long mentor,Long id,StatusReview decision) {
        requireMentor(mentor);timeouts();
        if(decision==null || decision==StatusReview.MENUNGGU) throw new IllegalArgumentException("Final review decision required");
        var question=locked(id);
        var savedOptions=options.findBySoalIdOrderByUrutan(id);
        if(question.getStatusReview()==decision) {
            log.atInfo().addKeyValue("event","review.decision_replayed").addKeyValue("question_id",id)
                    .addKeyValue("mentor_id",mentor).log("Existing review returned without changing audit");
            return view(question,savedOptions);
        }
        if(question.getStatusReview()!=StatusReview.MENUNGGU) throw alreadyReviewed();
        if(decision==StatusReview.DISETUJUI) {
            try {
                new SoalPilihanGanda(jenjang(question),question.getPertanyaan(),savedOptions.stream()
                        .map(o->new OpsiJawaban(o.getTeksOpsi(),o.getUrutan(),o.isBenar())).toList(),question.getGambarId());
            } catch(IllegalArgumentException failure) {
                throw new ReviewFailure(422,"INVALID_QUESTION","Perbaiki teks/opsi soal sesuai jenjang dengan opsi berbeda dan tepat satu kunci sebelum approve.");
            }
        }
        question.review(decision,mentor,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));questions.flush();
        log.atInfo().addKeyValue("event","review.decision_saved").addKeyValue("question_id",id)
                .addKeyValue("mentor_id",mentor).addKeyValue("status",decision).log("Question review saved");
        return view(question,savedOptions);
    }
    private void requireMentor(Long id) {
        // PRD grants Mentors the global review queue; there is no per-student assignment relation.
        if(users.findById(id).filter(u->u.getRole()==Role.MENTOR).isEmpty())
            throw new ReviewFailure(403,"FORBIDDEN","Akun MENTOR aktif diperlukan.");
    }
    private SoalEntity locked(Long id) {
        return questions.lockForReview(id).orElseThrow(()->new ReviewFailure(404,"QUESTION_NOT_FOUND","Soal tidak ditemukan."));
    }
    private Jenjang jenjang(SoalEntity question) {
        return questions.findJenjang(question.getBagianMateriId()).orElseThrow();
    }
    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");jdbc.execute("SET LOCAL statement_timeout = '5s'");
    }
    private static ReviewFailure alreadyReviewed() {
        return new ReviewFailure(409,"QUESTION_ALREADY_REVIEWED","Soal sudah direview; keputusan final tidak dapat diubah.");
    }
    private static ReviewDtos.Question view(SoalEntity q,List<OpsiJawabanEntity> opts) {
        return new ReviewDtos.Question(q.getId(),q.getBagianMateriId(),q.getPertanyaan(),q.getGambarId(),q.getStatusReview(),
                q.getDireviewOleh(),q.getDireviewAt(),q.getCreatedAt(),opts.stream()
                .map(o->new ReviewDtos.Option(o.getId(),o.getUrutan(),o.getTeksOpsi(),o.isBenar())).toList());
    }
}
