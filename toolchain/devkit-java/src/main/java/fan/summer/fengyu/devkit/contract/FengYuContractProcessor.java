package fan.summer.fengyu.devkit.contract;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

import fan.summer.fengyu.sdk.contract.FengYuAiTool;
import fan.summer.fengyu.sdk.contract.FengYuField;
import fan.summer.fengyu.sdk.contract.FengYuRpc;
import fan.summer.fengyu.sdk.contract.FengYuSensitive;

/**
 * The code-first contract extractor (implementation plan §5): reads
 * {@code @FengYuContract} interfaces at compile time and writes the
 * language-neutral contract IR to {@code CLASS_OUTPUT/fengyu-contract/contract.json}
 * (i.e. {@code target/classes/fengyu-contract/contract.json}), where the CLI's
 * Manifest Compiler picks it up.
 *
 * <p>Constraints enforced here (plan §5.3): no bare {@code Map}, no unbounded
 * generics, no recursive or open-polymorphic DTOs — anything unmappable is a
 * compile ERROR, never a silent generic {@code object}. The processor only
 * inspects the compile-time type model: no reflection, no instantiation, no
 * worker code execution.
 *
 * <p>Wiring: bound to the {@code generate-resources} phase with
 * {@code <proc>only</proc>} by code-first plugin POMs (see the Java template),
 * or run implicitly during a normal {@code compile}. The optional
 * {@code -Afengyu.contract.pluginId=<id>} option stamps the plugin id into the
 * IR so the compiler can cross-check it against {@code manifest.base.json}.
 */
@SupportedAnnotationTypes("fan.summer.fengyu.sdk.contract.FengYuContract")
@SupportedOptions("fengyu.contract.pluginId")
public final class FengYuContractProcessor extends AbstractProcessor {

    /** Must stay in lockstep with the CLI's IR_FORMAT_VERSION. */
    public static final int IR_FORMAT_VERSION = 1;

    private static final String IR_RESOURCE = "fengyu-contract/contract.json";
    private static final String RPC_CONTEXT = "fan.summer.fengyu.sdk.RpcContext";

    private Messager messager;
    private Elements elementUtils;
    private Types typeUtils;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.messager = env.getMessager();
        this.elementUtils = env.getElementUtils();
        this.typeUtils = env.getTypeUtils();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;
        var contracts = roundEnv.getElementsAnnotatedWith(
                elementUtils.getTypeElement("fan.summer.fengyu.sdk.contract.FengYuContract"));
        if (contracts.isEmpty()) return false;

        Map<String, Object> methods = new TreeMap<>();
        List<Map<String, Object>> aiTools = new ArrayList<>();
        Map<String, String> origins = new TreeMap<>();

        for (Element contract : contracts) {
            if (contract.getKind() != ElementKind.INTERFACE) {
                error(contract, "@FengYuContract applies only to interfaces: %s", contract);
                continue;
            }
            for (Element member : contract.getEnclosedElements()) {
                if (member.getKind() != ElementKind.METHOD) continue;
                ExecutableElement method = (ExecutableElement) member;
                FengYuRpc rpc = method.getAnnotation(FengYuRpc.class);
                if (rpc == null) {
                    if (!method.getModifiers().contains(Modifier.DEFAULT)
                            && !method.getModifiers().contains(Modifier.STATIC)) {
                        error(method, "contract method %s lacks @FengYuRpc", method.getSimpleName());
                    }
                    continue;
                }
                processMethod(method, rpc, methods, aiTools, origins);
            }
        }

        if (methods.isEmpty()) {
            error(null, "@FengYuContract declared no @FengYuRpc methods");
            return false;
        }
        aiTools.sort(Comparator.comparing(tool -> String.valueOf(tool.get("name"))));

        String pluginId = processingEnv.getOptions().get("fengyu.contract.pluginId");
        Map<String, Object> ir = new LinkedHashMap<>();
        ir.put("formatVersion", IR_FORMAT_VERSION);
        if (pluginId != null && !pluginId.isBlank()) ir.put("pluginId", pluginId);
        ir.put("rpc", Map.of("methods", methods));
        ir.put("aiTools", aiTools);
        ir.put("origins", origins);

        try (Writer writer = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", IR_RESOURCE)
                .openWriter()) {
            writer.write(JsonWriter.write(ir));
        } catch (IOException e) {
            error(null, "cannot write contract IR %s: %s", IR_RESOURCE, e.getMessage());
        }
        return false;
    }

    // ── Method extraction ───────────────────────────────────────────────

    private void processMethod(ExecutableElement method, FengYuRpc rpc,
                               Map<String, Object> methods, List<Map<String, Object>> aiTools,
                               Map<String, String> origins) {
        String name = rpc.name().isBlank() ? method.getSimpleName().toString() : rpc.name();
        if (methods.containsKey(name)) {
            error(method, "duplicate @FengYuRpc name: %s", name);
            return;
        }

        VariableElement inputParam = null;
        for (VariableElement param : method.getParameters()) {
            if (isRpcContext(param.asType())) continue;
            if (inputParam != null) {
                error(method, "%s has two input parameters; at most one input record plus RpcContext is allowed", name);
                return;
            }
            inputParam = param;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        if (!rpc.description().isBlank()) entry.put("description", rpc.description());
        TypeElement inputRecord = recordOf(inputParam == null ? null : inputParam.asType());
        if (inputParam != null && inputRecord == null) {
            error(method, "%s: input parameter %s must be a record",
                    name, inputParam.getSimpleName());
            return;
        }
        entry.put("inputSchema", inputRecord == null
                ? objectSchema()
                : recordSchema(inputRecord, name + ".input", method, new java.util.HashSet<>()));
        TypeMirror returnType = method.getReturnType();
        if (returnType.getKind() != TypeKind.VOID) {
            TypeElement outputRecord = recordOf(returnType);
            if (outputRecord == null) {
                error(method, "%s: return type must be a record (or void)", name);
                return;
            }
            entry.put("outputSchema", recordSchema(outputRecord, name + ".output", method, new java.util.HashSet<>()));
        }
        if (rpc.timeoutSeconds() > 0) entry.put("timeoutSeconds", rpc.timeoutSeconds());
        methods.put(name, entry);
        origins.put("rpc.methods." + name, sourceOf(method));

        FengYuAiTool tool = method.getAnnotation(FengYuAiTool.class);
        if (tool != null) {
            Map<String, Object> toolEntry = new LinkedHashMap<>();
            toolEntry.put("name", tool.name().isBlank() ? name : tool.name());
            toolEntry.put("description", tool.description());
            toolEntry.put("method", name);
            toolEntry.put("effect", tool.effect().name().toLowerCase(java.util.Locale.ROOT));
            if (tool.idempotent()) toolEntry.put("idempotent", true);
            if (tool.timeoutSeconds() > 0) toolEntry.put("timeoutSeconds", tool.timeoutSeconds());
            aiTools.add(toolEntry);
        }
    }

    // ── Type model → JSON-Schema subset ─────────────────────────────────

    /** Maps a record type element to { type: object, properties, required }. */
    private Map<String, Object> recordSchema(TypeElement record, String where, Element site,
                                             java.util.Set<Name> active) {
        if (!active.add(record.getQualifiedName())) {
            error(site, "%s: recursive DTO %s is not supported", where, record.getQualifiedName());
            return mutable("object");
        }
        try {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> properties = new TreeMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponentElement component : recordElements(record)) {
                FengYuField field = component.getAnnotation(FengYuField.class);
                boolean sensitive = component.getAnnotation(FengYuSensitive.class) != null;
                Map<String, Object> prop = typeSchema(component.asType(),
                        where + "." + component.getSimpleName(), site, active);
                if (field != null) {
                    if (!field.title().isBlank()) prop.put("title", field.title());
                    if (!field.description().isBlank()) prop.put("description", field.description());
                    if (field.nullable()) prop.put("nullable", true);
                    if (!Double.isNaN(field.minimum())) prop.put("minimum", field.minimum());
                    if (!Double.isNaN(field.maximum())) prop.put("maximum", field.maximum());
                    if (!field.defaultValue().isEmpty()) {
                        Object defaultValue = scalarDefault(field.defaultValue(), prop, component);
                        if (defaultValue != null) prop.put("default", defaultValue);
                    }
                    if (!field.analyze().isBlank()) prop.put("x-fengyu-analyze", field.analyze());
                    if (field.advanced()) prop.put("x-fengyu-advanced", true);
                    if (!field.optionsFrom().isBlank()) prop.put("x-fengyu-options-from", field.optionsFrom());
                }
                if (sensitive) prop.put("x-fengyu-sensitive", true);
                boolean isRequired = component.asType().getKind().isPrimitive()
                        || (field != null && field.required());
                if (isRequired) required.add(component.getSimpleName().toString());
                properties.put(component.getSimpleName().toString(), prop);
            }
            // `properties` is emitted even when empty so a no-parameter method round-trips
            // the same shape the manifest-first schema carried ("properties": {}).
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                required.sort(String::compareTo);
                schema.put("required", required);
            }
            return schema;
        } finally {
            active.remove(record.getQualifiedName());
        }
    }

    private Map<String, Object> typeSchema(TypeMirror type, String where, Element site,
                                           java.util.Set<Name> active) {
        switch (type.getKind()) {
            case BOOLEAN -> { return mutable("boolean"); }
            case BYTE, SHORT, INT, LONG, CHAR -> { return mutable("integer"); }
            case FLOAT, DOUBLE -> { return mutable("number"); }
            default -> { /* declared types handled below */ }
        }
        Element element = typeUtils.asElement(type);
        if (element == null) {
            error(site, "%s: unsupported type %s (no mapping to the JSON-Schema subset)", where, type);
            return mutable("object");
        }
        if (element.getKind() == ElementKind.ENUM) {
            List<String> values = new ArrayList<>();
            for (Element constant : element.getEnclosedElements()) {
                if (constant.getKind() == ElementKind.ENUM_CONSTANT) {
                    values.add(constant.getSimpleName().toString());
                }
            }
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "string");
            prop.put("enum", values);
            return prop;
        }
        if (element.getKind() == ElementKind.RECORD) {
            return recordSchema((TypeElement) element, where, site, active);
        }
        Name qualified = ((TypeElement) element).getQualifiedName();
        if (qualified.contentEquals("java.lang.String")) return mutable("string");
        for (var wrapper : List.of("java.lang.Integer", "java.lang.Long", "java.lang.Byte",
                "java.lang.Short", "java.lang.Character")) {
            if (qualified.contentEquals(wrapper)) return mutable("integer");
        }
        if (qualified.contentEquals("java.lang.Double")
                || qualified.contentEquals("java.lang.Float")
                || qualified.contentEquals("java.math.BigDecimal")) {
            return mutable("number");
        }
        if (qualified.contentEquals("java.lang.Boolean")) return mutable("boolean");
        if (type instanceof DeclaredType declared
                && ((TypeElement) declared.asElement()).getQualifiedName()
                        .contentEquals("java.util.List")
                && declared.getTypeArguments().size() == 1) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "array");
            prop.put("items", typeSchema(declared.getTypeArguments().getFirst(),
                    where + "[]", site, active));
            return prop;
        }
        error(site, "%s: unsupported type %s — Map, unbounded generics, and polymorphic DTOs " +
                "have no JSON-Schema subset mapping; use a record", where, type);
        return mutable("object");
    }

    private Map<String, Object> objectSchema() {
        // No input record: the method takes only RpcContext — an empty object schema.
        return mutable("object");
    }

    private Object scalarDefault(String value, Map<String, Object> schema, Element site) {
        String type = String.valueOf(schema.get("type"));
        try {
            return switch (type) {
                case "string" -> value;
                case "boolean" -> {
                    if (!"true".equals(value) && !"false".equals(value)) {
                        throw new IllegalArgumentException("expected true or false");
                    }
                    yield Boolean.valueOf(value);
                }
                case "integer" -> Long.valueOf(value);
                case "number" -> Double.valueOf(value);
                default -> throw new IllegalArgumentException(
                        "defaults are supported only for scalar and enum fields");
            };
        } catch (IllegalArgumentException invalid) {
            error(site, "invalid @FengYuField defaultValue '%s' for schema type %s: %s",
                    value, type, invalid.getMessage());
            return null;
        }
    }

    // ── Small helpers ───────────────────────────────────────────────────

    private TypeElement recordOf(TypeMirror type) {
        Element element = typeUtils.asElement(type);
        return element != null && element.getKind() == ElementKind.RECORD ? (TypeElement) element : null;
    }

    private boolean isRpcContext(TypeMirror type) {
        Element element = typeUtils.asElement(type);
        return element instanceof TypeElement typeElement
                && typeElement.getQualifiedName().contentEquals(RPC_CONTEXT);
    }

    private List<RecordComponentElement> recordElements(TypeElement record) {
        List<RecordComponentElement> components = new ArrayList<>();
        for (Element element : record.getEnclosedElements()) {
            if (element.getKind() == ElementKind.RECORD_COMPONENT) {
                components.add((RecordComponentElement) element);
            }
        }
        return components;
    }

    private String sourceOf(Element element) {
        try {
            var trees = com.sun.source.util.Trees.instance(processingEnv);
            var path = trees.getPath(element);
            if (path == null) return String.valueOf(element);
            var positions = trees.getSourcePositions();
            long start = positions.getStartPosition(path.getCompilationUnit(), path.getLeaf());
            long line = path.getCompilationUnit().getLineMap().getLineNumber(start);
            return path.getCompilationUnit().getSourceFile().getName() + ":" + line;
        } catch (RuntimeException e) {
            return String.valueOf(element);
        }
    }

    private void error(Element site, String format, Object... args) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(format, args), site);
    }

    /** A single-property schema map; mutable so callers can layer hints onto it. */
    private static Map<String, Object> mutable(String type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        return schema;
    }
}
