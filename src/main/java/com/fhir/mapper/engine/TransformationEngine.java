package com.fhir.mapper.engine;

import com.fhir.mapper.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.jexl3.*;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.*;

/**
 * Core transformation engine for JSON <-> FHIR conversion
 */
public class TransformationEngine {
    private final JexlEngine jexlEngine;
    private final ObjectMapper objectMapper;
    private final PathNavigator pathNavigator;
    private final ValidationEngine validationEngine;
    private final MappingRegistry mappingRegistry;
    private final FhirContext fhirContext;

    public TransformationEngine(MappingRegistry mappingRegistry) {
        this(mappingRegistry, FhirContext.forR4());
    }

    public TransformationEngine(MappingRegistry mappingRegistry, FhirContext fhirContext) {
        this.jexlEngine = new JexlBuilder()
            .namespaces(Map.of("fn", new TransformFunctions()))
            .create();
        this.objectMapper = new ObjectMapper();
        this.pathNavigator = new PathNavigator();
        this.validationEngine = new ValidationEngine();
        this.mappingRegistry = mappingRegistry;
        this.fhirContext = fhirContext;
    }

    // ============================================================================
    // JSON to FHIR Transformations
    // ============================================================================

    /**
     * Transform JSON string to FHIR Map
     */
    public Map<String, Object> jsonToFhirMap(String jsonString, ResourceMapping mapping,
                                             TransformationContext context) throws Exception {
        validateDirection(mapping, MappingDirection.JSON_TO_FHIR);
        Map<String, Object> source = objectMapper.readValue(jsonString, Map.class);
        return performTransformation(source, mapping, context);
    }

    /**
     * Transform JSON Map to FHIR Map
     */
    public Map<String, Object> jsonToFhirMap(Map<String, Object> jsonMap, ResourceMapping mapping,
                                             TransformationContext context) throws Exception {
        validateDirection(mapping, MappingDirection.JSON_TO_FHIR);
        return performTransformation(jsonMap, mapping, context);
    }

    /**
     * Transform POJO to FHIR Map
     */
    public Map<String, Object> jsonToFhirMap(Object pojo, ResourceMapping mapping,
                                             TransformationContext context) throws Exception {
        validateDirection(mapping, MappingDirection.JSON_TO_FHIR);
        Map<String, Object> source = objectMapper.convertValue(pojo, Map.class);
        return performTransformation(source, mapping, context);
    }

    /**
     * Transform JSON string to FHIR JSON string
     */
    public String jsonToFhirJson(String jsonString, ResourceMapping mapping,
                                 TransformationContext context) throws Exception {
        Map<String, Object> fhirMap = jsonToFhirMap(jsonString, mapping, context);
        return objectMapper.writeValueAsString(fhirMap);
    }

    /**
     * Transform JSON Map to FHIR JSON string
     */
    public String jsonToFhirJson(Map<String, Object> jsonMap, ResourceMapping mapping,
                                 TransformationContext context) throws Exception {
        Map<String, Object> fhirMap = jsonToFhirMap(jsonMap, mapping, context);
        return objectMapper.writeValueAsString(fhirMap);
    }

    /**
     * Transform POJO to FHIR JSON string
     */
    public String jsonToFhirJson(Object pojo, ResourceMapping mapping,
                                 TransformationContext context) throws Exception {
        Map<String, Object> fhirMap = jsonToFhirMap(pojo, mapping, context);
        return objectMapper.writeValueAsString(fhirMap);
    }

    /**
     * Transform JSON string to HAPI FHIR Resource
     */
    public <T extends IBaseResource> T jsonToFhirResource(String jsonString, ResourceMapping mapping,
                                                          TransformationContext context,
                                                          Class<T> resourceClass) throws Exception {
        String fhirJson = jsonToFhirJson(jsonString, mapping, context);
        IParser parser = fhirContext.newJsonParser();
        return parser.parseResource(resourceClass, fhirJson);
    }

    /**
     * Transform JSON Map to HAPI FHIR Resource
     */
    public <T extends IBaseResource> T jsonToFhirResource(Map<String, Object> jsonMap, 
                                                          ResourceMapping mapping,
                                                          TransformationContext context,
                                                          Class<T> resourceClass) throws Exception {
        String fhirJson = jsonToFhirJson(jsonMap, mapping, context);
        IParser parser = fhirContext.newJsonParser();
        return parser.parseResource(resourceClass, fhirJson);
    }

    /**
     * Transform POJO to HAPI FHIR Resource
     */
    public <T extends IBaseResource> T jsonToFhirResource(Object pojo, ResourceMapping mapping,
                                                          TransformationContext context,
                                                          Class<T> resourceClass) throws Exception {
        String fhirJson = jsonToFhirJson(pojo, mapping, context);
        IParser parser = fhirContext.newJsonParser();
        return parser.parseResource(resourceClass, fhirJson);
    }

    // ============================================================================
    // FHIR to JSON Transformations
    // ============================================================================

    /**
     * Transform FHIR JSON string to JSON Map
     */
    public Map<String, Object> fhirToJsonMap(String fhirJson, ResourceMapping mapping,
                                             TransformationContext context) throws Exception {
        validateDirection(mapping, MappingDirection.FHIR_TO_JSON);
        Map<String, Object> source = objectMapper.readValue(fhirJson, Map.class);
        return performTransformation(source, mapping, context);
    }

    /**
     * Transform FHIR Map to JSON Map
     */
    public Map<String, Object> fhirToJsonMap(Map<String, Object> fhirMap, ResourceMapping mapping,
                                             TransformationContext context) throws Exception {
        validateDirection(mapping, MappingDirection.FHIR_TO_JSON);
        return performTransformation(fhirMap, mapping, context);
    }

    /**
     * Transform HAPI FHIR Resource to JSON Map
     */
    public Map<String, Object> fhirToJsonMap(IBaseResource resource, ResourceMapping mapping,
                                             TransformationContext context) throws Exception {
        IParser parser = fhirContext.newJsonParser();
        String fhirJson = parser.encodeResourceToString(resource);
        return fhirToJsonMap(fhirJson, mapping, context);
    }

    /**
     * Transform FHIR JSON string to JSON string
     */
    public String fhirToJsonString(String fhirJson, ResourceMapping mapping,
                                   TransformationContext context) throws Exception {
        Map<String, Object> jsonMap = fhirToJsonMap(fhirJson, mapping, context);
        return objectMapper.writeValueAsString(jsonMap);
    }

    /**
     * Transform FHIR Map to JSON string
     */
    public String fhirToJsonString(Map<String, Object> fhirMap, ResourceMapping mapping,
                                   TransformationContext context) throws Exception {
        Map<String, Object> jsonMap = fhirToJsonMap(fhirMap, mapping, context);
        return objectMapper.writeValueAsString(jsonMap);
    }

    /**
     * Transform HAPI FHIR Resource to JSON string
     */
    public String fhirToJsonString(IBaseResource resource, ResourceMapping mapping,
                                   TransformationContext context) throws Exception {
        Map<String, Object> jsonMap = fhirToJsonMap(resource, mapping, context);
        return objectMapper.writeValueAsString(jsonMap);
    }

    /**
     * Transform FHIR JSON string to POJO
     */
    public <T> T fhirToJsonObject(String fhirJson, ResourceMapping mapping,
                                  TransformationContext context, Class<T> targetClass) throws Exception {
        Map<String, Object> jsonMap = fhirToJsonMap(fhirJson, mapping, context);
        return objectMapper.convertValue(jsonMap, targetClass);
    }

    /**
     * Transform FHIR Map to POJO
     */
    public <T> T fhirToJsonObject(Map<String, Object> fhirMap, ResourceMapping mapping,
                                  TransformationContext context, Class<T> targetClass) throws Exception {
        Map<String, Object> jsonMap = fhirToJsonMap(fhirMap, mapping, context);
        return objectMapper.convertValue(jsonMap, targetClass);
    }

    /**
     * Transform HAPI FHIR Resource to POJO
     */
    public <T> T fhirToJsonObject(IBaseResource resource, ResourceMapping mapping,
                                  TransformationContext context, Class<T> targetClass) throws Exception {
        Map<String, Object> jsonMap = fhirToJsonMap(resource, mapping, context);
        return objectMapper.convertValue(jsonMap, targetClass);
    }

    // ============================================================================
    // Internal Methods
    // ============================================================================

    /**
     * Validate mapping direction
     */
    private void validateDirection(ResourceMapping mapping, MappingDirection expectedDirection) {
        if (mapping.getDirection() != expectedDirection) {
            throw new TransformationException(
                "Invalid mapping direction. Expected " + expectedDirection + 
                " but got " + mapping.getDirection());
        }
    }

    /**
     * Perform the actual transformation
     */
    private Map<String, Object> performTransformation(Map<String, Object> source,
                                                      ResourceMapping mapping,
                                                      TransformationContext context) throws Exception {
        Map<String, Object> target = new LinkedHashMap<>();
        
        // Set resourceType for FHIR output
        if (mapping.getDirection() == MappingDirection.JSON_TO_FHIR) {
            target.put("resourceType", mapping.getTargetType());
        }

        for (FieldMapping fieldMapping : mapping.getFieldMappings()) {
            try {
                processMapping(source, target, fieldMapping, context);
            } catch (Exception e) {
                if (fieldMapping.isRequired()) {
                    throw new TransformationException(
                        "Failed to map required field: " + fieldMapping.getId(), e);
                }
            }
        }

        return target;
    }

    /**
     * Process individual field mapping
     */
    private void processMapping(Map<String, Object> source, Map<String, Object> target, 
                                FieldMapping mapping, TransformationContext context) {
        // Check condition with context
        if (mapping.getCondition() != null && 
            !evaluateCondition(mapping.getCondition(), source, context)) {
            return;
        }

        String sourcePath = mapping.getSourcePath();
        String targetPath = mapping.getTargetPath();

        Object value = null;

        // Extract value from source if path exists
        if (sourcePath != null) {
            value = pathNavigator.getValue(source, sourcePath);
        }

        // Apply default value (supports context variables)
        if (value == null && mapping.getDefaultValue() != null) {
            value = resolveValue(mapping.getDefaultValue(), context);
        }

        // Skip if still null and not required
        if (value == null) {
            if (mapping.isRequired()) {
                throw new TransformationException("Required field missing: " + 
                    (sourcePath != null ? sourcePath : mapping.getId()));
            }
            return;
        }

        // Apply lookup if specified
        if (mapping.getLookupTable() != null) {
            value = applyLookup(value, mapping.getLookupTable());
        }

        // Transform with context
        if (mapping.getTransformExpression() != null) {
            value = applyTransform(value, mapping.getTransformExpression(), source, context);
        }

        // Validate
        if (mapping.getValidator() != null) {
            validationEngine.validate(value, mapping.getValidator(), mapping.getId());
        }

        // Set value in target
        pathNavigator.setValue(target, targetPath, value);
    }

    /**
     * Resolve value that may contain context variables
     */
    private Object resolveValue(String value, TransformationContext context) {
        if (value == null || !value.startsWith("$ctx.")) {
            return value;
        }

        String path = value.substring(5); // Remove "$ctx."
        
        // Handle settings map
        if (path.startsWith("settings['") || path.startsWith("settings[\"")) {
            int endIdx = path.lastIndexOf("']");
            if (endIdx == -1) endIdx = path.lastIndexOf("\"]");
            String key = path.substring(10, endIdx);
            return context.getSettings().get(key);
        }

        // Handle direct properties
        switch (path) {
            case "organizationId": return context.getOrganizationId();
            case "facilityId": return context.getFacilityId();
            case "tenantId": return context.getTenantId();
            default: return context.getVariable(path);
        }
    }

    /**
     * Apply code lookup (supports bidirectional)
     */
    private Object applyLookup(Object value, String lookupTableId) {
        if (value == null) return null;

        CodeLookupTable lookupTable = mappingRegistry.getLookupTable(lookupTableId);
        if (lookupTable == null) {
            throw new TransformationException("Lookup table not found: " + lookupTableId);
        }

        String code = value.toString();
        String result = lookupTable.lookupTarget(code);
        
        if (result == null) {
            throw new TransformationException(
                "No mapping found for code '" + code + "' in lookup: " + lookupTableId);
        }
        
        return result;
    }

    /**
     * Evaluate JEXL condition with context
     */
    private boolean evaluateCondition(String condition, Map<String, Object> source,
                                      TransformationContext context) {
        try {
            // Replace $ctx variables
            String resolved = resolveContextInExpression(condition, context);
            
            JexlExpression expr = jexlEngine.createExpression(resolved);
            JexlContext jexlContext = new MapContext(source);
            Object result = expr.evaluate(jexlContext);
            return result != null && (Boolean) result;
        } catch (Exception e) {
            throw new TransformationException("Condition evaluation failed: " + condition, e);
        }
    }

    /**
     * Apply JEXL transformation with context
     */
    private Object applyTransform(Object value, String expression, 
                                  Map<String, Object> source, TransformationContext context) {
        try {
            // Replace $ctx variables
            String resolved = resolveContextInExpression(expression, context);
            
            JexlExpression expr = jexlEngine.createExpression(resolved);
            JexlContext jexlContext = new MapContext(source);
            jexlContext.set("value", value);
            return expr.evaluate(jexlContext);
        } catch (Exception e) {
            throw new TransformationException("Transform failed: " + expression, e);
        }
    }

    /**
     * Replace $ctx variables in expressions
     */
    private String resolveContextInExpression(String expression, TransformationContext context) {
        if (expression == null || !expression.contains("$ctx.")) {
            return expression;
        }

        String result = expression;
        
        // Replace simple context variables
        if (context.getOrganizationId() != null) {
            result = result.replace("$ctx.organizationId", "'" + context.getOrganizationId() + "'");
        }
        if (context.getFacilityId() != null) {
            result = result.replace("$ctx.facilityId", "'" + context.getFacilityId() + "'");
        }
        if (context.getTenantId() != null) {
            result = result.replace("$ctx.tenantId", "'" + context.getTenantId() + "'");
        }

        // Handle settings (more complex parsing needed for production)
        // For now, handle simple cases
        for (Map.Entry<String, String> entry : context.getSettings().entrySet()) {
            String placeholder = "$ctx.settings['" + entry.getKey() + "']";
            result = result.replace(placeholder, "'" + entry.getValue() + "'");
        }

        return result;
    }
}

/**
 * Navigate nested map structures using path notation
 */
class PathNavigator {
    
    public Object getValue(Map<String, Object> data, String path) {
        String[] parts = path.split("\\.");
        Object current = data;

        for (String part : parts) {
            if (current == null) return null;

            if (part.contains("[")) {
                int bracketIdx = part.indexOf('[');
                String key = part.substring(0, bracketIdx);
                int index = Integer.parseInt(part.substring(bracketIdx + 1, part.length() - 1));

                current = ((Map<String, Object>) current).get(key);
                if (current instanceof List) {
                    List list = (List) current;
                    current = index < list.size() ? list.get(index) : null;
                }
            } else {
                current = ((Map<String, Object>) current).get(part);
            }
        }

        return current;
    }

    public void setValue(Map<String, Object> data, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = data;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];

            if (part.contains("[")) {
                int bracketIdx = part.indexOf('[');
                String key = part.substring(0, bracketIdx);
                int index = Integer.parseInt(part.substring(bracketIdx + 1, part.length() - 1));

                current.putIfAbsent(key, new ArrayList<>());
                List list = (List) current.get(key);

                while (list.size() <= index) {
                    list.add(new LinkedHashMap<String, Object>());
                }

                current = (Map<String, Object>) list.get(index);
            } else {
                current.putIfAbsent(part, new LinkedHashMap<String, Object>());
                current = (Map<String, Object>) current.get(part);
            }
        }

        String lastPart = parts[parts.length - 1];
        if (lastPart.contains("[")) {
            int bracketIdx = lastPart.indexOf('[');
            String key = lastPart.substring(0, bracketIdx);
            int index = Integer.parseInt(lastPart.substring(bracketIdx + 1, lastPart.length() - 1));

            current.putIfAbsent(key, new ArrayList<>());
            List list = (List) current.get(key);
            while (list.size() <= index) {
                list.add(null);
            }
            list.set(index, value);
        } else {
            current.put(lastPart, value);
        }
    }
}

/**
 * Custom JEXL functions for transformations
 */
class TransformFunctions {
    public String uppercase(String value) {
        return value != null ? value.toUpperCase() : null;
    }

    public String lowercase(String value) {
        return value != null ? value.toLowerCase() : null;
    }

    public String substring(String value, int start, int end) {
        return value != null ? value.substring(start, end) : null;
    }

    public String concat(String... values) {
        return String.join("", values);
    }

    public String formatDate(String date, String format) {
        // Implement date formatting using SimpleDateFormat or DateTimeFormatter
        return date;
    }

    public String trim(String value) {
        return value != null ? value.trim() : null;
    }

    public String replace(String value, String target, String replacement) {
        return value != null ? value.replace(target, replacement) : null;
    }
}

/**
 * Validation engine
 */
class ValidationEngine {
    public void validate(Object value, String validator, String fieldId) {
        if (validator.equals("notEmpty()")) {
            if (value == null || value.toString().isEmpty()) {
                throw new ValidationException("Field " + fieldId + " cannot be empty");
            }
        } else if (validator.startsWith("regex(")) {
            String pattern = validator.substring(7, validator.length() - 2);
            if (value != null && !value.toString().matches(pattern)) {
                throw new ValidationException("Field " + fieldId + " does not match pattern: " + pattern);
            }
        }
    }
}

/**
 * Custom exceptions
 */
class TransformationException extends RuntimeException {
    public TransformationException(String message) {
        super(message);
    }
    public TransformationException(String message, Throwable cause) {
        super(message, cause);
    }
}

class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}