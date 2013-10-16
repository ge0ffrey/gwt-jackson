package com.github.nmorel.gwtjackson.rebind;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JAbstractMethod;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JConstructor;
import com.google.gwt.core.ext.typeinfo.JMethod;
import com.google.gwt.core.ext.typeinfo.JParameter;

import static com.github.nmorel.gwtjackson.rebind.CreatorUtils.findFirstEncounteredAnnotationsOnAllHierarchy;

/**
 * @author Nicolas Morel
 */
public final class BeanInfo {

    public static BeanInfo process( TreeLogger logger, JacksonTypeOracle typeOracle, BeanJsonMapperInfo mapperInfo ) throws
        UnableToCompleteException {
        BeanInfo result = new BeanInfo();
        result.type = mapperInfo.getType();
        result.hasSubtypes = mapperInfo.getType().getSubtypes().length > 0;

        result.parameterizedTypes = null == mapperInfo.getType().isGenericType() ? new JClassType[0] : mapperInfo.getType().isGenericType()
            .getTypeParameters();

        determineInstanceCreator( logger, mapperInfo, result );

        JsonTypeInfo typeInfo = findFirstEncounteredAnnotationsOnAllHierarchy( mapperInfo.getType(), JsonTypeInfo.class );
        if ( null != typeInfo && !JsonTypeInfo.Id.NONE.equals( typeInfo.use() ) ) {
            result.typeInfo = typeInfo;
        }

        JsonAutoDetect jsonAutoDetect = findFirstEncounteredAnnotationsOnAllHierarchy( mapperInfo.getType(), JsonAutoDetect.class );
        if ( null != jsonAutoDetect ) {
            result.creatorVisibility = jsonAutoDetect.creatorVisibility();
            result.fieldVisibility = jsonAutoDetect.fieldVisibility();
            result.getterVisibility = jsonAutoDetect.getterVisibility();
            result.isGetterVisibility = jsonAutoDetect.isGetterVisibility();
            result.setterVisibility = jsonAutoDetect.setterVisibility();
        }

        JsonIgnoreProperties jsonIgnoreProperties = findFirstEncounteredAnnotationsOnAllHierarchy( mapperInfo
            .getType(), JsonIgnoreProperties.class );
        if ( null != jsonIgnoreProperties ) {
            for ( String ignoreProperty : jsonIgnoreProperties.value() ) {
                result.addIgnoredField( ignoreProperty );
            }
            result.ignoreUnknown = jsonIgnoreProperties.ignoreUnknown();
        }

        JsonPropertyOrder jsonPropertyOrder = findFirstEncounteredAnnotationsOnAllHierarchy( mapperInfo
            .getType(), JsonPropertyOrder.class );
        if ( null != jsonPropertyOrder && jsonPropertyOrder.value().length > 0 ) {
            result.propertyOrderList = Arrays.asList( jsonPropertyOrder.value() );
        } else if ( !result.creatorParameters.isEmpty() ) {
            result.propertyOrderList = new ArrayList<String>( result.creatorParameters.keySet() );
        } else {
            result.propertyOrderList = Collections.emptyList();
        }
        result.propertyOrderAlphabetic = null != jsonPropertyOrder && jsonPropertyOrder.alphabetic();

        result.identityInfo = BeanIdentityInfo.process( logger, typeOracle, mapperInfo.getType() );

        return result;
    }

    /**
     * Look for the method to create a new instance of the bean. If none are found or the bean is abstract or an interface, we considered it
     * as non instantiable.
     *
     * @param logger logger
     * @param info current bean info
     */
    private static void determineInstanceCreator( TreeLogger logger, BeanJsonMapperInfo mapperInfo, BeanInfo info ) {
        if ( null != info.getType().isInterface() || info.getType().isAbstract() ) {
            info.instantiable = false;
        } else {
            // we search for @JsonCreator annotation
            JConstructor creatorDefaultConstructor = null;
            JConstructor creatorConstructor = null;
            for ( JConstructor constructor : info.getType().getConstructors() ) {
                if ( constructor.getParameters().length == 0 ) {
                    creatorDefaultConstructor = constructor;
                    continue;
                }

                // A constructor is considered as a creator if
                // - he is annotated with JsonCreator and
                //   * all its parameters are annotated with JsonProperty
                //   * or it has only one parameter
                // - or all its parameters are annotated with JsonProperty
                boolean isAllParametersAnnotatedWithJsonProperty = isAllParametersAnnotatedWith( constructor, JsonProperty.class );
                if ( (constructor.isAnnotationPresent( JsonCreator.class ) && ((isAllParametersAnnotatedWithJsonProperty) || (constructor
                    .getParameters().length == 1))) || isAllParametersAnnotatedWithJsonProperty ) {
                    if ( null != creatorConstructor ) {
                        // Jackson fails with an ArrayIndexOutOfBoundsException when it's the case, let's be more flexible
                        logger.log( TreeLogger.Type.WARN, "More than one constructor annotated with @JsonCreator, " +
                            "we use " + creatorConstructor );
                        break;
                    } else {
                        creatorConstructor = constructor;
                    }
                }
            }

            JMethod creatorFactory = null;
            if ( null == creatorConstructor ) {
                // searching for factory method
                for ( JMethod method : info.getType().getMethods() ) {
                    if ( method.isStatic() && method.isAnnotationPresent( JsonCreator.class ) && (method
                        .getParameters().length == 1 || isAllParametersAnnotatedWith( method, JsonProperty.class )) ) {
                        if ( null != creatorFactory ) {

                            // Jackson fails with an ArrayIndexOutOfBoundsException when it's the case, let's be more flexible
                            logger.log( TreeLogger.Type.WARN, "More than one factory method annotated with @JsonCreator, " +
                                "we use " + creatorFactory );
                            break;
                        } else {
                            creatorFactory = method;
                        }
                    }
                }
            }

            if ( null != creatorConstructor ) {
                info.creatorMethod = creatorConstructor;
            } else if ( null != creatorFactory ) {
                info.creatorMethod = creatorFactory;
            } else if ( null != creatorDefaultConstructor ) {
                info.creatorDefaultConstructor = true;
                info.creatorMethod = creatorDefaultConstructor;
            }

            info.instantiable = null != info.creatorMethod;
        }

        info.creatorParameters = new LinkedHashMap<String, JParameter>();

        if ( info.instantiable ) {
            info.instanceBuilderSimpleName = info.getType().getSimpleSourceName() + "InstanceBuilder";
            info.instanceBuilderQualifiedName = mapperInfo.getQualifiedDeserializerClassName() + "." + info.instanceBuilderSimpleName;

            if ( !info.isCreatorDefaultConstructor() ) {
                if ( info.creatorMethod
                    .getParameters().length == 1 && !isAllParametersAnnotatedWith( info.creatorMethod, JsonProperty.class ) ) {
                    // delegation constructor
                    info.creatorDelegation = true;
                } else {
                    for ( JParameter parameter : info.creatorMethod.getParameters() ) {
                        info.creatorParameters.put( parameter.getAnnotation( JsonProperty.class ).value(), parameter );
                    }
                }
            }
        } else {
            info.instanceBuilderQualifiedName = BeanJsonDeserializerCreator.INSTANCE_BUILDER_CLASS + "<" + info.getType()
                .getParameterizedQualifiedSourceName() + ">";
        }
    }

    private static <T extends Annotation> boolean isAllParametersAnnotatedWith( JAbstractMethod method, Class<T> annotation ) {
        for ( JParameter parameter : method.getParameters() ) {
            if ( !parameter.isAnnotationPresent( annotation ) ) {
                return false;
            }
        }

        return true;
    }

    private JClassType type;

    private JClassType[] parameterizedTypes;

    private String instanceBuilderQualifiedName;

    private String instanceBuilderSimpleName;

    /*####  Instantiation properties  ####*/
    private boolean instantiable;

    private JAbstractMethod creatorMethod;

    private Map<String, JParameter> creatorParameters;

    private boolean creatorDefaultConstructor;

    private boolean creatorDelegation;

    private JsonTypeInfo typeInfo;

    private boolean hasSubtypes;

    /*####  Visibility properties  ####*/
    private Set<String> ignoredFields = new HashSet<String>();

    private JsonAutoDetect.Visibility fieldVisibility = JsonAutoDetect.Visibility.DEFAULT;

    private JsonAutoDetect.Visibility getterVisibility = JsonAutoDetect.Visibility.DEFAULT;

    private JsonAutoDetect.Visibility isGetterVisibility = JsonAutoDetect.Visibility.DEFAULT;

    private JsonAutoDetect.Visibility setterVisibility = JsonAutoDetect.Visibility.DEFAULT;

    private JsonAutoDetect.Visibility creatorVisibility = JsonAutoDetect.Visibility.DEFAULT;

    private boolean ignoreUnknown;

    /*####  Ordering properties  ####*/
    private List<String> propertyOrderList;

    private boolean propertyOrderAlphabetic;

    /*####  Identity info  ####*/
    private BeanIdentityInfo identityInfo;

    private BeanInfo() {

    }

    public JClassType getType() {
        return type;
    }

    public JClassType[] getParameterizedTypes() {
        return parameterizedTypes;
    }

    public String getInstanceBuilderQualifiedName() {
        return instanceBuilderQualifiedName;
    }

    public String getInstanceBuilderSimpleName() {
        return instanceBuilderSimpleName;
    }

    public boolean isInstantiable() {
        return instantiable;
    }

    public boolean isCreatorDefaultConstructor() {
        return creatorDefaultConstructor;
    }

    public JAbstractMethod getCreatorMethod() {
        return creatorMethod;
    }

    public Map<String, JParameter> getCreatorParameters() {
        return creatorParameters;
    }

    public boolean isCreatorDelegation() {
        return creatorDelegation;
    }

    public JsonTypeInfo getTypeInfo() {
        return typeInfo;
    }

    public boolean isHasSubtypes() {
        return hasSubtypes;
    }

    public Set<String> getIgnoredFields() {
        return ignoredFields;
    }

    private void addIgnoredField( String ignoredField ) {
        this.ignoredFields.add( ignoredField );
    }

    public JsonAutoDetect.Visibility getFieldVisibility() {
        return fieldVisibility;
    }

    public JsonAutoDetect.Visibility getGetterVisibility() {
        return getterVisibility;
    }

    public JsonAutoDetect.Visibility getIsGetterVisibility() {
        return isGetterVisibility;
    }

    public JsonAutoDetect.Visibility getSetterVisibility() {
        return setterVisibility;
    }

    public JsonAutoDetect.Visibility getCreatorVisibility() {
        return creatorVisibility;
    }

    public boolean isIgnoreUnknown() {
        return ignoreUnknown;
    }

    public List<String> getPropertyOrderList() {
        return propertyOrderList;
    }

    public boolean isPropertyOrderAlphabetic() {
        return propertyOrderAlphabetic;
    }

    public BeanIdentityInfo getIdentityInfo() {
        return identityInfo;
    }
}
