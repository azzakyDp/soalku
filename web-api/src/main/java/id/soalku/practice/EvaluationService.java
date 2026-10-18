package id.soalku.practice;

import id.soalku.core.EvaluasiBagian;
import id.soalku.core.Role;
import id.soalku.persistence.auth.PenggunaRepository;
import id.soalku.persistence.practice.*;
import org.slf4j.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @PreAuthorize("hasRole('SISWA')")
public class EvaluationService {
    private static final Logger log=LoggerFactory.getLogger(EvaluationService.class);
    private final PenggunaRepository users;
    private final SesiLatihanRepository sessions;
    private final RiwayatJawabanRepository answers;
    private final JdbcTemplate jdbc;
    public EvaluationService(PenggunaRepository users,SesiLatihanRepository sessions,RiwayatJawabanRepository answers,JdbcTemplate jdbc) {
        this.users=users;this.sessions=sessions;this.answers=answers;this.jdbc=jdbc;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public PracticeDtos.Evaluation evaluate(Long student,Long sessionId,int page,int size) {
        if(page<0 || page>1_000_000 || size<1 || size>100 || (sessionId!=null && sessionId<1))
            throw new PracticeFailure(400,"INVALID_PAGE","Page harus 0–1000000, size 1–100, dan sesiId positif.");
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
        if(users.findById(student).filter(u->u.getRole()==Role.SISWA).isEmpty())
            throw new PracticeFailure(403,"FORBIDDEN","Akun SISWA aktif diperlukan.");
        if(sessionId!=null && sessions.findByIdAndSiswaId(sessionId,student).isEmpty())
            throw new PracticeFailure(404,"SESSION_NOT_FOUND","Sesi tidak ditemukan.");
        var result=answers.aggregateBySection(student,sessionId,PageRequest.of(page,size));
        // Map aggregate values only: no repository calls or lazy entity traversal inside this loop.
        var content=result.stream().map(row->{
            var section=new EvaluasiBagian(row.getBagianMateriId(),row.getJudulBagian(),row.getTotalJawaban(),row.getJumlahBenar());
            return new PracticeDtos.SectionEvaluation(section.bagianMateriId(),section.judulBagian(),section.totalJawaban(),section.jumlahBenar(),
                    section.jumlahSalah(),section.persentaseBenar(),section.perluDiulang(),
                    section.perluDiulang()?"Ulangi bagian "+section.judulBagian()+".":null);
        }).toList();
        log.atInfo().addKeyValue("event","practice.evaluation_loaded").addKeyValue("student_id",student)
                .addKeyValue("session_id",sessionId).addKeyValue("page",page).addKeyValue("sections",content.size()).log("Evaluation loaded");
        return new PracticeDtos.Evaluation(sessionId,content,page,size,result.getTotalElements(),result.getTotalPages());
    }
}
