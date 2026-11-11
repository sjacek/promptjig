package io.github.sjacek.promptjig.core;

import org.antlr.runtime.tree.Tree;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.compiler.STParser;
import org.stringtemplate.v4.compiler.STException;
import org.stringtemplate.v4.misc.ErrorBuffer;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** ST4 4.3.4 AST adapter; all branches are inspected without render-time values. */
public final class TemplateParameters {
    /**
     * First occurrence of a free input reference; property names and anonymous-template locals are excluded.
     * @param name root input name
     * @param line one-based source line
     * @param column one-based source column
     */
    public record Parameter(String name, int line, int column) { }

    /**
     * Syntax or unsupported-template finding, without a copy of the template text.
     * @param line one-based source line
     * @param column one-based source column
     * @param message safe explanation of the failure
     */
    public record Problem(int line, int column, String message) { }

    /**
     * AST analysis returned without rendering the template.
     * @param parameters first free references, sorted by name when produced by {@link #analyze(String)}
     * @param problems syntax and unsupported-call findings; nonempty means the input set cannot be trusted
     */
    public record Analysis(List<Parameter> parameters, List<Problem> problems) { }

    private TemplateParameters() { }

    /**
     * Extracts root input names from all branches using curly-brace delimiters.
     * @param template authored ST4 text
     * @return unmodifiable set of free input names, including the reserved {@code contract} when referenced
     * @throws IllegalArgumentException if the template has syntax errors or external template calls
     */
    public static Set<String> read(String template) {
        Analysis analysis = analyze(template);
        if (!analysis.problems().isEmpty()) throw new IllegalArgumentException(analysis.problems().toString());
        return analysis.parameters().stream().map(Parameter::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Inspects ST4 syntax and all AST branches, preserving the first location of each free reference.
     * No input values are needed and no template calls are executed.
     * @param template authored ST4 text
     * @return collected references and safe diagnostics with one-based source coordinates
     */
    public static Analysis analyze(String template) {
        var errors = new ErrorBuffer();
        var group = new STGroup('{', '}');
        group.setListener(errors);
        ST compiled = null;
        try {
            compiled = new ST(group, template);
        } catch (STException ignored) { /* Locations are retained by the listener. */ }
        List<Problem> problems = new ArrayList<>();
        for (var error : errors.errors) {
            var location = Pattern.compile("(\\d+):(\\d+)").matcher(error.toString());
            int line = 1, column = 1;
            if (location.find()) { line = Integer.parseInt(location.group(1)); column = Integer.parseInt(location.group(2)) + 1; }
            problems.add(new Problem(line, column, "Invalid ST4 template syntax"));
        }
        if (compiled == null && problems.isEmpty()) problems.add(new Problem(1, 1, "Invalid ST4 template syntax"));
        Map<String, Parameter> result = new HashMap<>();
        if (compiled != null && problems.isEmpty()) walk(compiled.impl.ast, Set.of(), result, problems);
        return new Analysis(result.values().stream().sorted(java.util.Comparator.comparing(Parameter::name)).toList(), List.copyOf(problems));
    }

    private static void walk(@Nullable Tree node, Set<String> locals, Map<String, Parameter> result, List<Problem> problems) {
        if (node == null) return;
        int type = node.getType();
        if (type == STParser.ID) {
            if (!locals.contains(node.getText())) result.putIfAbsent(node.getText(),
                    new Parameter(node.getText(), Math.max(1, node.getLine()), node.getCharPositionInLine() + 1));
            return;
        }
        // Properties are literal names, whereas the object and computed properties are expressions.
        if (type == STParser.PROP) {
            walk(node.getChild(0), locals, result, problems);
            return;
        }
        if (type == STParser.SUBTEMPLATE) {
            Set<String> scope = new HashSet<>(locals);
            scope.addAll(Set.of("i", "i0"));
            for (int i = 0; i < node.getChildCount(); i++) {
                Tree child = node.getChild(i);
                if (child.getType() == STParser.ARGS) {
                    for (int j = 0; j < child.getChildCount(); j++) scope.add(child.getChild(j).getText());
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                if (node.getChild(i).getType() != STParser.ARGS) walk(node.getChild(i), scope, result, problems);
            }
            return;
        }
        if (type == STParser.INCLUDE || type == STParser.INCLUDE_IND || type == STParser.INCLUDE_REGION
                || type == STParser.INCLUDE_SUPER || type == STParser.INCLUDE_SUPER_REGION) {
            problems.add(new Problem(Math.max(1, node.getLine()), node.getCharPositionInLine() + 1,
                    "External template calls are not supported in contract format v1"));
            return;
        }
        // A function name or an option name is not an input parameter.
        int first = type == STParser.EXEC_FUNC || type == STParser.EQUALS ? 1 : 0;
        for (int i = first; i < node.getChildCount(); i++) walk(node.getChild(i), locals, result, problems);
    }
}
