package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.Schema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class ContractReader {
    private static final Schema FORMAT = format();

    private ContractReader() { }

    private static Schema format() {
        try (var stream = ContractReader.class.getResourceAsStream("/schema/prompt-contract-v1.schema.json")) {
            if (stream == null) throw new IllegalStateException("Missing contract format schema");
            return SchemaValidation.compile(PromptContract.JSON.readTree(stream));
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    static @Nullable PromptContract read(Path project, Path source, Path file, List<Diagnostic> diagnostics,
                                         Map<String, SourceDocument> ids) {
        String name = project.relativize(file).toString().replace('\\', '/');
        try {
            if (!file.toRealPath().startsWith(source.toRealPath())) throw new IOException("Contract escapes source directory");
            return read(name, Files.readString(file), template -> {
                Path path = file.getParent().resolve(template);
                // Windows otherwise accepts case-only misspellings that fail in Linux or a JAR.
                try (var siblings = Files.list(file.getParent())) {
                    if (siblings.noneMatch(sibling -> sibling.getFileName().toString().equals(template)))
                        throw new IOException("Template name must match its exact case");
                }
                if (!path.toRealPath().getParent().equals(file.getParent().toRealPath()))
                    throw new IOException("Template escapes its contract directory");
                return Files.readString(path);
            }, diagnostics, ids);
        } catch (IOException exception) {
            diagnostics.add(syntax("contract-schema", name, exception));
            return null;
        }
    }

    static @Nullable PromptContract read(String name, String text, PromptTemplateSource templatesSource,
                                         List<Diagnostic> diagnostics, Map<String, SourceDocument> ids) {
        int initialErrors = diagnostics.size();
        SourceDocument document;
        try { document = SourceDocument.read(name, text); }
        catch (IOException exception) {
            diagnostics.add(syntax("contract-schema", name, exception));
            return null;
        }
        var structural = FORMAT.validate(document.root);
        for (var violation : structural) {
            String pointer = violation.getInstanceLocation().toString();
            if (violation.getProperty() != null) pointer += "/" + SourceDocument.escape(violation.getProperty());
            String rule = pointer.startsWith("/input/schema/") || pointer.startsWith("/output/schema/")
                    ? "schema-subset" : "contract-schema";
            diagnostics.add(document.error(rule, pointer, "Contract violates " + violation.getKeyword() + " constraint"));
        }
        if (!structural.isEmpty()) return null;
        String id = document.root.path("id").asText();
        SourceDocument previous = ids.putIfAbsent(id, document);
        if (previous != null) diagnostics.add(document.error("unique-id", "/id", "Duplicate prompt id; first declared in " + previous.file));
        SchemaSubset.check(document, "/input/schema", diagnostics);
        SchemaSubset.check(document, "/output/schema", diagnostics);
        Set<String> declared = new HashSet<>();
        document.root.path("input").path("schema").path("properties").fieldNames().forEachRemaining(declared::add);
        if (declared.contains("contract")) diagnostics.add(document.error("template-parameters", "/input/schema/properties/contract",
                "Input name contract is reserved"));
        Set<String> used = new HashSet<>();
        Map<String, String> templates = new HashMap<>();
        int messageIndex = 0;
        for (var message : document.root.path("messages")) {
            String template = message.path("template").asText();
            String templateName = name.substring(0, name.lastIndexOf('/') + 1) + template;
            String pointer = "/messages/" + messageIndex++ + "/template";
            try {
                String templateText = templatesSource.read(template);
                templates.put(template, templateText);
                var analysis = TemplateParameters.analyze(templateText);
                for (var problem : analysis.problems()) diagnostics.add(new Diagnostic("template-parameters", Diagnostic.Severity.ERROR,
                        templateName, problem.line(), problem.column(), "", problem.message()));
                for (var parameter : analysis.parameters()) {
                    used.add(parameter.name());
                    if (!parameter.name().equals("contract") && !declared.contains(parameter.name()))
                        diagnostics.add(new Diagnostic("template-parameters", Diagnostic.Severity.ERROR, templateName,
                                parameter.line(), parameter.column(), "", "Unknown input: " + parameter.name()));
                }
            } catch (IOException exception) {
                diagnostics.add(document.error("template-exists", pointer, "Template must exist in the contract directory and contain valid UTF-8"));
            }
        }
        for (String parameter : declared) if (!used.contains(parameter)) diagnostics.add(document.error("template-parameters",
                "/input/schema/properties/" + SourceDocument.escape(parameter), "Unused input: " + parameter));
        if (diagnostics.size() != initialErrors) return null;
        return new PromptContract(document.root, templates);
    }

    static Diagnostic syntax(String rule, String name, IOException exception) {
        int line = 1, column = 1;
        if (exception instanceof SourceDocument.Problem problem)
            return new Diagnostic(rule, Diagnostic.Severity.ERROR, name, problem.line, problem.column, "", problem.reason);
        if (exception instanceof JsonProcessingException json && json.getLocation() != null) {
            line = json.getLocation().getLineNr();
            column = json.getLocation().getColumnNr();
        }
        return new Diagnostic(rule, Diagnostic.Severity.ERROR, name, line, column, "",
                "Cannot read a single valid UTF-8 document (" + exception.getClass().getSimpleName() + ")");
    }
}
