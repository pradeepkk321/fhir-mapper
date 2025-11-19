package com.fhir.mapper.validation;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.RuntimeCompositeDatatypeDefinition;
import ca.uhn.fhir.context.RuntimeResourceDefinition;

/**
 * Validates FHIR paths using HAPI FHIR structure definitions
 */
public class FhirPathValidator {
    private final ca.uhn.fhir.context.FhirContext fhirContext;
    
    public FhirPathValidator(ca.uhn.fhir.context.FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }
    
    /**
     * Check if resource type is valid
     */
    public boolean isValidResourceType(String resourceType) {
        try {
            fhirContext.getResourceDefinition(resourceType);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get expected FHIR type for a path
     */
    public String getExpectedType(String resourceType, String path) {
        try {
            BaseRuntimeElementDefinition<?> elementDef = getElementDefinition(resourceType, path);
            if (elementDef != null) {
                return elementDef.getName();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    /**
     * Validate FHIR path for a resource using HAPI structure definitions
     */
    public ValidationResult validatePath(String resourceType, String path) {
        ValidationResult result = new ValidationResult();
        
        if (!isValidResourceType(resourceType)) {
            result.addError("FHIRPath", "Unknown resource type: " + resourceType);
            return result;
        }
        
        try {
            // Get resource definition from HAPI
            RuntimeResourceDefinition resourceDef = 
                fhirContext.getResourceDefinition(resourceType);
            
            // Parse and validate path
            String normalizedPath = normalizePath(path);
            validatePathAgainstDefinition(resourceDef, normalizedPath, result);
            
        } catch (Exception e) {
            result.addError("FHIRPath", "Error validating path: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Normalize path by removing array indices
     */
    private String normalizePath(String path) {
        // Remove array indices: name[0].given[0] -> name.given
        return path.replaceAll("\\[\\d+\\]", "");
    }
    
    /**
     * Validate path against HAPI structure definition
     */
    private void validatePathAgainstDefinition(
            RuntimeResourceDefinition resourceDef,
            String path, 
            ValidationResult result) {
        
        String[] parts = path.split("\\.");
        BaseRuntimeElementDefinition<?> currentDef = resourceDef;
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            
            if (currentDef instanceof RuntimeResourceDefinition) {
                RuntimeResourceDefinition resDef = (RuntimeResourceDefinition) currentDef;
                BaseRuntimeChildDefinition childDef = resDef.getChildByName(part);
                
                if (childDef == null) {
                    result.addError("FHIRPath", 
                        "Field '" + part + "' does not exist in " + resDef.getName());
                    return;
                }
                
                // Get the child element definition for next iteration
                if (i < parts.length - 1) {
                    currentDef = childDef.getChildByName(part);
                }
                
            } else if (currentDef instanceof RuntimeCompositeDatatypeDefinition) {
                RuntimeCompositeDatatypeDefinition compositeDef = 
                    (RuntimeCompositeDatatypeDefinition) currentDef;
                
                BaseRuntimeChildDefinition childDef = compositeDef.getChildByName(part);
                
                if (childDef == null) {
                    result.addError("FHIRPath", 
                        "Field '" + part + "' does not exist in " + compositeDef.getName());
                    return;
                }
                
                // Get next level definition
                if (i < parts.length - 1) {
                    currentDef = childDef.getChildByName(part);
                }
            } else {
                // Primitive or other type - can't validate further
                if (i < parts.length - 1) {
                    result.addWarning("FHIRPath", 
                        "Cannot validate nested path beyond primitive/complex type at: " + part);
                }
                return;
            }
        }
    }
    
    /**
     * Get element definition for a path (useful for type checking)
     */
    public BaseRuntimeElementDefinition<?> getElementDefinition(
            String resourceType, String path) {
        try {
            RuntimeResourceDefinition resourceDef = 
                fhirContext.getResourceDefinition(resourceType);
            
            String normalizedPath = normalizePath(path);
            String[] parts = normalizedPath.split("\\.");
            
            BaseRuntimeElementDefinition<?> currentDef = resourceDef;
            
            for (String part : parts) {
                if (currentDef instanceof RuntimeResourceDefinition) {
                    RuntimeResourceDefinition resDef = (RuntimeResourceDefinition) currentDef;
                    BaseRuntimeChildDefinition childDef = resDef.getChildByName(part);
                    if (childDef != null) {
                        currentDef = childDef.getChildByName(part);
                    } else {
                        return null;
                    }
                } else if (currentDef instanceof RuntimeCompositeDatatypeDefinition) {
                    RuntimeCompositeDatatypeDefinition compositeDef = 
                        (RuntimeCompositeDatatypeDefinition) currentDef;
                    BaseRuntimeChildDefinition childDef = compositeDef.getChildByName(part);
                    if (childDef != null) {
                        currentDef = childDef.getChildByName(part);
                    } else {
                        return null;
                    }
                } else {
                    // Can't traverse further
                    return currentDef;
                }
            }
            
            return currentDef;
        } catch (Exception e) {
            return null;
        }
    }


}
