package id.soalku.core;

import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.random.RandomGenerator;

final class SeleksiLatihan {
    private record Skor(KandidatSoal soal,double undian) { }
    private SeleksiLatihan() { }
    static RencanaLatihan pilih(Iterator<KandidatSoal> source,int count,Instant start,RandomGenerator random,
            boolean focus,Duration perQuestion) {
        Objects.requireNonNull(source);Objects.requireNonNull(start);Objects.requireNonNull(random);
        if(count<1 || count>50) throw new IllegalArgumentException("Jumlah soal harus 1–50");
        Comparator<Skor> order=(a,b)->focus?bandingkanRasio(a.soal(),b.soal()):0;
        order=order.thenComparingDouble(Skor::undian).thenComparingLong(s->s.soal().id());
        var selected=new PriorityQueue<Skor>(count,order);
        while(source.hasNext()) {
            var question=source.next();
            if(question.status()!=StatusReview.DISETUJUI) continue;
            selected.add(new Skor(question,random.nextDouble()));
            if(selected.size()>count) selected.poll();
        }
        var ids=selected.stream().sorted(order.reversed()).map(s->s.soal().id()).toList();
        Instant end=perQuestion==null || ids.isEmpty()?null:start.plus(perQuestion.multipliedBy(ids.size()));
        return new RencanaLatihan(ids,start,end);
    }
    private static int bandingkanRasio(KandidatSoal a,KandidatSoal b) {
        // Exact fractions: avoid integer division, rounding near 100%, and long multiplication overflow.
        return BigInteger.valueOf(a.salah()).multiply(BigInteger.valueOf(Math.max(1,b.totalJawaban())))
                .compareTo(BigInteger.valueOf(b.salah()).multiply(BigInteger.valueOf(Math.max(1,a.totalJawaban()))));
    }
}
