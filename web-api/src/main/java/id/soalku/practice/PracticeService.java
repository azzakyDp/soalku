package id.soalku.practice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.soalku.core.*;
import id.soalku.persistence.auth.PenggunaRepository;
import id.soalku.persistence.practice.*;
import id.soalku.persistence.question.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @PreAuthorize("hasRole('SISWA')")
public class PracticeService {
    private static final Logger log=LoggerFactory.getLogger(PracticeService.class);
    private final PenggunaRepository users;
    private final SesiLatihanRepository sessions;
    private final LatihanCandidateRepository candidates;
    private final SoalRepository questions;
    private final OpsiJawabanRepository options;
    private final Map<ModeLatihan,LatihanStrategy> strategies;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PracticeService(PenggunaRepository users,SesiLatihanRepository sessions,LatihanCandidateRepository candidates,
            SoalRepository questions,OpsiJawabanRepository options,List<LatihanStrategy> strategies,Clock clock,
            JdbcTemplate jdbc,ObjectMapper json) {
        this.users=users;this.sessions=sessions;this.candidates=candidates;this.questions=questions;this.options=options;
        this.strategies=strategies.stream().collect(Collectors.toUnmodifiableMap(LatihanStrategy::mode,s->s));
        if(this.strategies.size()!=ModeLatihan.values().length) throw new IllegalStateException("Missing practice strategy");
        this.clock=clock;this.jdbc=jdbc;this.json=json;
    }
    @Transactional(timeout=10)
    public PracticeDtos.Session start(Long student,UUID key,PracticeDtos.StartRequest request) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");jdbc.execute("SET LOCAL statement_timeout = '5s'");
        // Serialize starts for this student; the database unique key also protects against duplicate writes.
        if(users.lockForSessionStart(student).filter(u->u.getRole()==Role.SISWA).isEmpty())
            throw new PracticeFailure(403,"FORBIDDEN","Akun SISWA aktif diperlukan.");
        var existing=sessions.findBySiswaIdAndRequestKey(student,key);
        if(existing.isPresent()) {
            var saved=existing.get();
            if(saved.getMode()!=request.mode() || saved.getRequestedCount()!=request.jumlahSoal())
                throw new PracticeFailure(409,"IDEMPOTENCY_CONFLICT","Idempotency-Key sudah digunakan untuk permintaan berbeda.");
            log.atInfo().addKeyValue("event","practice.session_replayed").addKeyValue("student_id",student)
                    .addKeyValue("session_id",saved.getId()).log("Existing practice session returned");
            return response(saved);
        }
        var strategy=strategies.get(request.mode());
        RencanaLatihan plan;
        try(var source=candidates.approvedForStudent(student,request.mode()==ModeLatihan.FOKUS_KELEMAHAN)) {
            plan=strategy.pilih(source.iterator(),request.jumlahSoal(),clock.instant().truncatedTo(ChronoUnit.MICROS),RandomGenerator.getDefault());
        }
        if(plan.soalIds().isEmpty()) throw new PracticeFailure(409,"NO_APPROVED_QUESTIONS","Belum ada soal yang disetujui untuk latihan.");
        var selected=publicQuestions(plan.soalIds());
        String snapshot;
        try { snapshot=json.writeValueAsString(selected); }
        catch(JsonProcessingException failure) { throw new PracticeFailure(500,"SESSION_SERIALIZATION_FAILED","Sesi latihan belum dapat disimpan.",failure); }
        var session=sessions.saveAndFlush(new SesiLatihanEntity(student,request.mode(),key,request.jumlahSoal(),plan.mulaiAt(),plan.berakhirAt(),snapshot));
        log.atInfo().addKeyValue("event","practice.session_started").addKeyValue("student_id",student)
                .addKeyValue("session_id",session.getId()).addKeyValue("mode",session.getMode())
                .addKeyValue("questions",selected.size()).log("Practice session started");
        return new PracticeDtos.Session(session.getId(),session.getMode(),session.getMulaiAt(),session.getBerakhirAt(),selected);
    }
    private List<PracticeDtos.Question> publicQuestions(List<Long> ids) {
        var byId=questions.findAllById(ids).stream().collect(Collectors.toMap(SoalEntity::getId,q->q));
        var grouped=options.findBySoalIdInOrderBySoalIdAscUrutanAsc(ids).stream().collect(Collectors.groupingBy(OpsiJawabanEntity::getSoalId));
        return ids.stream().map(id->{
            var q=byId.get(id);
            if(q==null || q.getStatusReview()!=StatusReview.DISETUJUI)
                throw new PracticeFailure(409,"QUESTION_BANK_CHANGED","Bank soal berubah. Silakan ulangi permintaan.");
            return new PracticeDtos.Question(q.getId(),q.getBagianMateriId(),q.getPertanyaan(),q.getGambarId(),
                    grouped.getOrDefault(id,List.of()).stream().map(o->new PracticeDtos.Option(o.getId(),o.getUrutan(),o.getTeksOpsi())).toList());
        }).toList();
    }
    private PracticeDtos.Session response(SesiLatihanEntity saved) {
        try {
            List<PracticeDtos.Question> snapshot=json.readValue(saved.getSoalSnapshot(),new TypeReference<>() { });
            return new PracticeDtos.Session(saved.getId(),saved.getMode(),saved.getMulaiAt(),saved.getBerakhirAt(),List.copyOf(snapshot));
        } catch(JsonProcessingException failure) {
            throw new PracticeFailure(500,"SESSION_SNAPSHOT_INVALID","Snapshot sesi belum dapat dibaca.",failure);
        }
    }
}
