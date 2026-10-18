package id.soalku.core;

import java.time.*;
import java.util.Iterator;
import java.util.random.RandomGenerator;

public final class BerwaktuStrategy implements LatihanStrategy {
    private final Duration perQuestion;
    public BerwaktuStrategy() { this(Duration.ofSeconds(60)); }
    public BerwaktuStrategy(Duration perQuestion) {
        if(perQuestion==null || perQuestion.isNegative() || perQuestion.isZero() || perQuestion.compareTo(Duration.ofMinutes(10))>0)
            throw new IllegalArgumentException("Batas waktu per soal harus positif dan maksimal 10 menit");
        this.perQuestion=perQuestion;
    }
    @Override public ModeLatihan mode() { return ModeLatihan.BERWAKTU; }
    @Override public RencanaLatihan pilih(Iterator<KandidatSoal> source,int count,Instant start,RandomGenerator random) {
        return SeleksiLatihan.pilih(source,count,start,random,false,perQuestion);
    }
}
