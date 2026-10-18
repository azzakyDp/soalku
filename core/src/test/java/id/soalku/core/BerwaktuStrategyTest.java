package id.soalku.core;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BerwaktuStrategyTest {
    @Test void deadlineUsesActualSelectedCountAndConfiguredTimePerQuestion() {
        var strategy=new BerwaktuStrategy(Duration.ofSeconds(30));
        var result=strategy.pilih(AcakStrategyTest.pool().iterator(),50,AcakStrategyTest.START,new Random(2));
        assertEquals(4,result.soalIds().size());assertEquals(AcakStrategyTest.START.plusSeconds(120),result.berakhirAt());
        assertFalse(result.sudahBerakhir(result.berakhirAt().minusNanos(1)));
        assertTrue(result.sudahBerakhir(result.berakhirAt()));assertTrue(result.sudahBerakhir(result.berakhirAt().plusSeconds(1)));
    }
    @Test void timedSelectionUsesSameApprovedRandomSelectionAsAcak() {
        var timed=new BerwaktuStrategy().pilih(AcakStrategyTest.pool().iterator(),3,AcakStrategyTest.START,new Random(8));
        var random=new AcakStrategy().pilih(AcakStrategyTest.pool().iterator(),3,AcakStrategyTest.START,new Random(8));
        assertEquals(random.soalIds(),timed.soalIds());assertEquals(AcakStrategyTest.START.plusSeconds(180),timed.berakhirAt());
    }
    @Test void emptyPoolDoesNotCreateMeaninglessDeadline() {
        var result=new BerwaktuStrategy().pilih(Collections.emptyIterator(),1,AcakStrategyTest.START,new Random(1));
        assertTrue(result.soalIds().isEmpty());assertNull(result.berakhirAt());assertFalse(result.sudahBerakhir(AcakStrategyTest.START.plusSeconds(999)));
    }
    @Test void invalidTimeBudgetsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new BerwaktuStrategy(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,()->new BerwaktuStrategy(Duration.ofSeconds(-1)));
    }
}
