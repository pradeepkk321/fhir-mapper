package com.fhir.mapper.validation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;

import com.fhir.mapper.model.CodeLookupTable;
import com.fhir.mapper.model.CodeMapping;
import com.fhir.mapper.model.FieldMapping;
import com.fhir.mapper.model.MappingDirection;
import com.fhir.mapper.model.MappingRegistry;
import com.fhir.mapper.model.ResourceMapping;

import ca.uhn.fhir.context.FhirContext;

/**
 * Validates mapping configurations during loading using HAPI FHIR
 */
public class MappingValidator {
    private final JexlEngine jexlEngine;
    private final FhirPathValidator fhirPathValidator;
    private final Set<String> validDataTypes;
    
    public MappingValidator() {
        this(FhirContext.forR4());
    }

    public MappingValidator(FhirContext fhirContext) {
        this.jexlEngine = new JexlBuilder().create();
        this.fhirPathValidator = new FhirPathValidator(fhirContext);
        this.validDataTypes = initValidDataTypes();
    }

    /**
     * Validate entire mapping registry
     */
    public ValidationResult validateRegistry(MappingRegistry registry) {
        ValidationResult result = new ValidationResult();
        
        // Validate lookup tables
        for (CodeLookupTable lookup : registry.getLookupTables().values()) {
            validateLookupTable(lookup, result);
        }
        
        // Validate resource mappings
        for (ResourceMapping mapping : registry.getResourceMappings()) {
            validateResourceMapping(mapping, registry, result);
        }
        
        return result;
    }

    /**
     * Validate resource mapping
     */
    public void validateResourceMapping(ResourceMapping mapping, MappingRegistry registry, 
                                        ValidationResult result) {
        String context = "Mapping: " + mapping.getId();
        
        // Basic validations
        if (mapping.getId() == null || mapping.getId().isEmpty()) {
            result.addError(context, "Mapping ID is required");
        }
        
        if (mapping.getDirection() == null) {
            result.addError(context, "Mapping direction is required");
        }
        
        if (mapping.getSourceType() == null || mapping.getSourceType().isEmpty()) {
            result.addError(context, "Source type is required");
        }
        
        if (mapping.getTargetType() == null || mapping.getTargetType().isEmpty()) {
            result.addError(context, "Target type is required");
        }
        
        // Validate FHIR resource type
        if (mapping.getDirection() == MappingDirection.JSON_TO_FHIR) {
            if (!fhirPathValidator.isValidResourceType(mapping.getTargetType())) {
                result.addError(context, "Invalid FHIR resource type: " + mapping.getTargetType());
            }
        } else if (mapping.getDirection() == MappingDirection.FHIR_TO_JSON) {
            if (!fhirPathValidator.isValidResourceType(mapping.getSourceType())) {
                result.addError(context, "Invalid FHIR resource type: " + mapping.getSourceType());
            }
        }
        
        // Validate field mappings
        Set<String> fieldIds = new HashSet<>();
        for (FieldMapping field : mapping.getFieldMappings()) {
            // Check duplicate IDs
            if (!fieldIds.add(field.getId())) {
                result.addError(context, "Duplicate field mapping ID: " + field.getId());
            }
            
            validateFieldMapping(field, mapping, registry, result);
        }
    }

    /**
     * Validate individual field mapping
     */
    private void validateFieldMapping(FieldMapping field, ResourceMapping parent, 
                                      MappingRegistry registry, ValidationResult result) {
        String context = "Mapping: " + parent.getId() + ", Field: " + field.getId();
        
        // ID validation
        if (field.getId() == null || field.getId().isEmpty()) {
            result.addError(context, "Field ID is required");
        }
        
        // Target path is always required
        if (field.getTargetPath() == null || field.getTargetPath().isEmpty()) {
            result.addError(context, "Target path is required");
        }
        
        // Source path validation (can be null if default value is provided)
        if (field.getSourcePath() == null && field.getDefaultValue() == null) {
            if (field.isRequired()) {
                result.addError(context, "Required field must have either sourcePath or defaultValue");
            }
        }
        
        // Data type validation
        if (field.getDataType() != null && !validDataTypes.contains(field.getDataType())) {
            result.addError(context, "Invalid data type: " + field.getDataType() + 
                ". Valid types: " + validDataTypes);
        }
        
        // FHIR path validation
        String resourceType = parent.getDirection() == MappingDirection.JSON_TO_FHIR ? 
            parent.getTargetType() : parent.getSourceType();
        
        String fhirPath = parent.getDirection() == MappingDirection.JSON_TO_FHIR ?
            field.getTargetPath() : field.getSourcePath();
            
        if (fhirPath != null) {
            ValidationResult pathResult = fhirPathValidator.validatePath(resourceType, fhirPath);
            if (!pathResult.isValid()) {
                result.addError(context, "Invalid FHIR path '" + fhirPath + "': " + 
                    pathResult.getErrors());
            }
            
            // Validate dataType matches FHIR path expected type
            if (field.getDataType() != null) {
                String expectedType = fhirPathValidator.getExpectedType(resourceType, fhirPath);
                if (expectedType != null && !isCompatibleType(field.getDataType(), expectedType)) {
                    result.addError(context, "DataType mismatch: field specifies '" + 
                        field.getDataType() + "' but FHIR path expects '" + expectedType + "'");
                }
            }
        }
        
        // Condition validation
        if (field.getCondition() != null) {
            validateExpression(field.getCondition(), context + " (condition)", result, true);
        }
        
        // Transform expression validation
        if (field.getTransformExpression() != null) {
            validateExpression(field.getTransformExpression(), context + " (transform)", result, false);
        }
        
        // Validator validation
        if (field.getValidator() != null) {
            validateValidatorExpression(field.getValidator(), context, result);
        }
        
        // Lookup table validation
        if (field.getLookupTable() != null) {
            if (registry.getLookupTable(field.getLookupTable()) == null) {
                result.addError(context, "Lookup table not found: " + field.getLookupTable());
            }
        }
    }

    /**
     * Validate JEXL expression
     */
    private void validateExpression(String expression, String context, 
                                    ValidationResult result, boolean mustBeBoolean) {
        try {
            // Replace context variables with dummy values for validation
            String testExpression = prepareExpressionForValidation(expression);
            
            JexlExpression expr = jexlEngine.createExpression(testExpression);
            
            // Additional check: if it must be boolean, verify it's a comparison/logical expression
            if (mustBeBoolean) {
                // Basic heuristic: boolean expressions usually contain operators
                if (!expression.matches(".*[=!<>].*|.*\\b(and|or|not)\\b.*")) {
                    result.addWarning(context, "Condition may not evaluate to boolean: " + expression);
                }
            }
        } catch (JexlException e) {
            result.addError(context, "Invalid JEXL expression '" + expression + "': " + e.getMessage());
        }
    }
    
    /**
     * Prepare expression for validation by replacing context variables
     */
    private String prepareExpressionForValidation(String expression) {
        if (expression == null || !expression.contains("$ctx.")) {
            return expression;
        }
        
        String prepared = expression;
        
        // Replace context variable references with test values
        prepared = prepared.replaceAll("\\$ctx\\.organizationId", "'test-org-id'");
        prepared = prepared.replaceAll("\\$ctx\\.facilityId", "'test-facility-id'");
        prepared = prepared.replaceAll("\\$ctx\\.tenantId", "'test-tenant-id'");
        prepared = prepared.replaceAll("\\$ctx\\.identifierSystem", "'test-system'");
        
        // Handle settings map access: $ctx.settings['key']
        prepared = prepared.replaceAll("\\$ctx\\.settings\\['([^']+)'\\]", "'test-value'");
        prepared = prepared.replaceAll("\\$ctx\\.settings\\[\"([^\"]+)\"\\]", "'test-value'");
        
        // Handle generic context variables
        prepared = prepared.replaceAll("\\$ctx\\.\\w+", "'test-value'");
        
        return prepared;
    }

    /**
     * Validate validator expression
     */
    private void validateValidatorExpression(String validator, String context, ValidationResult result) {
        if (validator.equals("notEmpty()")) {
            return; // Valid
        }
        
        if (validator.startsWith("regex(")) {
            // Extract and validate regex pattern
            String pattern = validator.substring(6, validator.length() - 1);
            // Remove quotes
            pattern = pattern.replaceAll("^['\"]|['\"]$", "");
            try {
                Pattern.compile(pattern);
            } catch (Exception e) {
                result.addError(context, "Invalid regex pattern in validator: " + e.getMessage());
            }
            return;
        }
        
        if (validator.startsWith("range(")) {
            // Validate range format: range(min, max)
            return;
        }
        
        result.addWarning(context, "Unknown validator function: " + validator);
    }

    /**
     * Validate lookup table
     */
    private void validateLookupTable(CodeLookupTable lookup, ValidationResult result) {
        String context = "Lookup: " + lookup.getId();
        
        if (lookup.getId() == null || lookup.getId().isEmpty()) {
            result.addError(context, "Lookup ID is required");
        }
        
        if (lookup.getMappings() == null || lookup.getMappings().isEmpty()) {
            result.addError(context, "Lookup must have at least one mapping");
        }
        
        // Check for duplicate source codes
        Set<String> sourceCodes = new HashSet<>();
        for (CodeMapping mapping : lookup.getMappings()) {
            if (mapping.getSourceCode() == null || mapping.getSourceCode().isEmpty()) {
                result.addError(context, "Source code cannot be null or empty");
            }
            
            if (mapping.getTargetCode() == null || mapping.getTargetCode().isEmpty()) {
                result.addError(context, "Target code cannot be null or empty");
            }
            
            if (!sourceCodes.add(mapping.getSourceCode())) {
                result.addError(context, "Duplicate source code: " + mapping.getSourceCode());
            }
        }
        
        // If bidirectional, check for duplicate target codes
        if (lookup.isBidirectional()) {
            Set<String> targetCodes = new HashSet<>();
            for (CodeMapping mapping : lookup.getMappings()) {
                if (!targetCodes.add(mapping.getTargetCode())) {
                    result.addError(context, "Bidirectional lookup has duplicate target code: " + 
                        mapping.getTargetCode());
                }
            }
        }
    }

    /**
     * Check if mapping dataType is compatible with FHIR expected type
     */
    private boolean isCompatibleType(String mappingType, String fhirType) {
        // Exact match
        if (mappingType.equals(fhirType)) {
            return true;
        }
        
        // Compatible type mappings
        Map<String, Set<String>> compatibleTypes = new HashMap<>();
        compatibleTypes.put("string", new HashSet<>(Arrays.asList(
            "string", "markdown", "id", "code", "uri", "url", "canonical", "oid", "uuid"
        )));
        compatibleTypes.put("integer", new HashSet<>(Arrays.asList(
            "integer", "unsignedInt", "positiveInt"
        )));
        compatibleTypes.put("decimal", new HashSet<>(Arrays.asList(
            "decimal"
        )));
        compatibleTypes.put("date", new HashSet<>(Arrays.asList(
            "date", "dateTime", "instant"
        )));
        compatibleTypes.put("dateTime", new HashSet<>(Arrays.asList(
            "dateTime", "instant"
        )));
        compatibleTypes.put("boolean", new HashSet<>(Arrays.asList(
            "boolean"
        )));
        compatibleTypes.put("code", new HashSet<>(Arrays.asList(
            "code", "string"
        )));
        
        Set<String> compatible = compatibleTypes.get(mappingType.toLowerCase());
        return compatible != null && compatible.contains(fhirType.toLowerCase());
    }

    /**
     * Initialize valid FHIR data types
     */
    private Set<String> initValidDataTypes() {
        return new HashSet<>(Arrays.asList(
            "string", "integer", "decimal", "boolean", "date", "dateTime", "time",
            "instant", "code", "uri", "url", "canonical", "oid", "uuid", "id",
            "markdown", "base64Binary", "unsignedInt", "positiveInt"
        ));
    }
}