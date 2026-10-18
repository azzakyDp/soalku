package id.soalku.core;

import java.time.Instant;
import java.util.Iterator;
import java.util.random.RandomGenerator;

public interface LatihanStrategy {
    ModeLatihan mode();
    /** Candidates have unique IDs; consumption is single-pass and retained memory is bounded by jumlah (1–50). */
    RencanaLatihan pilih(Iterator<KandidatSoal> kandidat,int jumlah,Instant mulai,RandomGenerator random);
}
