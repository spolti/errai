/*
 * Copyright 2011 JBoss, a divison Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.ioc.rebind;

import com.google.gwt.core.ext.Generator;
import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.NotFoundException;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.user.rebind.ClassSourceFileComposerFactory;
import com.google.gwt.user.rebind.SourceWriter;
import org.jboss.errai.bus.client.ErraiBus;
import org.jboss.errai.bus.client.api.MessageCallback;
import org.jboss.errai.bus.client.framework.MessageBus;
import org.jboss.errai.bus.rebind.ProcessingContext;
import org.jboss.errai.bus.rebind.ScannerSingleton;
import org.jboss.errai.bus.server.ErraiBootstrapFailure;
import org.jboss.errai.bus.server.service.metadata.MetaDataScanner;
import org.jboss.errai.ioc.client.InterfaceInjectionContext;
import org.jboss.errai.ioc.client.api.*;
import org.jboss.errai.ioc.rebind.ioc.*;
import org.jboss.errai.ioc.rebind.ioc.codegen.MetaClassFactory;
import org.jboss.errai.ioc.rebind.ioc.codegen.meta.MetaClass;
import org.jboss.errai.ioc.rebind.ioc.codegen.meta.MetaField;
import org.jboss.errai.ioc.rebind.ioc.codegen.meta.MetaParameterizedType;

import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * The main generator class for the errai-ioc framework.
 */
public class IOCGenerator extends Generator {
  /**
   * Simple name of class to be generated
   */
  private String className = null;

  /**
   * Package name of class to be generated
   */
  private String packageName = null;

  private TypeOracle typeOracle;

  private ProcessingContext procContext;
  private InjectorFactory injectFactory;
  private ProcessorFactory procFactory;

  public static final boolean isDebugCompile = Boolean.getBoolean("errai.ioc.debug");

  private List<Runnable> deferredTasks = new LinkedList<Runnable>();

  public IOCGenerator() {
  }

  public IOCGenerator(ProcessingContext processingContext) {
    this();
    this.procContext = processingContext;
    this.typeOracle = processingContext.getOracle();
    this.injectFactory = new InjectorFactory(processingContext);
    this.procFactory = new ProcessorFactory(injectFactory);
    defaultConfigureProcessor();
  }

  @Override
  public String generate(TreeLogger logger, GeneratorContext context, String typeName)
      throws UnableToCompleteException {
    typeOracle = context.getTypeOracle();

    try {
      // get classType and save instance variables

      JClassType classType = typeOracle.getType(typeName);
      packageName = classType.getPackage().getName();
      className = classType.getSimpleSourceName() + "Impl";

      logger.log(TreeLogger.INFO, "Generating Extensions Bootstrapper...");

      // Generate class source code
      generateIOCBootstrapClass(logger, context);
    }
    catch (Throwable e) {
      // record sendNowWith logger that Map generation threw an exception
      e.printStackTrace();
      logger.log(TreeLogger.ERROR, "Error generating extensions", e);
    }

    // return the fully qualified name of the class generated
    return packageName + "." + className;
  }

  /**
   * Generate source code for new class. Class extends
   * <code>HashMap</code>.
   *
   * @param logger  Logger object
   * @param context Generator context
   */
  private void generateIOCBootstrapClass(TreeLogger logger, GeneratorContext context) {
    // get print writer that receives the source code
    PrintWriter printWriter = context.tryCreate(logger, packageName, className);
    // print writer if null, source code has ALREADY been generated,

    if (printWriter == null) return;

    // init composer, set class properties, create source writer
    ClassSourceFileComposerFactory composer = new ClassSourceFileComposerFactory(packageName,
        className);

    composer.addImplementedInterface(Bootstrapper.class.getName());
    composer.addImport(InterfaceInjectionContext.class.getName());
    composer.addImport(Widget.class.getName());
    composer.addImport(List.class.getName());
    composer.addImport(ArrayList.class.getName());
    composer.addImport(Map.class.getName());
    composer.addImport(HashMap.class.getName());
    composer.addImport(com.google.gwt.user.client.ui.Panel.class.getName());
    composer.addImport(ErraiBus.class.getName());

    SourceWriter sourceWriter = composer.createSourceWriter(context, printWriter);

    procContext = new ProcessingContext(logger, context, sourceWriter, typeOracle);
    injectFactory = new InjectorFactory(procContext);
    procFactory = new ProcessorFactory(injectFactory);
    defaultConfigureProcessor();

    // generator constructor source code
    initializeProviders(context, logger, sourceWriter);
    generateExtensions(context, logger, sourceWriter);
    // close generated class
    sourceWriter.outdent();
    sourceWriter.println("}");

    // commit generated class
    context.commit(logger, printWriter);
  }

  public void initializeProviders(final GeneratorContext context, final TreeLogger logger, final SourceWriter sourceWriter) {

    final MetaClass typeProviderCls = MetaClassFactory.get(typeOracle, TypeProvider.class);
    MetaDataScanner scanner = ScannerSingleton.getOrCreateInstance();
    /*
    * IOCDecoratorExtension.class
    */
    Set<Class<?>> iocExtensions = scanner.getTypesAnnotatedWith(org.jboss.errai.ioc.client.api.IOCExtension.class);
    List<IOCExtensionConfigurator> extensionConfigurators = new ArrayList<IOCExtensionConfigurator>();
    for (Class<?> clazz : iocExtensions) {
      try {
        Class<? extends IOCExtensionConfigurator> configuratorClass = clazz.asSubclass(IOCExtensionConfigurator.class);

        IOCExtensionConfigurator configurator = configuratorClass.newInstance();

        configurator.configure(procContext, injectFactory, procFactory);

        extensionConfigurators.add(configurator);
      }
      catch (Exception e) {
        throw new ErraiBootstrapFailure("unable to load IOC Extension Configurator: " + e.getMessage(), e);
      }
    }

    /**
     * CodeDecorator.class
     */
    Set<Class<?>> decorators = scanner.getTypesAnnotatedWith(CodeDecorator.class);
    for (Class<?> clazz : decorators) {
      try {
        Class<? extends IOCDecoratorExtension> decoratorClass = clazz.asSubclass(IOCDecoratorExtension.class);

        Class<? extends Annotation> annoType = null;
        Type t = decoratorClass.getGenericSuperclass();
        if (!(t instanceof ParameterizedType)) {
          throw new ErraiBootstrapFailure("code decorator must extend IOCDecoratorExtension<@AnnotationType>");
        }

        ParameterizedType pType = (ParameterizedType) t;
        if (IOCDecoratorExtension.class.equals(pType.getRawType())) {
          if (pType.getActualTypeArguments().length == 0 || !Annotation.class.isAssignableFrom((Class) pType.getActualTypeArguments()[0])) {
            throw new ErraiBootstrapFailure("code decorator must extend IOCDecoratorExtension<@AnnotationType>");
          }

          annoType = ((Class) pType.getActualTypeArguments()[0]).asSubclass(Annotation.class);
        }

        injectFactory.getInjectionContext().registerDecorator(decoratorClass.getConstructor(new Class[]{Class.class}).newInstance(annoType));
      }
      catch (Exception e) {
        throw new ErraiBootstrapFailure("unable to load code decorator: " + e.getMessage(), e);
      }
    }

    /**
     * IOCProvider.class
     */
    Set<Class<?>> providers = scanner.getTypesAnnotatedWith(IOCProvider.class);
    for (Class<?> clazz : providers) {
      MetaClass bindType = null;
      MetaClass type = MetaClassFactory.get(typeOracle, clazz);

      boolean contextual = false;
      for (MetaClass iface : type.getInterfaces()) {
        if (iface.getFullyQualifiedName().equals(ContextualTypeProvider.class.getName())) {
          contextual = true;

          MetaParameterizedType pType = iface.getParameterizedType();

          if (pType == null) {
            throw new InjectionFailure("could not determine the bind type for the IOCProvider class: "
                + type.getFullyQualifiedName());
          }

          //todo: check for nested type parameters
          bindType = (MetaClass) pType.getTypeParameters()[0];
          break;
        }
      }

      if (bindType == null) {
        for (MetaClass iface : type.getInterfaces()) {
          if (!typeProviderCls.isAssignableFrom(iface)) {
            continue;
          }

          MetaParameterizedType pType = iface.getParameterizedType();

          if (pType == null) {
            throw new InjectionFailure("could not determine the bind type for the IOCProvider class: "
                + type.getFullyQualifiedName());
          }

          //todo: check for nested type parameters
          bindType = (MetaClass) pType.getTypeParameters()[0];
        }
      }

      if (bindType == null) {
        throw new InjectionFailure("the annotated provider class does not appear to implement " +
            TypeProvider.class.getName() + ": " + type.getFullyQualifiedName());
      }

      final MetaClass finalBindType = bindType;

      if (contextual) {
        injectFactory.addInjector(new ContextualProviderInjector(finalBindType, type));

      }
      else {
        injectFactory.addInjector(new ProviderInjector(finalBindType, type));
      }
    }


    /**
     * GeneratedBy.class
     */
    Set<Class<?>> generatedBys = scanner.getTypesAnnotatedWith(GeneratedBy.class);
    for (Class<?> clazz : generatedBys) {
      MetaClass type = MetaClassFactory.get(typeOracle, clazz);
      GeneratedBy anno = type.getAnnotation(GeneratedBy.class);
      Class<? extends ContextualTypeProvider> injectorClass = anno.value();

      try {
        injectFactory.addInjector(new ContextualProviderInjector(type, getJClassType(injectorClass)));
      }
      catch (Exception e) {
        throw new ErraiBootstrapFailure("could not load injector: " + e.getMessage(), e);
      }
    }

    for (IOCExtensionConfigurator extensionConfigurator : extensionConfigurators) {
      extensionConfigurator.afterInitialization(procContext, injectFactory, procFactory);
    }
  }

  private void generateExtensions(final GeneratorContext context, final TreeLogger logger, final SourceWriter sourceWriter) {
    // start constructor source generation
    sourceWriter.println("public " + className + "() { ");
    sourceWriter.indent();
    sourceWriter.println("super();");
    sourceWriter.outdent();
    sourceWriter.println("}");

    sourceWriter.println("public InterfaceInjectionContext bootstrapContainer() { ");
    sourceWriter.outdent();
    sourceWriter.println("InterfaceInjectionContext ctx = new InterfaceInjectionContext();");

    MetaDataScanner scanner = ScannerSingleton.getOrCreateInstance();
    procFactory.process(scanner, procContext);

    runAllDeferred();

    sourceWriter.println("return ctx;");
    sourceWriter.outdent();
    sourceWriter.println("}");

    List<MetaField> privateFields = injectFactory.getInjectionContext().getPrivateFieldsToExpose();

    if (!privateFields.isEmpty()) {
      sourceWriter.println();
    }

    for (MetaField f : privateFields) {
      sourceWriter.print("private static native void ");
      sourceWriter.print(InjectUtil.getPrivateFieldInjectorName(f));
      sourceWriter.print("(");
      sourceWriter.print(f.getDeclaringClass().getFullyQualifiedName());
      sourceWriter.print(" instance, ");
      sourceWriter.print(f.getType().getCanonicalName());
      sourceWriter.print(" value) /*-{");
      sourceWriter.println();
      sourceWriter.outdent();
      // begin JSNI call
      sourceWriter.print("instance.@");
      sourceWriter.print(f.getDeclaringClass().getFullyQualifiedName());
      sourceWriter.print("::");
      sourceWriter.print(f.getName());
      sourceWriter.print(" = ");
      sourceWriter.print("value;");
      sourceWriter.println();
      sourceWriter.outdent();
      sourceWriter.println("}-*/;\n");
    }

    for (MetaField f : privateFields) {
      sourceWriter.print("private static native ");
      sourceWriter.print(f.getType().getFullyQualifiedName());
      sourceWriter.print(" ");
      sourceWriter.print(InjectUtil.getPrivateFieldInjectorName(f));
      sourceWriter.print("(");
      sourceWriter.print(f.getDeclaringClass().getFullyQualifiedName());
      sourceWriter.print(" instance) /*-{");
      sourceWriter.println();
      sourceWriter.outdent();
      // begin JSNI call
      sourceWriter.print("return instance.@");
      sourceWriter.print(f.getDeclaringClass().getFullyQualifiedName());
      sourceWriter.print("::");
      sourceWriter.print(f.getName());
      sourceWriter.print(";");
      sourceWriter.println();
      sourceWriter.outdent();
      sourceWriter.println("}-*/;\n");
    }
  }

  public void addType(final MetaClass type) {
    injectFactory.addType(type);
  }

  public String generateWithSingletonSemantics(final MetaClass visit) {
    return injectFactory.generateSingleton(visit);
  }

  public String generateInjectors(final MetaClass visit) {
    return injectFactory.generate(visit);
  }

  public String generateAllProviders() {
    return injectFactory.generateAllProviders();
  }

  public void addDeferred(Runnable task) {
    deferredTasks.add(task);
  }

  private void runAllDeferred() {
    for (Runnable r : deferredTasks)
      r.run();
  }

  public MetaClass getJClassType(Class cls) {
    try {
      return MetaClassFactory.get(typeOracle.getType(cls.getName()));
    }
    catch (NotFoundException e) {
      return null;
    }
  }

  private void defaultConfigureProcessor() {
    final MetaClass widgetType = getJClassType(Widget.class);
    final MetaClass messageCallbackType = getJClassType(MessageCallback.class);
    final MetaClass messageBusType = getJClassType(MessageBus.class);

    procFactory.registerHandler(EntryPoint.class, new AnnotationHandler<EntryPoint>() {
      public void handle(final MetaClass type, EntryPoint annotation, ProcessingContext context) {
        addDeferred(new Runnable() {
          public void run() {
            generateWithSingletonSemantics(type);
          }
        });
      }
    });

    procFactory.registerHandler(ToRootPanel.class, new AnnotationHandler<ToRootPanel>() {
      public void handle(final MetaClass type, final ToRootPanel annotation, final ProcessingContext context) {
        if (widgetType.isAssignableFrom(type)) {

          addDeferred(new Runnable() {
            public void run() {
              context.getWriter().println("ctx.addToRootPanel(" + generateWithSingletonSemantics(type) + ");");
            }
          });
        }
        else {
          throw new InjectionFailure("type declares @" + annotation.getClass().getSimpleName()
              + "  but does not extend type Widget: " + type.getFullyQualifiedName());
        }
      }
    });

    procFactory.registerHandler(CreatePanel.class, new AnnotationHandler<CreatePanel>() {
      public void handle(final MetaClass type, final CreatePanel annotation, final ProcessingContext context) {
        if (widgetType.isAssignableFrom(type)) {

          addDeferred(new Runnable() {
            public void run() {
              context.getWriter().println("ctx.registerPanel(\"" + (annotation.value().equals("")
                  ? type.getName() : annotation.value()) + "\", " + generateInjectors(type) + ");");
            }
          });
        }
        else {
          throw new InjectionFailure("type declares @" + annotation.getClass().getSimpleName()
              + "  but does not extend type Widget: " + type.getFullyQualifiedName());
        }
      }
    });

    procFactory.registerHandler(ToPanel.class, new AnnotationHandler<ToPanel>() {
      public void handle(final MetaClass type, final ToPanel annotation, final ProcessingContext context) {
        if (widgetType.isAssignableFrom(type)) {

          addDeferred(new Runnable() {
            public void run() {
              context.getWriter()
                  .println("ctx.widgetToPanel(" + generateWithSingletonSemantics(type) + ", \"" + annotation.value() + "\");");
            }
          });
        }
        else {
          throw new InjectionFailure("type declares @" + annotation.getClass().getSimpleName()
              + "  but does not extend type Widget: " + type.getFullyQualifiedName());
        }
      }
    });
  }

  public static String debugOutput(CharSequence s) {
    if (isDebugCompile) {
      String debugStmt = Window.class.getName() + ".alert(" + s.toString().replaceAll("\"", "\\\"") + ");\n";
      return debugStmt + s.toString();
    }
    return s.toString();
  }

}
