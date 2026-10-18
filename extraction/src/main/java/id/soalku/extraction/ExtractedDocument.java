package id.soalku.extraction;

import java.util.List;

public record ExtractedDocument(List<Section> sections, List<String> warnings) {
    public ExtractedDocument {
        sections = List.copyOf(sections);
        warnings = List.copyOf(warnings);
    }
    public record Section(String title, String text, List<byte[]> images) {
        public Section { images = List.copyOf(images); }
    }
}
