package org.argos.argos_backend.analysis.service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.QueryType;
import org.argos.argos_backend.external.document.DocumentFetcher.Document;
import org.argos.argos_backend.external.llm.LlmModels.DraftCrossClaim;
import org.argos.argos_backend.external.search.SearchClient.Hit;
import static org.argos.argos_backend.analysis.domain.ReportingModels.*;

/** Local metadata ranking only: dates and search snippets never establish a factual verdict. */
final class ReportRetrievalPolicy {
    private static final ZoneId NEWS_ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern DATE = Pattern.compile("(?:(20\\d{2})\\s*년\\s*)?(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    private static final Pattern YEARS = Pattern.compile("20\\d{2}(?=\\s*년)");
    private static final Pattern VALUES = Pattern.compile("[-+−]?[0-9]+(?:[.,][0-9]+)*\\s*(?:%\\s*(?:p|포인트)?|퍼센트\\s*포인트|퍼센트|(?:천|만|억|조)?\\s*(?:원|명|건|개|배)|kg|km)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY = Pattern.compile("정정|오보|회고|당시|되짚|과거|종전");
    record Candidate(String id, Hit hit, QueryType type) {}
    record Ranked(Candidate candidate, PrefetchPriority priority) {}
    private record Scored(Candidate candidate, int score, boolean eligible, List<String> reasons) {}
    private ReportRetrievalPolicy() {}

    static String normalizeYears(String text, Instant publishedAt) {
        if (text == null || publishedAt == null) return text;
        int year = publishedAt.atZone(NEWS_ZONE).getYear();
        return text.replaceAll("재작년", (year - 2) + "년")
            .replaceAll("내년도|내년|익년", (year + 1) + "년")
            .replaceAll("올해|금년", year + "년")
            .replaceAll("작년|지난해", (year - 1) + "년").replaceAll("후년", (year + 2) + "년");
    }

    static TemporalContext context(DraftCrossClaim claim, Document article, IssueFrame issue) {
        Instant published = article.publishedAt();
        LocalDate publishedDate = published == null ? null : published.atZone(NEWS_ZONE).toLocalDate();
        String explicit = issue == null || issue.announcementDate() == null ? "" : issue.announcementDate();
        String eventText = explicit.isBlank() ? claim.text() + " " + (issue == null ? "" : issue.originalQuote()) : explicit;
        LocalDate announced = date(normalizeYears(eventText, published), publishedDate);
        String source = announced != null ? "ARTICLE_TEXT" : publishedDate != null ? "PUBLISHED_AT_FALLBACK" : "UNKNOWN";
        if (announced == null) announced = publishedDate;
        LinkedHashSet<String> periods = new LinkedHashSet<>();
        boolean statedPeriod = issue != null && !issue.period().isBlank();
        String periodText = statedPeriod ? normalizeYears(issue.period(), published) : "";
        if (statedPeriod) periods.add(periodText);
        // A single extracted period can omit the second forecast year. Keep claim years, excluding full event dates.
        periodText += " " + DATE.matcher(dateText(normalizeYears(claim.text(), published))).replaceAll("");
        Matcher years = YEARS.matcher(periodText);
        while (years.find()) periods.add(years.group() + "년");
        List<String> values = values(claim.text()).stream().limit(6).toList();
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        if (issue != null) for (String part : List.of(issue.subject(), issue.target(), issue.event(), issue.metric())) {
            if (!part.isBlank()) anchors.add(normalizeYears(part, published));
        }
        return new TemporalContext(published, announced == null ? "" : announced.toString(), source,
            List.copyOf(periods), values, List.copyOf(anchors), action(claim.text()));
    }

    static String query(String original, TemporalContext temporal) {
        String normalized = normalizeYears(original, temporal.originalPublishedAt());
        LinkedHashSet<String> extra = new LinkedHashSet<>();
        if (!temporal.announcementDate().isBlank()) {
            LocalDate date = LocalDate.parse(temporal.announcementDate());
            extra.add(date.getYear() + "년");
            extra.add(date.getMonthValue() + "월 " + date.getDayOfMonth() + "일");
        }
        extra.addAll(temporal.targetPeriods());
        extra.addAll(temporal.numericAnchors());
        // Reserve space for event/date/value anchors instead of appending them after a potentially full query.
        StringBuilder result = new StringBuilder();
        for (String anchor : extra) if (!compact(result.toString()).contains(compact(anchor))
            && result.length() + anchor.length() + 1 <= 210) {
            if (!result.isEmpty()) result.append(' ');
            result.append(anchor);
        }
        for (String token : normalized.split("\\s+")) if (!token.isBlank() && !compact(result.toString()).contains(compact(token))
            && result.length() + token.length() + 1 <= 290) {
            if (!result.isEmpty()) result.append(' ');
            result.append(token);
        }
        return result.toString();
    }

    static List<Ranked> rank(List<Candidate> candidates, TemporalContext temporal, IssueFrame issue) {
        List<Scored> scored = candidates.stream().map(c -> score(c, temporal, issue)).toList();
        // Deduplicate across all routes before assigning the limited collection budget. Keep every occurrence in Trace.
        Map<String, Scored> representatives = new LinkedHashMap<>();
        for (Scored s : scored) {
            String key = key(s.candidate().hit().url());
            Scored before = representatives.get(key);
            if (before == null || s.score() > before.score()) representatives.put(key, s);
        }
        List<Scored> remaining = new ArrayList<>(), excluded = new ArrayList<>();
        for (Scored s : scored) {
            if (representatives.get(key(s.candidate().hit().url())) != s) {
                List<String> reasons = new ArrayList<>(s.reasons()); reasons.add("DUPLICATE_URL");
                excluded.add(new Scored(s.candidate(), s.score(), false, List.copyOf(reasons)));
            } else if (s.eligible()) remaining.add(s); else excluded.add(s);
        }
        List<Ranked> ranked = new ArrayList<>();
        Map<QueryType, Integer> assigned = new EnumMap<>(QueryType.class);
        while (!remaining.isEmpty()) {
            // Small diversity penalty only: a clearly stronger event match always wins over route coverage.
            Scored best = remaining.stream().max(Comparator.comparingInt(s ->
                s.score() - 4 * Math.min(3, assigned.getOrDefault(s.candidate().type(), 0)))).orElseThrow();
            remaining.remove(best);
            int penalty = 4 * Math.min(3, assigned.getOrDefault(best.candidate().type(), 0));
            assigned.merge(best.candidate().type(), 1, Integer::sum);
            List<String> reasons = new ArrayList<>(best.reasons());
            if (penalty > 0) reasons.add("ROUTE_DIVERSITY_PENALTY:" + penalty);
            ranked.add(new Ranked(best.candidate(), new PrefetchPriority(ranked.size() + 1, best.score(), true, List.copyOf(reasons))));
        }
        for (Scored s : excluded) ranked.add(new Ranked(s.candidate(),
            new PrefetchPriority(ranked.size() + 1, s.score(), false, s.reasons())));
        return List.copyOf(ranked);
    }

    private static Scored score(Candidate candidate, TemporalContext temporal, IssueFrame issue) {
        Hit hit = candidate.hit();
        LocalDate publication = publicationDate(hit.dateHint());
        Instant reference = publication == null ? null : publication.atStartOfDay(NEWS_ZONE).toInstant();
        String text = dateText(normalizeYears(hit.title() + " " + hit.description(), reference));
        String flat = compact(text);
        List<String> reasons = new ArrayList<>();
        int score = 0, anchors = 0, values = 0;
        for (String anchor : temporal.eventAnchors()) if (flat.contains(compact(anchor))) anchors++;
        if (anchors > 0) { score += Math.min(24, anchors * 6); reasons.add("EVENT_ANCHOR_MATCH:" + anchors); }
        for (String value : temporal.numericAnchors()) if (flat.contains(compact(value))) values++;
        if (values > 0) { score += Math.min(24, values * 12); reasons.add("CLAIM_VALUE_MATCH:" + values); }
        for (String period : temporal.targetPeriods()) if (flat.contains(compact(period))) {
            score += 5; reasons.add("TARGET_PERIOD_MATCH");
        }
        boolean dateMatch = false, wrongEvent = false;
        if (!temporal.announcementDate().isBlank()) {
            LocalDate event = LocalDate.parse(temporal.announcementDate());
            String monthDay = event.getMonthValue() + "월" + event.getDayOfMonth() + "일";
            boolean yearMatch = flat.contains(event.getYear() + "년") || flat.contains(temporal.announcementDate());
            boolean dayMatch = flat.contains(monthDay) || flat.contains(temporal.announcementDate());
            if (yearMatch) { score += 16; reasons.add("ANNOUNCEMENT_YEAR_MATCH"); }
            if (dayMatch) { score += 18; reasons.add("ANNOUNCEMENT_DAY_MATCH"); }
            Matcher dates = DATE.matcher(text);
            while (dates.find()) {
                if (dates.group(1) == null) continue;
                try {
                    LocalDate mentioned = LocalDate.of(Integer.parseInt(dates.group(1)), Integer.parseInt(dates.group(2)), Integer.parseInt(dates.group(3)));
                    if (mentioned.equals(event)) dateMatch = true;
                    if (!mentioned.equals(event) && text.substring(dates.end(), Math.min(text.length(), dates.end() + 35))
                        .matches("(?s).*(발표|전망|금통위|결정).*")) wrongEvent = true;
                } catch (DateTimeException ignored) { /* Invalid snippets cannot establish an event date. */ }
            }
            if (wrongEvent && !dateMatch) { score -= 45; reasons.add("OTHER_ANNOUNCEMENT_IN_TEXT"); }
            if (publication != null && Math.abs(java.time.temporal.ChronoUnit.DAYS.between(event, publication)) <= 14) {
                score += 3; reasons.add("PUBLICATION_NEAR_EVENT_WEAK_SIGNAL");
            }
        }
        boolean history = HISTORY.matcher(text).find();
        if (history && (anchors > 0 || values > 0 || dateMatch)) { score += 3; reasons.add("CORRECTION_OR_RETROSPECTIVE_RETAINED"); }
        boolean eligible = !wrongEvent || dateMatch || history || values > 0;
        if (!eligible) reasons.add("DIFFERENT_EVENT_WITHOUT_ORIGINAL_ANCHORS");
        if (reasons.isEmpty()) reasons.add("INSUFFICIENT_METADATA_RETAINED");
        return new Scored(candidate, score, eligible, List.copyOf(reasons));
    }

    static String action(String text) {
        List<String> actions = new ArrayList<>();
        if (text.matches("(?s).*(유지|동결).*")) actions.add("MAINTAIN");
        if (text.matches("(?s).*(상향|인상|높였|높이|올렸|올리).*")) actions.add("RAISE");
        if (text.matches("(?s).*(하향|인하|낮췄|낮추|내렸|내리).*")) actions.add("LOWER");
        return actions.isEmpty() ? "NONE" : String.join("+", actions);
    }
    static List<String> values(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = VALUES.matcher(text);
        while (matcher.find()) result.add(matcher.group().strip());
        return List.copyOf(result);
    }
    static boolean sameAction(String expected, String quote) {
        Set<String> observed = Set.of(action(quote).split("\\+"));
        return Arrays.stream(expected.split("\\+")).allMatch(observed::contains)
            && !quote.matches("(?s).*(유지|동결|상향|하향|인상|인하)\\s*(하지\\s*(않|못)|할\\s*수\\s*없|되지\\s*않).*");
    }
    static boolean valuesCovered(String claim, String quote) {
        Set<String> observed = new HashSet<>(values(quote).stream().map(ReportRetrievalPolicy::normalizedValue).toList());
        return values(claim).stream().map(ReportRetrievalPolicy::normalizedValue).allMatch(observed::contains);
    }
    private static String normalizedValue(String value) {
        String compact = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
            .replace('−', '-')
            .replace("퍼센트포인트", "%p").replace("%포인트", "%p").replace("퍼센트", "%");
        Matcher number = Pattern.compile("[-+]?[0-9]+(?:[.,][0-9]+)*").matcher(compact);
        if (!number.find()) return compact;
        try { return new java.math.BigDecimal(number.group().replace(",", "")).stripTrailingZeros().toPlainString() + compact.substring(number.end()); }
        catch (NumberFormatException ignored) { return compact; }
    }
    private static String compact(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replace("퍼센트", "%"); }
    private static String key(String url) {
        try { return AnalysisPipeline.canonical(url); } catch (RuntimeException ignored) { return url; }
    }
    private static LocalDate date(String text, LocalDate reference) {
        Matcher matcher = DATE.matcher(dateText(text));
        while (matcher.find()) {
            if (matcher.group(1) == null && reference == null) continue;
            try {
                return LocalDate.of(matcher.group(1) == null ? reference.getYear() : Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            } catch (DateTimeException ignored) { /* Keep unknown rather than invent an invalid date. */ }
        }
        if (reference != null) {
            Matcher day = Pattern.compile("(?<![0-9])([0-3]?[0-9])\\s*일(?!간|동안|째)").matcher(text);
            if (day.find()) try {
                int value = Integer.parseInt(day.group(1));
                LocalDate month = text.contains("지난") && value > reference.getDayOfMonth() ? reference.minusMonths(1) : reference;
                return month.withDayOfMonth(value);
            } catch (DateTimeException ignored) { /* Invalid day stays unknown. */ }
        }
        return null;
    }
    private static String dateText(String text) {
        return text.replaceAll("(20\\d{2})[-./](\\d{1,2})[-./](\\d{1,2})", "$1년 $2월 $3일");
    }
    private static LocalDate publicationDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); } catch (RuntimeException ignored) {}
        try { return Instant.parse(value).atZone(NEWS_ZONE).toLocalDate(); } catch (RuntimeException ignored) {}
        try { return LocalDateTime.parse(value).toLocalDate(); } catch (RuntimeException ignored) {}
        try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(NEWS_ZONE).toLocalDate(); }
        catch (RuntimeException ignored) { return null; }
    }
}
