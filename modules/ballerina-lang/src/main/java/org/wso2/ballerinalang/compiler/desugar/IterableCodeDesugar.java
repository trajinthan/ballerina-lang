/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.wso2.ballerinalang.compiler.desugar;

import org.ballerinalang.model.TreeBuilder;
import org.ballerinalang.model.elements.Flag;
import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.model.tree.IdentifierNode;
import org.ballerinalang.model.tree.OperatorKind;
import org.wso2.ballerinalang.compiler.semantics.analyzer.SymbolEnter;
import org.wso2.ballerinalang.compiler.semantics.analyzer.SymbolResolver;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolEnv;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolTable;
import org.wso2.ballerinalang.compiler.semantics.model.iterable.IterableContext;
import org.wso2.ballerinalang.compiler.semantics.model.iterable.IterableKind;
import org.wso2.ballerinalang.compiler.semantics.model.iterable.Operation;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BInvokableSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BOperatorSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BPackageSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BVarSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.types.BType;
import org.wso2.ballerinalang.compiler.tree.BLangFunction;
import org.wso2.ballerinalang.compiler.tree.BLangIdentifier;
import org.wso2.ballerinalang.compiler.tree.BLangVariable;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangArrayLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangBinaryExpr;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangIndexBasedAccess;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangInvocation;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangRecordLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangSimpleVarRef;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangUnaryExpr;
import org.wso2.ballerinalang.compiler.tree.statements.BLangAssignment;
import org.wso2.ballerinalang.compiler.tree.statements.BLangBlockStmt;
import org.wso2.ballerinalang.compiler.tree.statements.BLangExpressionStmt;
import org.wso2.ballerinalang.compiler.tree.statements.BLangForeach;
import org.wso2.ballerinalang.compiler.tree.statements.BLangIf;
import org.wso2.ballerinalang.compiler.tree.statements.BLangNext;
import org.wso2.ballerinalang.compiler.tree.statements.BLangReturn;
import org.wso2.ballerinalang.compiler.tree.statements.BLangVariableDef;
import org.wso2.ballerinalang.compiler.util.CompilerContext;
import org.wso2.ballerinalang.compiler.util.Names;
import org.wso2.ballerinalang.compiler.util.TypeTags;
import org.wso2.ballerinalang.compiler.util.diagnotic.DiagnosticPos;
import org.wso2.ballerinalang.util.Lists;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Class responsible for desugar an iterable chain into actual Ballerina code.
 *
 * @since 0.96.1
 */
public class IterableCodeDesugar {

    private static final String FUNC_CALLER = "$lambda$iterable";
    private static final String FUNC_STREAM = "$lambda$stream";
    private static final String VAR_ARG = "arg";
    private static final String VAR_SKIP = "skip";
    private static final String VAR_RESULT = "result";
    private static final String VAR_COUNT = "count";
    private static final String VAR_COLLECTION = "collection";

    private static final CompilerContext.Key<IterableCodeDesugar> DESUGAR_KEY =
            new CompilerContext.Key<>();

    private final SymbolTable symTable;
    private final SymbolResolver symResolver;
    private final SymbolEnter symbolEnter;
    private final Names names;

    private int lambdaFunctionCount = 0;
    private int variableCount = 0;

    public static IterableCodeDesugar getInstance(CompilerContext context) {
        IterableCodeDesugar desugar = context.get(DESUGAR_KEY);
        if (desugar == null) {
            desugar = new IterableCodeDesugar(context);
        }

        return desugar;
    }

    private IterableCodeDesugar(CompilerContext context) {
        context.put(DESUGAR_KEY, this);
        this.symTable = SymbolTable.getInstance(context);
        this.symResolver = SymbolResolver.getInstance(context);
        this.symbolEnter = SymbolEnter.getInstance(context);
        this.names = Names.getInstance(context);
    }

    public void desugar(IterableContext ctx) {
        rewrite(ctx);
        generateIteratorFunction(ctx);
        final BLangSimpleVarRef collectionRef = (BLangSimpleVarRef) ctx.collectionExpr;
        final BLangVariable collectionVar = createVariable(collectionRef.pos, collectionRef.variableName.value,
                collectionRef.type);
        collectionVar.symbol = collectionRef.symbol;
        ctx.iteratorCaller = createInvocationExpr(collectionRef.pos, ctx.iteratorFuncSymbol, Lists.of(collectionVar));
    }

    private void rewrite(IterableContext ctx) {
        variableCount = 0;
        ctx.operations.forEach(this::rewrite);
        ctx.collectionExpr = ctx.getFirstOperation().iExpr.expr;

    }

    private void rewrite(Operation operation) {
        // Update desugered lambda expression.
        operation.lambda = operation.lambda != null ? operation.iExpr.argExprs.get(0) : null;
        generateVariables(operation);
    }

    /**
     * calculate and generate each input and output variables of each operation.
     *
     * @param operation current operation
     */
    private void generateVariables(Operation operation) {
        // Add input types.
        if (operation.previous == null) {
            // first Operation.
            for (BType varType : operation.argTypes) {
                operation.argVars.add(createVariable(operation.pos, VAR_ARG + variableCount++, varType));
            }
        } else {
            Operation lastOperation = operation.previous;
            while (lastOperation.kind == IterableKind.FILTER) {
                lastOperation = lastOperation.previous;
            }
            operation.argVars.addAll(lastOperation.retVars);
        }
        // Add output types.
        for (BType varType : operation.resultTypes) {
            operation.retVars.add(createVariable(operation.pos, VAR_ARG + variableCount++, varType));
        }
    }

    private List<BLangVariable> copyOf(List<BLangVariable> variables) {
        List<BLangVariable> copy = new ArrayList<>();
        variables.forEach(variable -> copy.add(createVariable(variable.pos, variable.name.value, variable.type)));
        return copy;
    }

    private void generateIteratorFunction(IterableContext ctx) {
        final Operation firstOperation = ctx.getFirstOperation();
        final Operation lastOperation = ctx.getLastOperation();
        final DiagnosticPos pos = firstOperation.pos;

        // Create and define function signature.
        final BLangFunction funcNode = createFunction(pos, FUNC_CALLER);
        funcNode.params.add(createVariable(pos, VAR_COLLECTION, ctx.collectionExpr.type));
        if (isFunctionReturns(ctx)) {
            funcNode.retParams.add(createVariable(ctx.getLastOperation().pos, VAR_RESULT, ctx.resultType));
        }

        final BPackageSymbol packageSymbol = firstOperation.env.enclPkg.symbol;
        final SymbolEnv packageEnv = symbolEnter.packageEnvs.get(packageSymbol);
        symbolEnter.defineNode(funcNode, packageEnv);
        ctx.iteratorFuncSymbol = funcNode.symbol;
        packageEnv.enclPkg.functions.add(funcNode);
        packageEnv.enclPkg.topLevelNodes.add(funcNode);

        // Generate function Body.
        if (isFunctionReturns(ctx)) {
            final BLangVariable counterVar = createVariable(ctx.getLastOperation().pos, VAR_COUNT, symTable.intType);
            counterVar.expr = createLiteral(ctx.getLastOperation().pos, symTable.intType, 0);
            defineVariable(counterVar, packageSymbol.pkgID, funcNode);
            ctx.countVar = counterVar;
            generateCounterVariable(funcNode.body, ctx, counterVar);
            generateResultVariable(funcNode.body, ctx, funcNode.retParams.get(0));
        }

        // Generate foreach iteration.
        // TODO : Future improvement:- optimize single operation invocation, which doesn't need a stream function.

        // Define variable used in iteration.
        final List<BLangVariable> foreachVars = copyOf(ctx.getFirstOperation().argVars);
        foreachVars.forEach(variable -> defineVariable(variable, packageSymbol.pkgID, funcNode));

        // Generate foreach structure.
        final BLangForeach foreach = createForeach(pos, funcNode.body);
        foreach.collection = createVariableRef(pos, funcNode.params.get(0).symbol);
        foreach.varTypes = firstOperation.argTypes;
        foreach.varRefs.addAll(createVariableReferences(pos, foreachVars));
        foreach.body = createBlockStmt(pos);

        // Call Stream function and its assignment.
        List<BLangVariable> assignmentVars = new ArrayList<>();
        assignmentVars.add(createVariable(ctx.getLastOperation().pos, VAR_SKIP, symTable.booleanType));
        assignmentVars.addAll(copyOf(lastOperation.retVars));
        assignmentVars.forEach(variable -> defineVariable(variable, packageSymbol.pkgID, funcNode));
        ctx.resultOfStream = assignmentVars;

        generateStreamFunction(ctx);
        final BLangInvocation iExpr = createInvocationExpr(pos, ctx.streamFuncSymbol, foreachVars);
        BLangAssignment assignment = createAssignmentStmt(pos, foreach.body);
        assignment.expr = iExpr;
        assignment.varRefs.addAll(createVariableReferences(pos, assignmentVars));

        if (isFunctionReturns(ctx)) {
            generateSkipCondition(foreach.body, ctx);
            generateAggregator(foreach.body, ctx, funcNode.retParams.get(0));
        }

        createReturnStmt(lastOperation.pos, funcNode.body);
    }

    private void defineVariable(BLangVariable variable, PackageID pkgID, BLangFunction funcNode) {
        variable.symbol = new BVarSymbol(0, names.fromIdNode(variable.name), pkgID, variable.type, funcNode.symbol);
        funcNode.symbol.scope.define(variable.symbol.name, variable.symbol);
    }

    private boolean isFunctionReturns(IterableContext ctx) {
        return ctx.resultType != symTable.noType;
    }

    /**
     * Generates following.
     *
     * int counter = 0;
     *
     * @param blockStmt target
     * @param ctx       current context
     * @param counter   counter variable
     */
    private void generateCounterVariable(BLangBlockStmt blockStmt, IterableContext ctx, BLangVariable counter) {
        final BLangVariableDef variableDefStmt = createVariableDefStmt(ctx.getLastOperation().pos, blockStmt);
        variableDefStmt.var = counter;
    }

    /**
     * Generates following.
     *
     * array:   result =[];
     * map:     result = {};
     *
     * @param blockStmt target
     * @param ctx       current context
     * @param varResult result variable
     */
    private void generateResultVariable(BLangBlockStmt blockStmt, IterableContext ctx, BLangVariable varResult) {
        final BLangAssignment assignment = createAssignmentStmt(ctx.getLastOperation().pos, blockStmt);
        assignment.varRefs.add(createVariableRef(ctx.getLastOperation().pos, varResult.symbol));
        switch (ctx.resultType.tag) {
            case TypeTags.ARRAY:
                final BLangArrayLiteral arrayInit = (BLangArrayLiteral) TreeBuilder.createArrayLiteralNode();
                arrayInit.pos = ctx.getLastOperation().pos;
                arrayInit.exprs = new ArrayList<>();
                assignment.expr = arrayInit;
                break;
            case TypeTags.MAP:
                final BLangRecordLiteral record = (BLangRecordLiteral) TreeBuilder.createRecordLiteralNode();
                record.pos = ctx.getLastOperation().pos;
                assignment.expr = record;
                break;
        }
    }

    /**
     * Generates following.
     *
     * if(skip){
     * next;
     * }
     *
     * @param blockStmt target
     * @param ctx       current context
     */
    private void generateSkipCondition(BLangBlockStmt blockStmt, IterableContext ctx) {
        final DiagnosticPos pos = ctx.getLastOperation().pos;
        final BLangIf ifNode = createIfStmt(pos, blockStmt);
        ifNode.expr = createVariableRef(pos, ctx.streamFuncSymbol.retParams.get(0));
        ifNode.body = createBlockStmt(pos);
        createNextStmt(pos, ifNode.body);
    }

    /* Aggregator related code generation */

    /**
     * Generates target aggregator logic.
     *
     * @param blockStmt target
     * @param ctx       current context
     * @param varResult result variable
     */
    private void generateAggregator(BLangBlockStmt blockStmt, IterableContext ctx, BLangVariable varResult) {
        switch (ctx.resultType.tag) {
            case TypeTags.ARRAY:
                generateArrayAggregator(blockStmt, ctx, varResult);
                break;
            case TypeTags.MAP:
                generateMapAggregator(blockStmt, ctx, varResult);
                break;
        }
    }

    /**
     * Generates following.
     *
     * result[count] = value;
     * count = count + 1;
     *
     * @param blockStmt target
     * @param ctx       current context
     * @param varResult result variable
     */
    private void generateArrayAggregator(BLangBlockStmt blockStmt, IterableContext ctx, BLangVariable varResult) {
        final DiagnosticPos pos = ctx.getLastOperation().pos;
        // create assignment result[count] = value;
        final BLangIndexBasedAccess indexAccessNode = (BLangIndexBasedAccess) TreeBuilder.createIndexBasedAccessNode();
        indexAccessNode.pos = pos;
        indexAccessNode.indexExpr = createVariableRef(pos, ctx.countVar.symbol);
        indexAccessNode.expr = createVariableRef(pos, varResult.symbol);
        final BLangAssignment valueAssign = createAssignmentStmt(pos, blockStmt);
        valueAssign.varRefs.add(indexAccessNode);
        valueAssign.expr = createVariableRef(pos, ctx.resultOfStream.get(1).symbol);

        // create count = count + 1;
        final BLangBinaryExpr add = (BLangBinaryExpr) TreeBuilder.createBinaryExpressionNode();
        add.pos = pos;
        add.opKind = OperatorKind.ADD;
        add.lhsExpr = createVariableRef(pos, ctx.countVar.symbol);
        add.rhsExpr = createLiteral(pos, symTable.intType, 1);
        add.opSymbol = (BOperatorSymbol) symResolver.resolveBinaryOperator(OperatorKind.ADD, symTable.intType,
                symTable.intType);
        final BLangAssignment countAdd = createAssignmentStmt(pos, blockStmt);
        countAdd.varRefs.add(createVariableRef(pos, ctx.countVar.symbol));
        countAdd.expr = add;
    }

    /**
     * Generates following.
     *
     * result[key] = value;
     *
     * @param blockStmt target
     * @param ctx       current context
     * @param varResult result variable
     */
    private void generateMapAggregator(BLangBlockStmt blockStmt, IterableContext ctx, BLangVariable varResult) {
        final DiagnosticPos pos = ctx.getLastOperation().pos;
        // create assignment result[key] = value
        final BLangIndexBasedAccess indexAccessNode = (BLangIndexBasedAccess) TreeBuilder.createIndexBasedAccessNode();
        indexAccessNode.pos = pos;
        indexAccessNode.indexExpr = createVariableRef(pos, ctx.resultOfStream.get(1).symbol);
        indexAccessNode.expr = createVariableRef(pos, varResult.symbol);
        final BLangAssignment valueAssign = createAssignmentStmt(pos, blockStmt);
        valueAssign.varRefs.add(indexAccessNode);
        valueAssign.expr = createVariableRef(pos, ctx.resultOfStream.get(2).symbol);
    }


    /**
     * Generate Stream function from operation chain.
     *
     * @param ctx current context
     */
    private void generateStreamFunction(IterableContext ctx) {
        // Create and define function signature.
        final BLangFunction funcNode = createFunction(ctx.getFirstOperation().pos, FUNC_STREAM);
        funcNode.params.addAll(ctx.getFirstOperation().argVars);
        funcNode.retParams.add(createVariable(ctx.getLastOperation().pos, VAR_SKIP, symTable.booleanType));
        funcNode.retParams.addAll(ctx.getLastOperation().retVars);

        final BPackageSymbol packageSymbol = ctx.getFirstOperation().env.enclPkg.symbol;
        final SymbolEnv packageEnv = symbolEnter.packageEnvs.get(packageSymbol);
        symbolEnter.defineNode(funcNode, packageEnv);
        ctx.streamFuncSymbol = funcNode.symbol;
        packageEnv.enclPkg.functions.add(funcNode);
        packageEnv.enclPkg.topLevelNodes.add(funcNode);

        // Define all undefined variables.
        Set<BLangVariable> usedVars = new HashSet<>();
        ctx.operations.forEach(operation -> {
            usedVars.addAll(operation.argVars);
            usedVars.addAll(operation.retVars);
        });
        usedVars.removeAll(funcNode.params);
        usedVars.removeAll(funcNode.retParams);
        usedVars.forEach(variable -> defineVariable(variable, packageSymbol.pkgID, funcNode));
        // Generate function Body.
        ctx.operations.forEach(operation -> generateOperationCode(funcNode.body, operation, ctx));
        generateStreamReturnStmt(funcNode.body, ctx.getLastOperation().retVars);
    }

    /**
     * Generates following.
     *
     * return true, vx... ;
     *
     * @param blockStmt target
     * @param retArgs   return variables
     */
    private void generateStreamReturnStmt(BLangBlockStmt blockStmt, List<BLangVariable> retArgs) {
        final DiagnosticPos pos = blockStmt.pos;
        final BLangReturn returnStmt = createReturnStmt(pos, blockStmt);

        returnStmt.exprs.add(createLiteral(pos, symTable.booleanType, false));
        returnStmt.exprs.addAll(createVariableReferences(pos, retArgs));
    }

    /**
     * Generates Operation related code.
     *
     * @param blockStmt target
     * @param operation current operation
     * @param ctx       current context
     */
    private void generateOperationCode(BLangBlockStmt blockStmt, Operation operation, IterableContext ctx) {
        switch (operation.kind) {
            case FOREACH:
                generateForeach(blockStmt, operation);
                break;
            case FILTER:
                generateFilter(blockStmt, operation, ctx);
                break;
            case MAP:
                generateMap(blockStmt, operation);
                break;
        }
    }

    /* Operation related code generation */

    /**
     * Generates statements for foreach operation.
     *
     * lambda(...)
     *
     * @param blockStmt target
     * @param operation operation instance
     */
    private void generateForeach(BLangBlockStmt blockStmt, Operation operation) {
        final DiagnosticPos pos = operation.pos;
        final BLangExpressionStmt exprStmt = createExpressionStmt(pos, blockStmt);
        exprStmt.expr = createInvocationExpr(pos, (BInvokableSymbol) ((BLangSimpleVarRef) operation.lambda).symbol,
                operation.argVars);
    }

    /**
     * Generates statements for filter operation.
     *
     * if(!lambda(...)){
     * skip = true;
     * return;
     * }
     *
     * @param blockStmt target
     * @param operation operation instance
     * @param ctx       current context
     */
    private void generateFilter(BLangBlockStmt blockStmt, Operation operation, IterableContext ctx) {
        final DiagnosticPos pos = operation.pos;

        final BLangIf ifNode = createIfStmt(pos, blockStmt);
        final BLangUnaryExpr notExpr = (BLangUnaryExpr) TreeBuilder.createUnaryExpressionNode();
        notExpr.pos = pos;
        notExpr.operator = OperatorKind.NOT;
        notExpr.opSymbol = (BOperatorSymbol) symResolver.resolveUnaryOperator(pos, notExpr.operator,
                symTable.booleanType);
        notExpr.expr = createInvocationExpr(pos, (BInvokableSymbol) ((BLangSimpleVarRef) operation.lambda).symbol,
                operation.argVars);
        ifNode.expr = notExpr;
        ifNode.body = createBlockStmt(pos);

        final BLangAssignment assignment = createAssignmentStmt(pos, ifNode.body);
        assignment.varRefs.add(createVariableRef(pos, ctx.streamFuncSymbol.retParams.get(0)));
        assignment.expr = createLiteral(pos, symTable.booleanType, true);

        createReturnStmt(pos, ifNode.body);

    }

    /**
     * Generates statements for filter operation.
     *
     * v3,v4 = lambda(v1,v2);
     *
     * @param blockStmt target
     * @param operation operation instance
     */
    private void generateMap(BLangBlockStmt blockStmt, Operation operation) {
        final DiagnosticPos pos = operation.pos;
        final BLangAssignment assignment = createAssignmentStmt(pos, blockStmt);
        assignment.varRefs.addAll(createVariableReferences(operation.pos, operation.retVars));
        assignment.expr = createInvocationExpr(pos, (BInvokableSymbol) ((BLangSimpleVarRef) operation.lambda).symbol,
                operation.argVars);
    }


    /* Util methods to create model nodes */

    private BLangFunction createFunction(DiagnosticPos pos, String name) {
        final BLangFunction bLangFunction = (BLangFunction) TreeBuilder.createFunctionNode();
        final IdentifierNode funcName = createIdentifier(pos, getFunctionName(name));
        bLangFunction.setName(funcName);
        bLangFunction.flagSet = EnumSet.of(Flag.LAMBDA);
        bLangFunction.pos = pos;
        //Create body of the function
        bLangFunction.body = createBlockStmt(bLangFunction.pos);
        return bLangFunction;
    }

    private BLangIf createIfStmt(DiagnosticPos pos, BLangBlockStmt target) {
        final BLangIf ifNode = (BLangIf) TreeBuilder.createIfElseStatementNode();
        ifNode.pos = pos;
        target.addStatement(ifNode);
        return ifNode;
    }

    private BLangForeach createForeach(DiagnosticPos pos, BLangBlockStmt target) {
        final BLangForeach foreach = (BLangForeach) TreeBuilder.createForeachNode();
        foreach.pos = pos;
        target.addStatement(foreach);
        return foreach;
    }

    private BLangVariableDef createVariableDefStmt(DiagnosticPos pos, BLangBlockStmt target) {
        final BLangVariableDef variableDef = (BLangVariableDef) TreeBuilder.createVariableDefinitionNode();
        variableDef.pos = pos;
        target.addStatement(variableDef);
        return variableDef;
    }

    private BLangAssignment createAssignmentStmt(DiagnosticPos pos, BLangBlockStmt target) {
        final BLangAssignment assignment = (BLangAssignment) TreeBuilder.createAssignmentNode();
        assignment.pos = pos;
        target.addStatement(assignment);
        return assignment;
    }

    private BLangExpressionStmt createExpressionStmt(DiagnosticPos pos, BLangBlockStmt target) {
        final BLangExpressionStmt exprStmt = (BLangExpressionStmt) TreeBuilder.createExpressionStatementNode();
        exprStmt.pos = pos;
        target.addStatement(exprStmt);
        return exprStmt;
    }

    private BLangReturn createReturnStmt(DiagnosticPos pos, BLangBlockStmt target) {
        final BLangReturn returnStmt = (BLangReturn) TreeBuilder.createReturnNode();
        returnStmt.pos = pos;
        target.addStatement(returnStmt);
        return returnStmt;
    }

    private void createNextStmt(DiagnosticPos pos, BLangBlockStmt target) {
        final BLangNext nextStmt = (BLangNext) TreeBuilder.createNextNode();
        nextStmt.pos = pos;
        target.addStatement(nextStmt);
    }

    private BLangBlockStmt createBlockStmt(DiagnosticPos pos) {
        final BLangBlockStmt blockNode = (BLangBlockStmt) TreeBuilder.createBlockNode();
        blockNode.pos = pos;
        return blockNode;
    }

    private BLangInvocation createInvocationExpr(DiagnosticPos pos, BInvokableSymbol invokableSymbol,
                                                 List<BLangVariable> args) {
        final BLangInvocation invokeLambda = (BLangInvocation) TreeBuilder.createInvocationNode();
        invokeLambda.pos = pos;
        invokeLambda.argExprs.addAll(createVariableReferences(pos, args));
        invokeLambda.symbol = invokableSymbol;
        invokableSymbol.retParams.forEach(symbol -> invokeLambda.types.add(symbol.type));
        return invokeLambda;
    }

    private List<BLangSimpleVarRef> createVariableReferences(DiagnosticPos pos, List<BLangVariable> args) {
        final List<BLangSimpleVarRef> varRefs = new ArrayList<>();
        args.forEach(variable -> varRefs.add(createVariableRef(pos, variable.symbol)));
        return varRefs;
    }

    private BLangSimpleVarRef createVariableRef(DiagnosticPos pos, BVarSymbol variable) {
        final BLangSimpleVarRef varRef = (BLangSimpleVarRef) TreeBuilder.createSimpleVariableReferenceNode();
        varRef.pos = pos;
        varRef.variableName = createIdentifier(pos, variable.name.value);
        varRef.symbol = variable;
        varRef.type = variable.type;
        return varRef;
    }

    private BLangVariable createVariable(DiagnosticPos pos, String name, BType type) {
        final BLangVariable varNode = (BLangVariable) TreeBuilder.createVariableNode();
        varNode.setName(createIdentifier(pos, name));
        varNode.type = type;
        varNode.pos = pos;
        return varNode;
    }

    private BLangLiteral createLiteral(DiagnosticPos pos, BType type, Object value) {
        final BLangLiteral literal = (BLangLiteral) TreeBuilder.createLiteralExpression();
        literal.pos = pos;
        literal.value = value;
        literal.typeTag = type.tag;
        literal.type = type;
        return literal;
    }

    private BLangIdentifier createIdentifier(DiagnosticPos pos, String value) {
        final BLangIdentifier node = (BLangIdentifier) TreeBuilder.createIdentifierNode();
        node.pos = pos;
        if (value != null) {
            node.setValue(value);
        }
        return node;
    }

    private String getFunctionName(String name) {
        return name + lambdaFunctionCount++;
    }

}
