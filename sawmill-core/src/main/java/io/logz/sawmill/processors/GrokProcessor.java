package io.logz.sawmill.processors;

import io.logz.sawmill.Doc;
import io.logz.sawmill.ProcessResult;
import io.logz.sawmill.Processor;
import io.logz.sawmill.annotations.ProcessorProvider;
import io.logz.sawmill.exceptions.ProcessorParseException;
import io.logz.sawmill.utilities.Grok;
import io.logz.sawmill.utilities.JsonUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.EMPTY_LIST;

@ProcessorProvider(type = "grok", factory = GrokProcessor.Factory.class)
public class GrokProcessor implements Processor {
    private final String field;
    private final List<String> expressions;
    private final List<Grok> groks;
    private final List<String> overwrite;
    private final boolean ignoreMissing;

    public GrokProcessor(String field, List<String> matchExpressions, Map<String, String> patternsBank, List<String> overwrite, boolean ignoreMissing) {
        checkState(CollectionUtils.isNotEmpty(matchExpressions), "patterns cannot be empty");
        this.field = checkNotNull(field, "field cannot be null");
        this.expressions = matchExpressions;
        this.overwrite = overwrite != null ? overwrite : EMPTY_LIST;
        this.ignoreMissing = ignoreMissing;

        this.groks = new ArrayList<>();

        compileExpressions(matchExpressions, patternsBank);
    }

    private void compileExpressions(List<String> matchExpressions, Map<String, String> patternsBank) {
        matchExpressions.forEach(expression -> {
            Grok grok = new Grok(patternsBank, expression);
            this.groks.add(grok);
        });

    }

    @Override
    public ProcessResult process(Doc doc) {
        if (!doc.hasField(field, String.class)) {
            if (ignoreMissing) return ProcessResult.success();

            return ProcessResult.failure(String.format("failed to grok field in path [%s], field is missing or not instance of [%s]", field, String.class));
        }

        String value = doc.getField(field);

        Map<String, Object> matches = getMatches(value);

        if (MapUtils.isEmpty(matches)) {
            doc.appendList("tags", "_grokparsefailure");
            return ProcessResult.failure(String.format("failed to grok field [%s] in path [%s], doesn't match any of the expressions [%s]", value, field, expressions));
        }

        matches.entrySet().stream()
                .filter((e) -> Objects.nonNull(e.getValue()))
                .filter((e) -> !e.getValue().toString().isEmpty())
                .forEach((e) -> {
                    if (overwrite.contains(e.getKey()) || !doc.hasField(e.getKey())) {
                        doc.addField(e.getKey(), e.getValue());
                    } else {
                        doc.appendList(e.getKey(), e.getValue());
                    }
                });

        return ProcessResult.success();
    }

    private Map<String, Object> getMatches(String value) {
        for (int i=0; i< groks.size(); i++) {
            Map<String, Object> captures = groks.get(i).captures(value);
            if (MapUtils.isNotEmpty(captures)) {
                return captures;
            }
        }
        return Collections.EMPTY_MAP;
    }

    public static class Factory implements Processor.Factory {
        private static final String[] PATTERN_NAMES = new String[] {
                "gpfs", "grok-patterns", "haproxy",
                "java", "linux-syslog", "mcollective", "mcollective-patterns", "mongodb", "nagios",
                "postgresql", "redis", "ruby", "SYSLOG5424BASEOLDER"
        };

        private final Map<String,String> patternsBank;

        public Factory() {
            this.patternsBank = loadBuiltinPatterns();
        }

        public Factory(String dirPath) {
            File patternsDirectory = new File(dirPath);
            this.patternsBank = loadExternalPatterns(patternsDirectory);
        }

        public Map<String, String> loadBuiltinPatterns() {
            Map<String, String> builtinPatterns = new HashMap<>();
            for (String pattern : PATTERN_NAMES) {
                try(InputStream is = getClass().getResourceAsStream("/grok/patterns/" + pattern)) {
                    loadPatterns(builtinPatterns, is);
                } catch (Exception e) {
                    throw new RuntimeException(String.format("failed to load pattern file [%s]", pattern), e);
                }
            }
            return Collections.unmodifiableMap(builtinPatterns);
        }

        private Map<String,String> loadExternalPatterns(File dir) {
            Map<String,String> externalPatterns = new HashMap<>();
            String[] patternFiles = dir.list();

            for (String patternFileName : patternFiles) {
                try (FileInputStream is = new FileInputStream(dir.getPath() + "/" + patternFileName)){
                    loadPatterns(externalPatterns, is);
                } catch (Exception e) {
                    throw new RuntimeException(String.format("failed to load pattern file [%s]", patternFileName), e);
                }
            }

            return Collections.unmodifiableMap(externalPatterns);
        }

        private void loadPatterns(Map<String, String> patternBank, InputStream inputStream) throws IOException {
            String line;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                while ((line = br.readLine()) != null) {
                    String trimmedLine = line.replaceAll("^\\s+", "");
                    if (trimmedLine.startsWith("#") || trimmedLine.length() == 0) {
                        continue;
                    }

                    String[] parts = trimmedLine.split("\\s+", 2);
                    if (parts.length == 2) {
                        patternBank.put(parts[0], parts[1]);
                    }
                }
            }
        }

        @Override
        public GrokProcessor create(Map<String,Object> config) {
            GrokProcessor.Configuration grokConfig = JsonUtils.fromJsonMap(GrokProcessor.Configuration.class, config);

            if (CollectionUtils.isEmpty(grokConfig.getPatterns())) {
                throw new ProcessorParseException("cannot create grok without any pattern");
            }

            return new GrokProcessor(grokConfig.getField(),
                    grokConfig.getPatterns(),
                    patternsBank,
                    grokConfig.getOverwrite(),
                    grokConfig.getIgnoreMissing());
        }
    }

    public static class Configuration implements Processor.Configuration {
        private String field;
        private List<String> patterns;
        private List<String> overwrite = EMPTY_LIST;
        private boolean ignoreMissing = true;

        public Configuration() { }

        public Configuration(String field, List<String> patterns, List<String> overwrite, boolean ignoreMissing) {
            this.field = field;
            this.patterns = patterns;
            this.overwrite = overwrite;
            this.ignoreMissing = ignoreMissing;
        }

        public String getField() {
            return field;
        }

        public List<String> getPatterns() {
            return patterns;
        }

        public List<String> getOverwrite() {
            return overwrite;
        }

        public boolean getIgnoreMissing() {
            return ignoreMissing;
        }
    }
}
