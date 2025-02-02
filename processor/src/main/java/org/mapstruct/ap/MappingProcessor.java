/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementKindVisitor6;
import javax.tools.Diagnostic.Kind;

import org.mapstruct.ap.internal.model.Mapper;
import org.mapstruct.ap.internal.option.Options;
import org.mapstruct.ap.internal.prism.MapperPrism;
import org.mapstruct.ap.internal.prism.ReportingPolicyPrism;
import org.mapstruct.ap.internal.processor.DefaultModelElementProcessorContext;
import org.mapstruct.ap.internal.processor.ModelElementProcessor;
import org.mapstruct.ap.internal.processor.ModelElementProcessor.ProcessorContext;
import org.mapstruct.ap.internal.util.AnnotationProcessingException;
import org.mapstruct.ap.internal.util.AnnotationProcessorContext;
import org.mapstruct.ap.internal.util.RoundContext;
import org.mapstruct.ap.spi.TypeHierarchyErroneousException;

import static javax.lang.model.element.ElementKind.CLASS;

/**
 * A JSR 269 annotation {@link Processor} which generates the implementations for mapper interfaces (interfaces
 * annotated with {@code @Mapper}).
 * <p>
 * Implementation notes:
 * <p>
 * The generation happens by incrementally building up a model representation of each mapper to be generated (a
 * {@link Mapper} object), which is then written into the resulting Java source file.
 * <p>
 * The model instantiation and processing happens in several phases/passes by applying a sequence of
 * {@link ModelElementProcessor}s. The processors to apply are retrieved using the Java service loader mechanism and are
 * processed in order of their {@link ModelElementProcessor#getPriority() priority}. The general processing flow is
 * this:
 * <ul>
 * <li>retrieve mapping methods</li>
 * <li>create the {@code Mapper} model</li>
 * <li>perform enrichments and modifications (e.g. add annotations for dependency injection)</li>
 * <li>if no error occurred, write out the model into Java source files</li>
 * </ul>
 * <p>
 * For reading annotation attributes, prisms as generated with help of the <a
 * href="https://java.net/projects/hickory">Hickory</a> tool are used. These prisms allow a comfortable access to
 * annotations and their attributes without depending on their class objects.
 * <p>
 * The creation of Java source files is done using the <a href="http://freemarker.org/"> FreeMarker</a> template engine.
 * Each node of the mapper model has a corresponding FreeMarker template file which provides the Java representation of
 * that element and can include sub-elements via a custom FreeMarker directive. That way writing out a root node of the
 * model ({@code Mapper}) will recursively include all contained sub-elements (such as its methods, their property
 * mappings etc.).
 *
 * @author Gunnar Morling
 */
@SupportedAnnotationTypes("org.mapstruct.Mapper")
@SupportedOptions({
    MappingProcessor.SUPPRESS_GENERATOR_TIMESTAMP,
    MappingProcessor.SUPPRESS_GENERATOR_VERSION_INFO_COMMENT,
    MappingProcessor.UNMAPPED_TARGET_POLICY,
    MappingProcessor.DEFAULT_COMPONENT_MODEL,
    MappingProcessor.VERBOSE
})
public class MappingProcessor extends AbstractProcessor {

    /**
     * Whether this processor claims all processed annotations exclusively or not.
     */
    private static final boolean ANNOTATIONS_CLAIMED_EXCLUSIVELY = false;

    protected static final String SUPPRESS_GENERATOR_TIMESTAMP = "mapstruct.suppressGeneratorTimestamp";
    protected static final String SUPPRESS_GENERATOR_VERSION_INFO_COMMENT =
        "mapstruct.suppressGeneratorVersionInfoComment";
    protected static final String UNMAPPED_TARGET_POLICY = "mapstruct.unmappedTargetPolicy";
    protected static final String DEFAULT_COMPONENT_MODEL = "mapstruct.defaultComponentModel";
    protected static final String ALWAYS_GENERATE_SERVICE_FILE = "mapstruct.alwaysGenerateServicesFile";
    protected static final String VERBOSE = "mapstruct.verbose";

    private Options options;

    private AnnotationProcessorContext annotationProcessorContext;

    /**
     * Any mappers for which an implementation cannot be generated in the current round because they have source/target
     * types with incomplete hierarchies (as super-types are to be generated by other processors). They will be
     * processed in subsequent rounds.
     * <p>
     * If the hierarchy of a mapper's source/target types is never completed (i.e. the missing super-types are not
     * generated by other processors), this mapper will not be generated; That's fine, the compiler will raise an error
     * due to the inconsistent Java types used as source or target anyways.
     */
    private Set<TypeElement> deferredMappers = new HashSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init( processingEnv );

        options = createOptions();
        annotationProcessorContext = new AnnotationProcessorContext(
            processingEnv.getElementUtils(),
            processingEnv.getTypeUtils(),
            processingEnv.getMessager(),
            options.isVerbose()
        );
    }

    private Options createOptions() {
        String unmappedTargetPolicy = processingEnv.getOptions().get( UNMAPPED_TARGET_POLICY );

        return new Options(
            Boolean.valueOf( processingEnv.getOptions().get( SUPPRESS_GENERATOR_TIMESTAMP ) ),
            Boolean.valueOf( processingEnv.getOptions().get( SUPPRESS_GENERATOR_VERSION_INFO_COMMENT ) ),
            unmappedTargetPolicy != null ? ReportingPolicyPrism.valueOf( unmappedTargetPolicy.toUpperCase() ) : null,
            processingEnv.getOptions().get( DEFAULT_COMPONENT_MODEL ),
            Boolean.valueOf( processingEnv.getOptions().get( ALWAYS_GENERATE_SERVICE_FILE ) ),
            Boolean.valueOf( processingEnv.getOptions().get( VERBOSE ) )
        );
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnvironment) {
        // nothing to do in the last round
        if ( !roundEnvironment.processingOver() ) {
            RoundContext roundContext = new RoundContext( annotationProcessorContext );

            // process any mappers left over from previous rounds
            Set<TypeElement> deferredMappers = getAndResetDeferredMappers();
            processMapperElements( deferredMappers, roundContext );

            // get and process any mappers from this round
            Set<TypeElement> mappers = getMappers( annotations, roundEnvironment );
            processMapperElements( mappers, roundContext );
        }

        return ANNOTATIONS_CLAIMED_EXCLUSIVELY;
    }

    /**
     * Gets fresh copies of all mappers deferred from previous rounds (the originals may contain references to
     * erroneous source/target type elements).
     */
    private Set<TypeElement> getAndResetDeferredMappers() {
        Set<TypeElement> deferred = new HashSet<>( deferredMappers.size() );

        for (TypeElement element : deferredMappers ) {
            deferred.add( processingEnv.getElementUtils().getTypeElement( element.getQualifiedName() ) );
        }

        deferredMappers.clear();
        return deferred;
    }

    private Set<TypeElement> getMappers(final Set<? extends TypeElement> annotations,
                                        final RoundEnvironment roundEnvironment) {
        Set<TypeElement> mapperTypes = new HashSet<>();

        for ( TypeElement annotation : annotations ) {
            //Indicates that the annotation's type isn't on the class path of the compiled
            //project. Let the compiler deal with that and print an appropriate error.
            if ( annotation.getKind() != ElementKind.ANNOTATION_TYPE ) {
                continue;
            }

            try {
                Set<? extends Element> annotatedMappers = roundEnvironment.getElementsAnnotatedWith( annotation );
                for (Element mapperElement : annotatedMappers) {
                    TypeElement mapperTypeElement = asTypeElement( mapperElement );

                    // on some JDKs, RoundEnvironment.getElementsAnnotatedWith( ... ) returns types with
                    // annotations unknown to the compiler, even though they are not declared Mappers
                    if ( mapperTypeElement != null && MapperPrism.getInstanceOn( mapperTypeElement ) != null ) {
                        mapperTypes.add( mapperTypeElement );
                    }
                }
            }
            catch ( Throwable t ) { // whenever that may happen, but just to stay on the save side
                handleUncaughtError( annotation, t );
                continue;
            }
        }
        return mapperTypes;
    }

    private void processMapperElements(Set<TypeElement> mapperElements, RoundContext roundContext) {
        for ( TypeElement mapperElement : mapperElements ) {
            try {
                // create a new context for each generated mapper in order to have imports of referenced types
                // correctly managed;
                // note that this assumes that a new source file is created for each mapper which must not
                // necessarily be the case, e.g. in case of several mapper interfaces declared as inner types
                // of one outer interface
                List<? extends Element> tst = mapperElement.getEnclosedElements();
                ProcessorContext context = new DefaultModelElementProcessorContext(
                        processingEnv, options, roundContext, getDeclaredTypesNotToBeImported( mapperElement )
                );

                processMapperTypeElement( context, mapperElement );
            }
            catch ( TypeHierarchyErroneousException thie ) {
                if ( options.isVerbose() ) {
                    processingEnv.getMessager().printMessage(
                        Kind.NOTE, "MapStruct: referred types not available (yet), deferring mapper: "
                            + mapperElement );
                }
                deferredMappers.add( mapperElement );
            }
            catch ( Throwable t ) {
                handleUncaughtError( mapperElement, t );
                break;
            }
        }
    }

    private Map<String, String> getDeclaredTypesNotToBeImported(TypeElement element) {
        return element.getEnclosedElements().stream()
            .filter( e -> CLASS.equals( e.getKind() ) )
            .map( Element::getSimpleName )
            .map( Name::toString )
            .collect( Collectors.toMap( k -> k, v -> element.getQualifiedName().toString() + "." + v ) );
    }

    private void handleUncaughtError(Element element, Throwable thrown) {
        StringWriter sw = new StringWriter();
        thrown.printStackTrace( new PrintWriter( sw ) );

        String reportableStacktrace = sw.toString().replace( System.lineSeparator( ), "  " );

        processingEnv.getMessager().printMessage(
            Kind.ERROR, "Internal error in the mapping processor: " + reportableStacktrace, element );
    }

    /**
     * Applies all registered {@link ModelElementProcessor}s to the given mapper
     * type.
     *
     * @param context The processor context.
     * @param mapperTypeElement The mapper type element.
     */
    private void processMapperTypeElement(ProcessorContext context, TypeElement mapperTypeElement) {
        Object model = null;

        for ( ModelElementProcessor<?, ?> processor : getProcessors() ) {
            try {
                model = process( context, processor, mapperTypeElement, model );
            }
            catch ( AnnotationProcessingException e ) {
                processingEnv.getMessager()
                    .printMessage(
                        Kind.ERROR,
                        e.getMessage(),
                        e.getElement(),
                        e.getAnnotationMirror(),
                        e.getAnnotationValue()
                    );
                break;
            }
        }
    }

    private <P, R> R process(ProcessorContext context, ModelElementProcessor<P, R> processor,
                             TypeElement mapperTypeElement, Object modelElement) {
        @SuppressWarnings("unchecked")
        P sourceElement = (P) modelElement;
        return processor.process( context, mapperTypeElement, sourceElement );
    }

    /**
     * Retrieves all model element processors, ordered by their priority value
     * (with the method retrieval processor having the lowest priority value (1)
     * and the code generation processor the highest priority value.
     *
     * @return A list with all model element processors.
     */
    private Iterable<ModelElementProcessor<?, ?>> getProcessors() {
        // TODO Re-consider which class loader to use in case processors are
        // loaded from other modules, too
        @SuppressWarnings("rawtypes")
        Iterator<ModelElementProcessor> processorIterator = ServiceLoader.load(
            ModelElementProcessor.class,
            MappingProcessor.class.getClassLoader()
        )
            .iterator();
        List<ModelElementProcessor<?, ?>> processors = new ArrayList<>();

        while ( processorIterator.hasNext() ) {
            processors.add( processorIterator.next() );
        }

        Collections.sort( processors, new ProcessorComparator() );

        return processors;
    }

    private TypeElement asTypeElement(Element element) {
        return element.accept(
            new ElementKindVisitor6<TypeElement, Void>() {
                @Override
                public TypeElement visitTypeAsInterface(TypeElement e, Void p) {
                    return e;
                }

                @Override
                public TypeElement visitTypeAsClass(TypeElement e, Void p) {
                    return e;
                }

            }, null
        );
    }

    private static class ProcessorComparator implements Comparator<ModelElementProcessor<?, ?>> {

        @Override
        public int compare(ModelElementProcessor<?, ?> o1, ModelElementProcessor<?, ?> o2) {
            return Integer.compare( o1.getPriority(), o2.getPriority() );
        }
    }
}
