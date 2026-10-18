package id.soalku.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FokusKelemahanStrategyTest {
    @Test void prioritizesWrongPercentageRatherThanRawWrongCount() {
        var pool=List.of(new KandidatSoal(1,10,StatusReview.DISETUJUI,200,100),
                new KandidatSoal(2,20,StatusReview.DISETUJUI,2,2),new KandidatSoal(3,30,StatusReview.DISETUJUI,4,3),
                new KandidatSoal(4,20,StatusReview.MENUNGGU,2,2));
        var result=new FokusKelemahanStrategy().pilih(pool.iterator(),3,AcakStrategyTest.START,new Random(1));
        assertEquals(List.of(2L,3L,1L),result.soalIds());assertNull(result.berakhirAt());
    }
    @Test void exhaustedWeakestSectionFallsBackToNextThenUnseenAndNeverRepeats() {
        var pool=List.of(new KandidatSoal(1,1,StatusReview.DISETUJUI,4,4),new KandidatSoal(2,1,StatusReview.DISETUJUI,4,4),
                new KandidatSoal(3,2,StatusReview.DISETUJUI,2,1),new KandidatSoal(4,3,StatusReview.DISETUJUI,0,0));
        var result=new FokusKelemahanStrategy().pilih(pool.iterator(),4,AcakStrategyTest.START,new Random(4));
        assertEquals(Set.of(1L,2L),new HashSet<>(result.soalIds().subList(0,2)));assertEquals(3L,result.soalIds().get(2));assertEquals(4L,result.soalIds().get(3));
    }
    @Test void noHistoryFallsBackToRandomSelectionAcrossSections() {
        var seen=new HashSet<Long>();var strategy=new FokusKelemahanStrategy();
        for(int seed=0;seed<200;seed++) seen.addAll(strategy.pilih(AcakStrategyTest.pool().iterator(),1,AcakStrategyTest.START,new Random(seed)).soalIds());
        assertEquals(Set.of(1L,2L,3L,4L),seen);
    }
    @Test void fractionComparisonHandlesLargeCountsWithoutOverflowOrRoundingTie() {
        var pool=List.of(new KandidatSoal(1,1,StatusReview.DISETUJUI,Long.MAX_VALUE,Long.MAX_VALUE-1),
                new KandidatSoal(2,2,StatusReview.DISETUJUI,Long.MAX_VALUE,Long.MAX_VALUE));
        assertEquals(List.of(2L),new FokusKelemahanStrategy().pilih(pool.iterator(),1,AcakStrategyTest.START,new Random(1)).soalIds());
    }
    @Test void invalidStatisticsCannotEnterSelection() {
        assertThrows(IllegalArgumentException.class,()->new KandidatSoal(1,1,StatusReview.DISETUJUI,1,2));
        assertThrows(IllegalArgumentException.class,()->new KandidatSoal(1,1,StatusReview.DISETUJUI,-1,0));
    }
    @Test void weakestSectionAtEndOfLargeLazySourceIsNotLostToAnEarlyCandidateLimit() {
        var source=java.util.stream.IntStream.rangeClosed(1,10_000)
                .mapToObj(n->new KandidatSoal(n,n,StatusReview.DISETUJUI,1,n==10_000?1:0)).iterator();
        assertEquals(List.of(10_000L),new FokusKelemahanStrategy().pilih(source,1,AcakStrategyTest.START,new Random(4)).soalIds());
    }
}
