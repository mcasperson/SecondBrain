package secondbrain.domain.limit;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * A service that returns the sections of a document that contain the keywords,
 * with each section being a block of characters before and after each keyword.
 * Keywords are case-insensitive and require an exact match.
 */
@ApplicationScoped
public class DocumentTrimmerExactKeywords implements DocumentTrimmer {

    @Override
    public TrimResult trimDocumentToKeywords(final String document, final List<String> keywords, final int sectionLength) {
        if (document == null || document.isEmpty()) {
            return new TrimResult();
        }

        // Empty keywords or a non-positive section length will return the entire document
        if (keywords.isEmpty() || sectionLength <= 0) {
            return new TrimResult(document, List.of());
        }

        final List<Section> keywordPositions = getAllKeywordPositions(document, keywords)
                .stream()
                .flatMap(position -> position.toSections(sectionLength, document.length()).stream())
                .toList();

        final List<Section> mergedSections = mergeSections(keywordPositions);

        if (mergedSections.isEmpty()) {
            return new TrimResult();
        }

        final String trimmedDocument = String.join(" ",
                mergedSections
                        .stream()
                        .map(section -> document.substring(section.start(), section.end()).trim())
                        .toList());

        final List<String> keywordMatches = mergedSections
                .stream()
                .flatMap(section -> section.keyword().stream())
                .distinct()
                .toList();

        return new TrimResult(trimmedDocument, keywordMatches);
    }

    /**
     * Merges overlapping sections.
     *
     * @param sections The sections to merge
     * @return The merged sections
     */
    public List<Section> mergeSections(final List<Section> sections) {
        final List<Section> mergedSections = new ArrayList<>();
        final List<Section> sectionsCopy = new ArrayList<>(sections);

        while (!sectionsCopy.isEmpty()) {

            final Section currentSection = sectionsCopy.getFirst();
            sectionsCopy.removeFirst();

            final List<Section> overlaps = sectionsCopy
                    .stream()
                    .filter(nextSection ->
                            // The current section overlaps the start of the next section
                            (currentSection.start() <= nextSection.start() && currentSection.end() >= nextSection.start())
                                    // The current section overlaps the end of the next section
                                    || (currentSection.end() >= nextSection.end() && currentSection.start() <= nextSection.end())
                                    // The current section is contained within the next section
                                    || (currentSection.start() >= nextSection.start() && currentSection.end() <= nextSection.end()))
                    .toList();

            final Optional<Section> largestEnd = overlaps.stream()
                    .max(Comparator.comparingInt(Section::end));

            final Optional<Section> smallestStart = overlaps.stream()
                    .min(Comparator.comparingInt(Section::start));

            // The results need to be sorted, so use a TreeSet
            final Set<String> keywords = new TreeSet<>(overlaps
                    .stream()
                    .flatMap(section -> section.keyword().stream())
                    .toList());
            keywords.addAll(currentSection.keyword());

            if (!overlaps.isEmpty()) {
                mergedSections.add(
                        new Section(
                                Math.min(currentSection.start(), smallestStart.get().start()),
                                Math.max(currentSection.end(), largestEnd.get().end()),
                                keywords));
                sectionsCopy.removeAll(overlaps);
            } else {
                mergedSections.add(currentSection);
            }
        }

        // Return a sorted list of merged sections
        return mergedSections
                .stream()
                .sorted(Comparator.comparingInt(Section::start))
                .toList();
    }

    private List<KeywordPositions> getAllKeywordPositions(final String document, final List<String> keywords) {
        final List<String> filteredKeywords = keywords.stream()
                .filter(keyword -> !StringUtils.isBlank(keyword))
                .toList();
        final String lowerCaseDocument = document.toLowerCase();
        final List<KeywordPositions> keywordPositions = new ArrayList<>();

        for (String keyword : filteredKeywords) {
            final List<Integer> positions = new ArrayList<>();
            int position = lowerCaseDocument.indexOf(keyword.toLowerCase());
            while (position != -1) {
                // Keywords must be whole words. Otherwise keywords like "eks" match things like "weeks".
                if (isWholeWord(document, keyword, position)) {
                    positions.add(position);
                }
                position = lowerCaseDocument.indexOf(keyword.toLowerCase(), position + 1);
            }

            if (!positions.isEmpty()) {
                keywordPositions.add(new KeywordPositions(keyword, positions));
            }
        }
        return keywordPositions;
    }

    public boolean isWholeWord(final String document, final String keyword, final int position) {
        if (!(position <= 0 || !StringUtils.isAlphanumeric(document.substring(position - 1, position)))) {
            return false;
        }

        return position + keyword.length() >= document.length() || !StringUtils.isAlphanumeric(document.substring(position + keyword.length(), position + keyword.length() + 1));
    }
}

record KeywordPositions(String keyword, List<Integer> positions) {
    List<Section> toSections(final int width, final int documentLength) {
        return positions.stream()
                .map(position -> new Section(
                        // Look back 25% of the width
                        Math.max(0, position - (int) (width * 0.25)),
                        // look forward 75% of the width. This assumes most of the content associated with a keyword is after the keyword
                        Math.min(position + (int) (width * 0.75), documentLength),
                        Set.of(keyword)))
                .toList();
    }
}

