package proguard.optimize.kotlin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import proguard.classfile.*;
import proguard.classfile.attribute.Attribute;
import proguard.classfile.attribute.CodeAttribute;
import proguard.classfile.attribute.ExceptionsAttribute;
import proguard.classfile.attribute.LineNumberTableAttribute;
import proguard.classfile.attribute.visitor.AttributeVisitor;
import proguard.classfile.editor.ClassBuilder;
import proguard.classfile.editor.ClassEditor;
import proguard.classfile.editor.ConstantPoolEditor;
import proguard.classfile.editor.InstructionSequenceBuilder;
import proguard.classfile.instruction.*;
import proguard.classfile.instruction.visitor.InstructionVisitor;
import proguard.classfile.util.ClassSuperHierarchyInitializer;
import proguard.classfile.util.ClassUtil;
import proguard.classfile.visitor.*;
import proguard.io.ExtraDataEntryNameMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KotlinLambdaClassMerger implements ClassPoolVisitor, ClassVisitor {

    private final ClassVisitor lambdaGroupVisitor;
    private ProgramClass lambdaGroup = new ProgramClass();
    private ClassBuilder classBuilder;
    private LambdaGroupInvokeCodeBuilder invokeCodeBuilder;
    private final ClassPool programClassPool;
    private final ClassPool libraryClassPool;
    private final ExtraDataEntryNameMap extraDataEntryNameMap;
    private final Map<String, ProgramClass> lambdasToLambdaGroups = new HashMap<>();
    private final Map<String, Integer> lambdasToIds = new HashMap<>();
    private static final Logger logger = LogManager.getLogger(KotlinLambdaClassMerger.class);

    public KotlinLambdaClassMerger(final ClassPool             programClassPool,
                                   final ClassPool             libraryClassPool,
                                   final ClassVisitor          lambdaGroupVisitor,
                                   final ExtraDataEntryNameMap extraDataEntryNameMap)
    {
        this.programClassPool = programClassPool;
        this.libraryClassPool = libraryClassPool;
        this.lambdaGroupVisitor = lambdaGroupVisitor;
        this.extraDataEntryNameMap = extraDataEntryNameMap;
    }

    public Map<String, ProgramClass> getLambdaToLambdaGroupMapping()
    {
        return lambdasToLambdaGroups;
    }

    public Map<String, Integer> getLambdaToIdMapping()
    {
        return lambdasToIds;
    }

    @Override
    public void visitClassPool(ClassPool lambdaClassPool)
    {
        // choose a name for the lambda group
        // ensure that the lambda group is in the same package as the classes of the class pool
        String lambdaGroupName = getPackagePrefixOfClasses(lambdaClassPool) + "LambdaGroup";
        logger.info("Creating lambda group with name {}", lambdaGroupName);
        // create a lambda group
        // let the lambda group extend Lambda
        this.classBuilder = new ClassBuilder(VersionConstants.CLASS_VERSION_1_8,
                                             AccessConstants.PUBLIC,
                                             lambdaGroupName,
                                             KotlinLambdaMerger.NAME_KOTLIN_LAMBDA);
        this.lambdaGroup = classBuilder.getProgramClass();
        this.classBuilder = new ClassBuilder(this.lambdaGroup,
                                             this.programClassPool,
                                             this.libraryClassPool);

        // initialise the super class of the lambda group
        this.lambdaGroup.accept(new ClassSuperHierarchyInitializer(
                                this.programClassPool,
                                this.libraryClassPool));

        // let the lambda group implement Function0
        classBuilder.addInterface(KotlinLambdaMerger.NAME_KOTLIN_FUNCTION0);

        // add a classId field to the lambda group
        ProgramField classIdField = classBuilder.addAndReturnField(AccessConstants.PRIVATE, "classId", "I");

        this.invokeCodeBuilder = new LambdaGroupInvokeCodeBuilder();

        // add a constructor which takes an id as argument and stores it in the classId field
        ProgramMethod initMethod = classBuilder.addAndReturnMethod(AccessConstants.PUBLIC,
                                    ClassConstants.METHOD_NAME_INIT,
                                    "(I)V",
                                    50,
                                    code -> {
                                        code
                                        .aload_0() // load this class
                                        .iload_1() // load the id argument
                                        .putfield(lambdaGroup, classIdField) // store the id in a field
                                        .aload_0() // load this class
                                        .iconst_0() // push 0 on stack
                                        .invokespecial(KotlinLambdaMerger.NAME_KOTLIN_LAMBDA,
                                                       ClassConstants.METHOD_NAME_INIT,
                                                       "(I)V")
                                        .return_();
                                    });

        logger.info("Lambda group {} before adding lambda implementations:", lambdaGroupName);
        //lambdaGroup.accept(new ClassPrinter());

        // visit each lambda of this package to add their implementations to the lambda group
        lambdaClassPool.classesAccept(this);

        // add an invoke method
        ProgramMethod invokeMethod = classBuilder.addAndReturnMethod(AccessConstants.PUBLIC,
                                                            "invoke",
                                                            "()Ljava/lang/Object;",
                                                            50,
                                                            this.invokeCodeBuilder.build(this.lambdaGroup, classIdField));

        // FOR DEBUGGING THE LAMBDA GROUP
        addMainMethod(initMethod);

        logger.info("Lambda group {} after adding lambda implementations:", lambdaGroupName);
        //lambdaGroup.accept(new ClassPrinter());

        // let the lambda group visitor visit the newly created lambda group
        this.lambdaGroupVisitor.visitProgramClass(lambdaGroup);

        // ensure that this newly created class is part of the resulting output
        extraDataEntryNameMap.addExtraClass(lambdaGroup.getName());
    }

    private void addMainMethod(ProgramMethod initMethod)
    {

        classBuilder.addMethod(AccessConstants.PUBLIC | AccessConstants.STATIC,
                "main",
                "([Ljava/lang/String;)V",
                50,
                code -> code
                        .new_(this.lambdaGroup)
                        .dup()
                        .iconst_0()
                        .invokespecial(this.lambdaGroup, initMethod)
                        .invokeinterface(KotlinLambdaMerger.NAME_KOTLIN_FUNCTION0, "invoke", "()Ljava/lang/Object;")
                        .return_());
    }

    private String getPackagePrefixOfClasses(ClassPool classPool)
    {
        // Assume that all classes in the given class pool are in the same package.
        String someClassName = classPool.classNames().next();
        return ClassUtil.internalPackagePrefix(someClassName);
    }

    @Override
    public void visitAnyClass(Clazz clazz) {

    }

    @Override
    public void visitProgramClass(ProgramClass lambdaClass) {
        logger.info("Adding lambda {} to lambda group {}", lambdaClass.getName(), lambdaGroup.getName());

        ProgramMethod copiedMethod = copyLambdaInvokeToLambdaGroup(lambdaClass);
        boolean copiedMethodHasReturnValue = !copiedMethod.getDescriptor(this.lambdaGroup).endsWith(")V");

        // create a new case in the switch that calls the copied invoke method
        // add a case to the table switch instruction
        //lambdaClass.attributesAccept(invokeComposer);
        // TODO: create the instructions that call the correct method
        InstructionSequenceBuilder builder = new InstructionSequenceBuilder(lambdaGroup,
                                                                            this.programClassPool,
                                                                            this.libraryClassPool);
        builder.aload_0() // load this
               .invokevirtual(this.lambdaGroup, copiedMethod); // invoke the lambda implementation
        if (!copiedMethodHasReturnValue) {
            // ensure there is a return value
            builder.getstatic("kotlin/Unit", "INSTANCE", "Lkotlin/Unit;");
        }
        builder.areturn(); // return
        Instruction[] callToMethod = builder.instructions();

        this.invokeCodeBuilder.addNewCase(callToMethod);
        int lambdaClassId = this.invokeCodeBuilder.getCaseIndexCounter() - 1;

        // replace instantiation of lambda class with instantiation of lambda group
        //  with correct id
        updateLambdaInstantiationSite(lambdaClass, lambdaClassId);
        this.lambdasToLambdaGroups.put(lambdaClass.getName(), this.lambdaGroup);
        this.lambdasToIds.put(lambdaClass.getName(), lambdaClassId);
    }

    private ProgramMethod copyLambdaInvokeToLambdaGroup(ProgramClass lambdaClass)
    {
        logger.info("Copying invoke method of {} to lambda group {}", lambdaClass.getName(), this.lambdaGroup.getName());

        // Note: the lambda class is expected to contain two invoke methods:
        //      - a bridge method that implements invoke()Ljava/lang/Object; for the Function0 interface
        //      - a specific method that contains the implementation of the lambda
        // Assumption: the specific invoke method has been inlined into the bridge invoke method, such that
        // copying the bridge method to the lambda group is sufficient to retrieve the full implementation
        ProgramMethod invokeMethod = getInvokeMethod(lambdaClass);
        String newMethodName = lambdaClass.getName().substring(lambdaClass.getName().lastIndexOf("/") + 1) + "$invoke";
        invokeMethod.accept(lambdaClass, new MethodCopier(this.lambdaGroup, newMethodName));
        return (ProgramMethod)this.lambdaGroup.findMethod(newMethodName, invokeMethod.getDescriptor(lambdaClass));
    }

    /**
     * Returns the specific invoke method of the given lambdaClass, if there is a specific invoke method,
     *  else returns the bridge invoke method if there is one, else returns null.
     * @param lambdaClass
     * @return
     */
    private ProgramMethod getInvokeMethod(ProgramClass lambdaClass)
    {
        // Assuming that all specific invoke methods have been inlined into the bridge invoke methods
        // we can take the bridge invoke method (which returns an Object)
        ProgramMethod bridgeInvokeMethod = (ProgramMethod)lambdaClass.findMethod("invoke", "()Ljava/lang/Object;");
        /*
        for (int methodIndex = 0; methodIndex < lambdaClass.u2methodsCount; methodIndex++) {
            ProgramMethod method = lambdaClass.methods[methodIndex];
            if (method.getName(lambdaClass).equals("invoke"))
            {
                // we have found an invoke method
                if (!method.getDescriptor(lambdaClass).equals("()Ljava/lang/Object;"))
                {
                    // we have found the specific invoke method
                    return method;
                } else {
                    bridgeInvokeMethod = method;
                }
            }
        }*/
        return bridgeInvokeMethod;
    }

    private void updateLambdaInstantiationSite(ProgramClass lambdaClass, int lambdaClassId)
    {
        logger.info("Updating instantiation of {} in enclosing method to use id {}.", lambdaClass.getName(), lambdaClassId);
        lambdaClass.attributesAccept(new KotlinLambdaEnclosingMethodUpdater(
                                     this.programClassPool,
                                     this.libraryClassPool,
                                     this.lambdaGroup,
                                     lambdaClassId));
    }
}