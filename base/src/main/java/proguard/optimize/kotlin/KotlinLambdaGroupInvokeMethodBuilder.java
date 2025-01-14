/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2002-2022 Guardsquare NV
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package proguard.optimize.kotlin;

import proguard.classfile.*;
import proguard.classfile.editor.*;
import proguard.classfile.instruction.Instruction;
import proguard.classfile.kotlin.KotlinConstants;
import proguard.classfile.util.ClassUtil;

import java.util.ArrayList;
import java.util.List;

public class KotlinLambdaGroupInvokeMethodBuilder {

    public static final String METHOD_ARGUMENT_TYPE_INVOKE = "Ljava/lang/Object;";
    public static final String METHOD_RETURN_TYPE_INVOKE = "Ljava/lang/Object;";
    public static final String METHOD_NAME_INVOKE = "invoke";
    public static final String METHOD_TYPE_INVOKE_FUNCTIONN = "([Ljava/lang/Object;)Ljava/lang/Object;";
    private final int arity;
    private int caseIndexCounter = 0;
    private final ClassBuilder classBuilder;
    // tune the initial list size depending on the expected amount of lambda's that are merged
    private final List<Method> methodsToBeCalled = new ArrayList<>(5);
    private final ClassPool programClassPool;
    private final ClassPool libraryClassPool;

    public KotlinLambdaGroupInvokeMethodBuilder(int arity, ClassBuilder classBuilder, ClassPool programClassPool, ClassPool libraryClassPool)
    {
        this.arity = arity;
        this.classBuilder = classBuilder;
        this.programClassPool = programClassPool;
        this.libraryClassPool = libraryClassPool;
    }

    /**
     * Adds an invocation of the given method to the invoke method that is being built.
     * @param methodToBeCalled a method that must be called by the invoke method. The method must belong to the
     *                         lambda group that is being built by the class builder of this invoke method builder.
     * @return the case index that uniquely identifies the call to the given method within the invoke method
     */
    public int addCallTo(Method methodToBeCalled)
    {
        methodsToBeCalled.add(methodToBeCalled);
        return getNewCaseIndex();
    }

    private int getNewCaseIndex()
    {
        int caseIndex = this.caseIndexCounter;
        this.caseIndexCounter++;
        return caseIndex;
    }

    /**
     * Returns the number of cases that have been added to the invoke method that is being built until now.
     */
    public int getCaseIndexCounter()
    {
        return this.caseIndexCounter;
    }

    private Instruction[] getInstructionsForCase(int caseIndex)
    {
        Method methodToBeCalled = this.methodsToBeCalled.get(caseIndex);
        InstructionSequenceBuilder builder = new InstructionSequenceBuilder(this.classBuilder.getProgramClass(),
                                                                            this.programClassPool,
                                                                            this.libraryClassPool);
        builder.invokevirtual(this.classBuilder.getProgramClass(), methodToBeCalled); // invoke the lambda implementation
        if (methodDoesNotHaveReturnValue(methodToBeCalled)) {
            // ensure there is a return value
            builder.getstatic(KotlinConstants.NAME_KOTLIN_UNIT, KotlinConstants.KOTLIN_OBJECT_INSTANCE_FIELD_NAME, KotlinConstants.TYPE_KOTLIN_UNIT);
        }
        builder.areturn(); // return
        return builder.instructions();
    }

    private boolean methodDoesNotHaveReturnValue(Method method)
    {
        String methodDescriptor = method.getDescriptor(this.classBuilder.getProgramClass());
        String returnType = ClassUtil.internalMethodReturnType(methodDescriptor);
        return returnType.equals("V");
    }

    private CompactCodeAttributeComposer addLoadArgumentsInstructions(CompactCodeAttributeComposer composer)
    {
        // load the lambda group, so later on the correct implementation methods can be called on this lambda group
        composer.aload_0();
        if (this.arity == -1)
        {
            return composer.aload_1();
        }
        for (int argumentIndex = 1; argumentIndex <= this.arity; argumentIndex++)
        {
            composer.aload(argumentIndex);
        }
        return composer;
    }

    /**
     * Returns a code builder that builds the code for the invoke method that is being built.
     */
    public ClassBuilder.CodeBuilder buildCodeBuilder()
    {
        return composer -> {
            int cases = getCaseIndexCounter();
            if (cases == 0)
            {
                composer.getstatic(KotlinConstants.NAME_KOTLIN_UNIT, KotlinConstants.KOTLIN_OBJECT_INSTANCE_FIELD_NAME, KotlinConstants.TYPE_KOTLIN_UNIT);
                composer.areturn();
                return;
            }

            CompactCodeAttributeComposer.Label[] caseLabels = new CompactCodeAttributeComposer.Label[cases - 1];
            for (int caseIndex = 0; caseIndex < cases - 1; caseIndex++)
            {
                caseLabels[caseIndex] = composer.createLabel();
            }
            CompactCodeAttributeComposer.Label defaultLabel = composer.createLabel();
            CompactCodeAttributeComposer.Label endOfMethodLabel = composer.createLabel();

            addLoadArgumentsInstructions(composer);

            if (cases > 1)
            {
                // only add a switch when there is more than one case
                // if composer.tableswitch() would be called with cases == 1, then the highCase would be lower than the lowCase
                composer
                        .aload_0()
                        .getfield(this.classBuilder.getProgramClass(),
                                  this.classBuilder.getProgramClass().findField(KotlinLambdaGroupBuilder.FIELD_NAME_ID,
                                                                                KotlinLambdaGroupBuilder.FIELD_TYPE_ID))
                        .tableswitch(defaultLabel, 0, cases - 2, caseLabels);
            }
            for (int caseIndex = 0; caseIndex < cases - 1; caseIndex++)
            {
                composer
                        .label(caseLabels[caseIndex])
                        .appendInstructions(getInstructionsForCase(caseIndex))
                        .goto_(endOfMethodLabel);
            }
            composer
                    .label(defaultLabel)
                    .appendInstructions(getInstructionsForCase(cases - 1))
                    .label(endOfMethodLabel)
                    .areturn();
        };
    }

    private static String getMethodDescriptorForArity(int arity)
    {
        // arity -1 is used for implementations of FunctionN
        if (arity == -1)
        {
            return METHOD_TYPE_INVOKE_FUNCTIONN;
        }
        StringBuilder descriptor = new StringBuilder("(");
        for (int argumentIndex = 0; argumentIndex < arity; argumentIndex++)
        {
            descriptor.append(METHOD_ARGUMENT_TYPE_INVOKE);
        }
        descriptor.append(")").append(METHOD_RETURN_TYPE_INVOKE);
        return descriptor.toString();
    }

    /**
     * Adds a new invoke method to the class builder of this invoke method builder.
     * If the new method has to replace an existing invoke method, ensure that the original invoke method
     * has been removed before calling this method.
     * @return the newly created invoke method, which has been added to the program class under construction
     */
    public ProgramMethod build()
    {
        return classBuilder.addAndReturnMethod(AccessConstants.PUBLIC | AccessConstants.SYNTHETIC,
                                               KotlinConstants.METHOD_NAME_LAMBDA_INVOKE,
                                               getMethodDescriptorForArity(this.arity),
                                               this.methodsToBeCalled.size() * 10,
                                               this.buildCodeBuilder());

    }
}
