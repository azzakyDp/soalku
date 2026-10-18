package id.soalku.core;

import java.time.Instant;
import java.util.Iterator;
import java.util.random.RandomGenerator;

public final class AcakStrategy implements LatihanStrategy {
    @Override public ModeLatihan mode() { return ModeLatihan.ACAK; }
    @Override public RencanaLatihan pilih(Iterator<KandidatSoal> source,int count,Instant start,RandomGenerator random) {
        return SeleksiLatihan.pilih(source,count,start,random,false,null);
    }
}
