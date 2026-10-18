package id.soalku.dashboard;

import id.soalku.core.Role;
import id.soalku.persistence.auth.PenggunaRepository;
import id.soalku.persistence.dashboard.DashboardQueries;
import id.soalku.review.QuestionReviewService;
import java.math.*;
import java.time.*;
import java.util.List;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DashboardService {
    private static final Logger log=LoggerFactory.getLogger(DashboardService.class);
    private final PenggunaRepository users;
    private final DashboardQueries queries;
    private final QuestionReviewService reviews;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public DashboardService(PenggunaRepository users,DashboardQueries queries,QuestionReviewService reviews,JdbcTemplate jdbc,Clock clock) {
        this.users=users;this.queries=queries;this.reviews=reviews;this.jdbc=jdbc;this.clock=clock;
    }
    @PreAuthorize("hasRole('SISWA')")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public DashboardDtos.Student student(Long student,int page,int size) {
        validatePage(page,size);requireRole(student,Role.SISWA);timeout();Instant now=clock.instant();
        var row=queries.student(student,now).orElseThrow(()->new DashboardFailure(403,"FORBIDDEN","Akun SISWA aktif diperlukan."));
        var history=queries.history(student,size,(long)page*size).stream().map(s->history(s,now)).toList();
        log.atInfo().addKeyValue("event","dashboard.student_loaded").addKeyValue("student_id",student)
                .addKeyValue("page",page).addKeyValue("sessions",history.size()).log("Student dashboard loaded");
        return new DashboardDtos.Student(progress(row),page(history,page,size,row.totalSesi()));
    }
    @PreAuthorize("hasRole('MENTOR')")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public DashboardDtos.Mentor mentor(Long mentor,int reviewPage,int reviewSize,int studentPage,int studentSize) {
        validatePage(reviewPage,reviewSize);validatePage(studentPage,studentSize);requireRole(mentor,Role.MENTOR);timeout();
        // PRD 2 grants Mentors progress access to all SISWA; no assignment relation exists in the MVP schema.
        var queue=reviews.pending(mentor,reviewPage,reviewSize);
        long total=queries.countStudents();
        var students=queries.students(studentSize,(long)studentPage*studentSize,clock.instant()).stream().map(DashboardService::progress).toList();
        log.atInfo().addKeyValue("event","dashboard.mentor_loaded").addKeyValue("mentor_id",mentor)
                .addKeyValue("review_page",reviewPage).addKeyValue("student_page",studentPage)
                .addKeyValue("students",students.size()).log("Mentor dashboard loaded");
        return new DashboardDtos.Mentor(queue.totalElements(),total,queue,page(students,studentPage,studentSize,total));
    }
    private void requireRole(Long id,Role role) {
        if(users.findById(id).filter(u->u.getRole()==role).isEmpty())
            throw new DashboardFailure(403,"FORBIDDEN","Role akun aktif tidak sesuai dengan dashboard.");
    }
    private void timeout() { jdbc.execute("SET LOCAL statement_timeout = '5s'"); }
    private static void validatePage(int page,int size) {
        if(page<0 || page>1_000_000 || size<1 || size>100)
            throw new DashboardFailure(400,"INVALID_PAGE","Page harus 0–1000000 dan size harus 1–100.");
    }
    private static <T> DashboardDtos.Page<T> page(List<T> content,int page,int size,long total) {
        int pages=total==0?0:(int)Math.min(Integer.MAX_VALUE,1+(total-1)/size);
        return new DashboardDtos.Page<>(content,page,size,total,pages);
    }
    private static DashboardDtos.Progress progress(DashboardQueries.StudentProgress s) {
        return new DashboardDtos.Progress(s.siswaId(),s.nama(),s.totalMateri(),s.totalSesi(),s.sesiSelesai(),
                s.totalSesi()-s.sesiSelesai()-s.sesiKedaluwarsa(),s.sesiKedaluwarsa(),s.totalJawaban(),s.jumlahBenar(),
                s.totalJawaban()-s.jumlahBenar(),percentage(s.jumlahBenar(),s.totalJawaban()),
                percentage(s.sesiSelesai(),s.totalSesi()),s.latihanTerakhirAt());
    }
    private static DashboardDtos.History history(DashboardQueries.SessionHistory s,Instant now) {
        String status=s.selesaiAt()!=null?"SELESAI":s.berakhirAt()!=null && !now.isBefore(s.berakhirAt())?"KEDALUWARSA":"BERJALAN";
        return new DashboardDtos.History(s.sesiId(),s.mode(),status,s.mulaiAt(),s.selesaiAt(),s.berakhirAt(),s.jumlahSoal(),
                s.totalJawaban(),s.jumlahBenar(),percentage(s.jumlahBenar(),s.totalJawaban()),
                s.jumlahSoal()==null?null:percentage(s.totalJawaban(),s.jumlahSoal()));
    }
    private static BigDecimal percentage(long value,long total) {
        return total==0?null:BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total),2,RoundingMode.HALF_UP);
    }
}
