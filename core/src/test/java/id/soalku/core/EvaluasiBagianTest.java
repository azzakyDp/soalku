package id.soalku.core;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvaluasiBagianTest {
    @Test void percentageUsesAllAttemptsAndRoundsToTwoDecimals() {
        var result=new EvaluasiBagian(1,"Akar",3,1);
        assertEquals(new BigDecimal("33.33"),result.persentaseBenar());assertEquals(2,result.jumlahSalah());assertTrue(result.perluDiulang());
        assertEquals(new BigDecimal("66.67"),new EvaluasiBagian(1,"Akar",3,2).persentaseBenar());
    }
    @Test void perfectAndZeroScoresHaveMeaningfulRecommendations() {
        var perfect=new EvaluasiBagian(1,"Daun",4,4);
        assertEquals(new BigDecimal("100.00"),perfect.persentaseBenar());assertFalse(perfect.perluDiulang());
        var zero=new EvaluasiBagian(1,"Daun",4,0);
        assertEquals(new BigDecimal("0.00"),zero.persentaseBenar());assertTrue(zero.perluDiulang());
    }
    @Test void largeCountsDoNotOverflowPercentageCalculation() {
        assertEquals(new BigDecimal("100.00"),new EvaluasiBagian(1,"Akar",Long.MAX_VALUE,Long.MAX_VALUE).persentaseBenar());
    }
    @Test void absentOrInconsistentStatisticsAreNotReportedAsScores() {
        assertThrows(IllegalArgumentException.class,()->new EvaluasiBagian(1,"Akar",0,0));
        assertThrows(IllegalArgumentException.class,()->new EvaluasiBagian(1,"Akar",2,3));
        assertThrows(IllegalArgumentException.class,()->new EvaluasiBagian(1,"Akar",2,-1));
    }
}
