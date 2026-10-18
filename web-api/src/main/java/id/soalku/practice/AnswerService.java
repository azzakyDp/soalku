package id.soalku.practice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.soalku.core.Role;
import id.soalku.persistence.auth.PenggunaRepository;
import id.soalku.persistence.practice.*;
import id.soalku.persistence.question.OpsiJawabanRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @PreAuthorize("hasRole('SISWA')")
public class AnswerService {
    private static final Logger log=LoggerFactory.getLogger(AnswerService.class);
    private final PenggunaRepository users;
    private final SesiLatihanRepository sessions;
    private final RiwayatJawabanRepository answers;
    private final OpsiJawabanRepository options;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AnswerService(PenggunaRepository users,SesiLatihanRepository sessions,RiwayatJawabanRepository answers,
            OpsiJawabanRepository options,ObjectMapper json,JdbcTemplate jdbc,Clock clock) {
        this.users=users;this.sessions=sessions;this.answers=answers;this.options=options;this.json=json;this.jdbc=jdbc;this.clock=clock;
    }
    @Transactional(timeout=10)
    public PracticeDtos.Answer submit(Long student,Long sessionId,PracticeDtos.SubmitRequest request) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");jdbc.execute("SET LOCAL statement_timeout = '5s'");
        if(users.findById(student).filter(u->u.getRole()==Role.SISWA).isEmpty())
            throw new PracticeFailure(403,"FORBIDDEN","Akun SISWA aktif diperlukan.");
        var session=sessions.lockOwned(sessionId,student).orElseThrow(()->new PracticeFailure(404,"SESSION_NOT_FOUND","Sesi tidak ditemukan."));
        var snapshot=snapshot(session);
        var question=snapshot.stream().filter(q->request.soalId().equals(q.id())).findFirst()
                .orElseThrow(()->new PracticeFailure(400,"QUESTION_NOT_IN_SESSION","Soal tidak termasuk dalam sesi latihan ini."));
        var previous=answers.findBySesiIdAndSoalId(sessionId,request.soalId());
        if(previous.isPresent()) {
            var saved=previous.get();
            if(!saved.getOpsiDipilih().equals(request.opsiId()))
                throw new PracticeFailure(409,"ANSWER_ALREADY_SUBMITTED","Jawaban sudah tersimpan dan tidak dapat diganti.");
            // Accepted receipts remain replayable after completion/deadline; a retry creates no new attempt.
            log.atInfo().addKeyValue("event","practice.answer_replayed").addKeyValue("student_id",student)
                    .addKeyValue("session_id",sessionId).addKeyValue("question_id",request.soalId()).log("Existing answer returned");
            return receipt(saved);
        }
        if(question.opsi().stream().noneMatch(o->request.opsiId().equals(o.id())))
            throw new PracticeFailure(400,"INVALID_OPTION","Opsi tidak termasuk dalam soal sesi ini.");
        var option=options.findByIdAndSoalId(request.opsiId(),request.soalId())
                .orElseThrow(()->new PracticeFailure(400,"INVALID_OPTION","Opsi soal tidak ditemukan."));
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(session.getBerakhirAt()!=null && !now.isBefore(session.getBerakhirAt()))
            throw new PracticeFailure(409,"SESSION_EXPIRED","Batas waktu sesi latihan sudah berakhir.");
        if(session.getSelesaiAt()!=null) throw new PracticeFailure(409,"SESSION_COMPLETED","Sesi latihan sudah selesai.");
        var saved=answers.saveAndFlush(new RiwayatJawabanEntity(sessionId,request.soalId(),request.opsiId(),option.isBenar(),now));
        if(answers.countBySesiId(sessionId)==snapshot.size()) { session.complete(now);sessions.flush(); }
        log.atInfo().addKeyValue("event","practice.answer_saved").addKeyValue("student_id",student)
                .addKeyValue("session_id",sessionId).addKeyValue("question_id",request.soalId())
                .addKeyValue("completed",session.getSelesaiAt()!=null).log("Answer saved");
        return receipt(saved);
    }
    private List<PracticeDtos.Question> snapshot(SesiLatihanEntity session) {
        if(session.getSoalSnapshot()==null)
            throw new PracticeFailure(409,"SESSION_SNAPSHOT_REQUIRED","Sesi lama tidak mempunyai daftar soal. Mulai sesi baru.");
        try { return json.readValue(session.getSoalSnapshot(),new TypeReference<List<PracticeDtos.Question>>() { }); }
        catch(JsonProcessingException failure) { throw new PracticeFailure(500,"SESSION_SNAPSHOT_INVALID","Snapshot sesi belum dapat dibaca.",failure); }
    }
    private static PracticeDtos.Answer receipt(RiwayatJawabanEntity a) {
        return new PracticeDtos.Answer(a.getId(),a.getSesiId(),a.getSoalId(),a.getOpsiDipilih(),a.isBenar(),a.getDijawabAt());
    }
}
