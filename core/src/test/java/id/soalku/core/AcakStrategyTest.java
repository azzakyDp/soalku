package id.soalku.core;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcakStrategyTest {
    static final Instant START=Instant.parse("2026-10-18T00:00:00Z");
    static List<KandidatSoal> pool() {
        return List.of(new KandidatSoal(1,1,StatusReview.DISETUJUI,0,0),new KandidatSoal(2,2,StatusReview.DISETUJUI,0,0),
                new KandidatSoal(3,3,StatusReview.DISETUJUI,0,0),new KandidatSoal(4,4,StatusReview.DISETUJUI,0,0),
                new KandidatSoal(5,5,StatusReview.MENUNGGU,0,0),new KandidatSoal(6,6,StatusReview.DITOLAK,0,0));
    }
    @Test void takesOnlyApprovedUniqueQuestionsAndDoesNotMutateSource() {
        var input=pool();var result=new AcakStrategy().pilih(input.iterator(),3,START,new Random(42));
        assertEquals(3,result.soalIds().size());assertEquals(3,new HashSet<>(result.soalIds()).size());
        assertTrue(Set.of(1L,2L,3L,4L).containsAll(result.soalIds()));assertEquals(pool(),input);assertNull(result.berakhirAt());
    }
    @Test void randomnessReachesAllSectionsAndIsReproducibleWithSeed() {
        var seen=new HashSet<Long>();var strategy=new AcakStrategy();
        for(int seed=0;seed<200;seed++) seen.addAll(strategy.pilih(pool().iterator(),1,START,new Random(seed)).soalIds());
        assertEquals(Set.of(1L,2L,3L,4L),seen);
        assertEquals(strategy.pilih(pool().iterator(),3,START,new Random(17)),strategy.pilih(pool().iterator(),3,START,new Random(17)));
    }
    @Test void insufficientPoolReturnsAllAvailableAndEmptyPoolIsEmpty() {
        var strategy=new AcakStrategy();assertEquals(4,strategy.pilih(pool().iterator(),50,START,new Random(1)).soalIds().size());
        assertTrue(strategy.pilih(Collections.emptyIterator(),2,START,new Random(1)).soalIds().isEmpty());
    }
    @Test void countIsBounded() {
        for(int count:List.of(0,-1,51)) assertThrows(IllegalArgumentException.class,()->new AcakStrategy().pilih(pool().iterator(),count,START,new Random(1)));
    }
}
