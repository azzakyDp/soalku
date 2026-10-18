package id.soalku.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoalPilihanGandaTest {
    private final List<OpsiJawaban> three = List.of(new OpsiJawaban("A",1,true),new OpsiJawaban("B",2,false),new OpsiJawaban("C",3,false));
    @Test void sdHasThreeOptionsAndAlwaysAwaitsReview() {
        var soal = new SoalPilihanGanda(Jenjang.SD,"Apa?",three,null);
        assertEquals(3,soal.getOpsi().size());assertEquals(StatusReview.MENUNGGU,soal.getStatusReview());
    }
    @Test void smpMustHaveFourOptions() {
        assertThrows(IllegalArgumentException.class,() -> new SoalPilihanGanda(Jenjang.SMP,"Apa?",three,null));
    }
    @Test void noKeyTwoKeysDuplicateOptionsAndNonContiguousOrderAreRejected() {
        for (var invalid : List.of(
                List.of(new OpsiJawaban("A",1,false),new OpsiJawaban("B",2,false),new OpsiJawaban("C",3,false)),
                List.of(new OpsiJawaban("A",1,true),new OpsiJawaban("B",2,true),new OpsiJawaban("C",3,false)),
                List.of(new OpsiJawaban("A",1,true),new OpsiJawaban(" a ",2,false),new OpsiJawaban("C",3,false)),
                List.of(new OpsiJawaban("A",2,true),new OpsiJawaban("B",2,false),new OpsiJawaban("C",3,false))))
            assertThrows(IllegalArgumentException.class,() -> new SoalPilihanGanda(Jenjang.SD,"Apa?",invalid,null));
    }
}
