package com.android.identity.processor

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference

/**
 * Kotlin Annotation Processor that generates dispatching code and stub
 * implementations for flow-based network calls. It processes annotations
 * defined in [com.android.identity.flow.annotation] package.
 */
class FlowSymbolProcessor(
    private val options: Map<String, String>,
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {

    companion object {
        const val ANNOTATION_PACKAGE = "com.android.identity.flow.annotation"
        const val ANNOTATION_STATE = "FlowState"
        const val ANNOTATION_INTERFACE = "FlowInterface"
        const val ANNOTATION_EXCEPTION = "FlowException"
        const val ANNOTATION_METHOD = "FlowMethod"
        const val ANNOTATION_JOIN = "FlowJoin"
        const val FLOW_DISPATCHER = "com.android.identity.flow.handler.FlowDispatcher"
        const val FLOW_NOTIFIER = "com.android.identity.flow.handler.FlowNotifier"
        const val BASE_INTERFACE = "com.android.identity.flow.client.FlowBase"
        const val NOTIFIABLE_INTERFACE = "com.android.identity.flow.client.FlowNotifiable"
        const val FLOW_DISPATCHER_LOCAL = "com.android.identity.flow.handler.FlowDispatcherLocal"
        const val FLOW_RETURN_CODE = "com.android.identity.flow.handler.FlowReturnCode"
        const val FLOW_EXCEPTION_MAP = "com.android.identity.flow.handler.FlowExceptionMap"
        const val FLOW_ENVIRONMENT = "com.android.identity.flow.server.FlowEnvironment"
        const val FLOW_NOTIFICATIONS = "com.android.identity.flow.handler.FlowNotifications"
        const val MUTABLE_SHARED_FLOW_CLASS = "kotlinx.coroutines.flow.MutableSharedFlow"
        const val SHARED_FLOW_CLASS = "kotlinx.coroutines.flow.SharedFlow"
        const val AS_SHARED_FLOW = "kotlinx.coroutines.flow.asSharedFlow"
        const val FLOW_MAP = "kotlinx.coroutines.flow.map"

        val stateSuffix = Regex("State$")
    }

    /**
     * Processor main entry point.
     */
    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getSymbolsWithAnnotation("$ANNOTATION_PACKAGE.$ANNOTATION_STATE")
            .filterIsInstance<KSClassDeclaration>().forEach(this::processStateClass)
        resolver.getSymbolsWithAnnotation("$ANNOTATION_PACKAGE.$ANNOTATION_INTERFACE")
            .filterIsInstance<KSClassDeclaration>().forEach(this::processFlowInterface)
        resolver.getSymbolsWithAnnotation("$ANNOTATION_PACKAGE.$ANNOTATION_EXCEPTION")
            .filterIsInstance<KSClassDeclaration>().forEach(this::processException)
        return listOf()
    }

    private fun processStateClass(stateClass: KSClassDeclaration) {
        if (stateClass.isAbstract()) {
            // Don't generate anything for abstract state classes
            return
        }
        val annotation = findAnnotation(stateClass, ANNOTATION_STATE)
        val path = getPathFromStateType(stateClass)
        val creatable = getBooleanArgument(annotation, "creatable", false)
        val flowInterface = getClassArgument(annotation, "flowInterface")
        val notificationType = if (flowInterface == null) null else notificationType(flowInterface)
        val flowInterfaceName = getFlowInterfaceName(stateClass, annotation)!!
        val joins = mutableMapOf<String, String>()  // interface to path
        val operations = mutableListOf<FlowOperationInfo>()
        val companionClass = CborSymbolProcessor.getCompanion(stateClass)
        if (companionClass == null) {
            logger.error("Companion object required", stateClass)
            return
        }

        collectFlowJoins(stateClass, joins, operations)
        collectFlowMethods(stateClass, joins, operations)

        val lastDot = flowInterfaceName.lastIndexOf('.')
        val interfacePackage = flowInterfaceName.substring(0, lastDot)
        val interfaceName = flowInterfaceName.substring(lastDot + 1)
        val flowInfo = FlowInterfaceInfo(
            path, stateClass, interfacePackage, interfaceName, notificationType, operations.toList()
        )

        if (flowInterface == null) {
            val flowImplName = getStringArgument(
                annotation, "flowImplementationName", flowInterfaceName + "Impl"
            )
            val containingFile = stateClass.containingFile!!
            generateFlowInterface(flowInfo)
            generateFlowImplementation(
                containingFile, flowInterfaceName, interfaceName, null, flowImplName, operations
            )
        }
        generateFlowRegistration(creatable, flowInfo)
    }

    private fun processFlowInterface(flowClass: KSClassDeclaration) {
        val annotation = findAnnotation(flowClass, ANNOTATION_INTERFACE)
        val operations = mutableListOf<FlowOperationInfo>()
        flowClass.getAllFunctions().forEach { function ->
            val methodAnnotation = findAnnotation(function, ANNOTATION_METHOD) ?: return@forEach
            val returnType = function.returnType?.resolve()
            val type = if (returnType?.declaration?.qualifiedName?.asString() == "kotlin.Unit") {
                null
            } else {
                returnType
            }
            val parameters = mutableListOf<FlowOperationParameterInfo>()
            function.parameters.forEach { parameter ->
                val parameterType = parameter.type.resolve()
                val parameterName = parameter.name?.asString()
                if (parameterName == null) {
                    logger.error("Parameter name required", parameter)
                } else {
                    val clientTypeInfo = getInterfaceFlowTypeInfo(parameterType)
                    parameters.add(
                        FlowOperationParameterInfo(
                            parameterName, clientTypeInfo, parameterType
                        )
                    )
                }
            }
            val clientTypeInfo = getInterfaceFlowTypeInfo(type)
            val methodName = function.simpleName.getShortName()
            val methodPath = getStringArgument(methodAnnotation, "path", methodName)
            operations.add(
                FlowOperationInfo(
                    false, methodPath, methodName, parameters, type, clientTypeInfo
                )
            )
        }
        val notificationType = notificationType(flowClass)
        val interfaceName = flowClass.simpleName.asString()
        val interfaceFullName = flowClass.qualifiedName!!.asString()
        val flowImplName = getStringArgument(
            annotation, "flowImplementationName", "${interfaceFullName}Impl"
        )
        val containingFile = flowClass.containingFile!!
        generateFlowImplementation(containingFile, interfaceFullName, interfaceName,
            notificationType, flowImplName, operations)
    }

    private fun processException(exceptionClass: KSClassDeclaration) {
        val companionClass = CborSymbolProcessor.getCompanion(exceptionClass)
        if (companionClass == null) {
            logger.error("Companion object required", exceptionClass)
            return
        }

        val baseName = exceptionClass.simpleName.asString()
        val annotation = findAnnotation(exceptionClass, ANNOTATION_EXCEPTION)
        val exceptionId = getStringArgument(
            annotation = annotation,
            name = "exceptionId",
            defaultValue = if (baseName.endsWith("Exception")) {
                baseName.substring(0, baseName.length - 9)
            } else {
                baseName
            }
        )
        val containingFile = exceptionClass.containingFile!!
        val packageName = exceptionClass.packageName.asString()
        val type = exceptionClass.asType(listOf())
        with(CodeBuilder()) {
            importQualifiedName(CborSymbolProcessor.DATA_ITEM_CLASS)
            importQualifiedName(exceptionClass)
            importQualifiedName(FLOW_EXCEPTION_MAP)
            importQualifiedName(CborSymbolProcessor.BSTR_TYPE)

            block("fun $baseName.Companion.register(exceptionMapBuilder: FlowExceptionMap.Builder)") {
                line("exceptionMapBuilder.addException<$baseName>(")
                withIndent {
                    line("\"$exceptionId\",")
                    block("", hasBlockAfter = true, lambdaParameters = "exception") {
                        line(CborSymbolProcessor.serializeValue(
                            this, "exception", type
                        ))
                    }
                    append(",")
                    endLine()
                    block("", hasBlockAfter = true, lambdaParameters = "dataItem") {
                        line(CborSymbolProcessor.deserializeValue(
                            this, "dataItem", type
                        ))
                    }
                    append(",")
                    endLine()
                    line("listOf(")
                    withIndent {
                        line("$baseName::class,")
                        for (subclass in exceptionClass.getSealedSubclasses()) {
                            importQualifiedName(subclass)
                            line("${subclass.simpleName.asString()}::class,")
                        }
                        line(")")
                    }
                }
                line(")")
            }

            writeToFile(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(false, containingFile),
                packageName = packageName,
                fileName = "${baseName}_Registration"
            )
        }
    }

    private fun collectFlowJoins(
        stateClass: KSClassDeclaration,
        joins: MutableMap<String, String>,
        operations: MutableList<FlowOperationInfo>
    ) {
        stateClass.getAllFunctions().forEach { function ->
            val joinAnnotation = findAnnotation(function, ANNOTATION_JOIN) ?: return@forEach
            val returnType = function.returnType?.resolve()?.declaration?.qualifiedName?.asString()
            if (returnType != null && returnType != "kotlin.Unit") {
                logger.error("FlowJoin methods must not return any value", function)
                return@forEach
            }
            val params = function.parameters
            if (params.size != 2) {
                logger.error("FlowJoin method must take 2 parameters", function)
                return@forEach
            }
            val param0Type = params[0].type.resolve()
            if (param0Type.declaration.qualifiedName?.asString() != FLOW_ENVIRONMENT) {
                logger.error(
                    "FlowJoin method's first parameter is not FlowEnvironment", function
                )
                return@forEach
            }
            val type = params[1].type.resolve()
            val typeInfo = getStateFlowTypeInfo(type)
            if (typeInfo == null) {
                logger.error("FlowJoin method's second parameter is not a flow state", function)
                return@forEach
            }
            val methodName = function.simpleName.getShortName()
            val methodPath = getStringArgument(joinAnnotation, "path", methodName)
            if (joins.containsKey(typeInfo.qualifiedInterfaceName)) {
                logger.error("Duplicate flow join for ${typeInfo.qualifiedInterfaceName}", function)
            } else {
                joins[typeInfo.qualifiedInterfaceName] = methodPath
                operations.add(
                    FlowOperationInfo(
                        true, methodPath, methodName, listOf(
                            FlowOperationParameterInfo("joiningFlow", typeInfo, type)
                        ), null, null
                    )
                )
            }
        }
    }

    private fun collectFlowMethods(
        stateClass: KSClassDeclaration,
        joins: Map<String, String>,
        operations: MutableList<FlowOperationInfo>
    ) {
        stateClass.getAllFunctions().forEach { function ->
            val methodAnnotation = findAnnotation(function, ANNOTATION_METHOD) ?: return@forEach
            val returnType = function.returnType?.resolve()
            val type = if (returnType?.declaration?.qualifiedName?.asString() == "kotlin.Unit") {
                null
            } else {
                returnType
            }
            val parameters = mutableListOf<FlowOperationParameterInfo>()
            var index = 0
            function.parameters.forEach { parameter ->
                val parameterType = parameter.type.resolve()
                if (index == 0) {
                    if (parameterType.declaration.qualifiedName?.asString() != FLOW_ENVIRONMENT) {
                        logger.error("First parameter must be FlowEnvironment", parameter)
                    }
                } else {
                    val parameterName = parameter.name?.asString()
                    if (parameterName == null) {
                        logger.error("Parameter name required", parameter)
                    } else {
                        val clientTypeInfo = getStateFlowTypeInfo(parameterType)
                        parameters.add(
                            FlowOperationParameterInfo(
                                parameterName, clientTypeInfo, parameterType
                            )
                        )
                    }
                }
                index++
            }
            val clientTypeInfo = getStateFlowTypeInfo(type)
            if (clientTypeInfo != null) {
                clientTypeInfo.joinPath = joins[clientTypeInfo.qualifiedInterfaceName]
            }
            val methodName = function.simpleName.getShortName()
            val methodPath = getStringArgument(methodAnnotation, "path", methodName)
            operations.add(
                FlowOperationInfo(
                    false, methodPath, methodName, parameters, type, clientTypeInfo
                )
            )
        }
    }

    private fun generateFlowInterface(flowInfo: FlowInterfaceInfo) {
        with(CodeBuilder()) {
            importQualifiedName(CborSymbolProcessor.DATA_ITEM_CLASS)
            importQualifiedName(FLOW_DISPATCHER)
            importQualifiedName(BASE_INTERFACE)

            emptyLine()
            block("interface ${flowInfo.interfaceName}: $BASE_INTERFACE") {
                flowInfo.operations.forEach { op ->
                    if (!op.hidden) {
                        line("suspend fun ${opDeclaration(this, op)}")
                    }
                }
            }

            writeToFile(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(false, flowInfo.stateClass.containingFile!!),
                packageName = flowInfo.interfacePackage,
                fileName = flowInfo.interfaceName
            )
        }
    }

    private fun generateFlowImplementation(
        containingFile: KSFile,
        interfaceFullName: String,
        interfaceName: String,
        notificationType: KSType?,
        flowImplName: String,
        operations: List<FlowOperationInfo>
    ) {
        val lastDotImpl = flowImplName.lastIndexOf('.')
        val packageName = flowImplName.substring(0, lastDotImpl)
        val baseName = flowImplName.substring(lastDotImpl + 1)
        with(CodeBuilder()) {
            importQualifiedName(CborSymbolProcessor.DATA_ITEM_CLASS)
            importQualifiedName(FLOW_DISPATCHER)
            importQualifiedName(FLOW_NOTIFIER)
            importQualifiedName(FLOW_RETURN_CODE)
            importQualifiedName(CborSymbolProcessor.BSTR_TYPE)
            importQualifiedName(interfaceFullName)

            emptyLine()
            line("class $baseName(")
            withIndent {
                line("private val flowPath: String,")
                line("override var flowState: DataItem,")
                line("private val flowDispatcher: FlowDispatcher,")
                line("private val flowNotifier: FlowNotifier,")
                line("private val onComplete: suspend (DataItem) -> Unit = {}")
            }
            block("): $interfaceName") {
                line("private var flowComplete = false")
                if (notificationType != null) {
                    val simpleName = notificationType.declaration.simpleName.asString()
                    if (notificationType.declaration is KSClassDeclaration) {
                        importQualifiedName(notificationType.declaration as KSClassDeclaration)
                    }
                    importQualifiedName(MUTABLE_SHARED_FLOW_CLASS)
                    importQualifiedName(SHARED_FLOW_CLASS)
                    importQualifiedName(AS_SHARED_FLOW)
                    importQualifiedName(FLOW_MAP)
                    line("private val notificationFlow = MutableSharedFlow<$simpleName>()")
                    line("override val notifications: SharedFlow<$simpleName>")
                    withIndent {
                        line("get() = notificationFlow.asSharedFlow()")
                    }
                    emptyLine()
                    importQualifiedName(CborSymbolProcessor.BYTESTRING_TYPE)
                    block("suspend fun startNotifications()") {
                        block("flowNotifier.register(flowPath, flowState, notificationFlow)") {
                            line(CborSymbolProcessor.deserializeValue(this, "it", notificationType))
                        }
                    }
                    emptyLine()
                    block("suspend fun stopNotifications()") {
                        line("flowNotifier.unregister(flowPath, flowState)")
                    }
                }
                operations.forEach { op ->
                    if (!op.hidden) {
                        generateFlowMethodStub(this, notificationType != null, op)
                    }
                }
                emptyLine()
                block("override suspend fun complete()") {
                    line("checkFlowNotComplete()")
                    if (notificationType != null) {
                        line("stopNotifications()")
                    }
                    line("onComplete(flowState)")
                    line("flowState = Bstr(byteArrayOf())")
                    line("flowComplete = true")
                }
                emptyLine()
                block("private fun checkFlowNotComplete()") {
                    block("if (flowComplete)") {
                        line("throw IllegalStateException(\"flow is already complete\")")
                    }
                }
            }

            writeToFile(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(false, containingFile),
                packageName = packageName,
                fileName = baseName
            )
        }
    }

    private fun generateFlowMethodStub(
        codeBuilder: CodeBuilder,
        notifications: Boolean,
        op: FlowOperationInfo
    ) {
        with(codeBuilder) {
            emptyLine()
            block("override suspend fun ${opDeclaration(this, op)}") {
                line("checkFlowNotComplete()")
                val parameters = op.parameters.map { parameter ->
                    if (parameter.flowTypeInfo == null) {
                        CborSymbolProcessor.serializeValue(
                            this, parameter.name, parameter.type
                        )
                    } else {
                        "${parameter.name}.flowState"
                    }
                }
                line("val flowParameters = listOf<DataItem>(")
                withIndent {
                    line("this.flowState,")
                    parameters.forEach { parameter ->
                        line("$parameter,")
                    }
                }
                line(")")
                line("val flowMethodResponse = flowDispatcher.dispatch(flowPath, \"${op.path}\", flowParameters)")
                if (notifications) {
                    block("if (this.flowState != flowMethodResponse[0])") {
                        line("stopNotifications()")
                        line("this.flowState = flowMethodResponse[0]")
                        line("startNotifications()")
                    }
                } else {
                    line("this.flowState = flowMethodResponse[0]")
                }
                block("if (flowMethodResponse[1].asNumber.toInt() == FlowReturnCode.EXCEPTION.ordinal)") {
                    line("this.flowDispatcher.exceptionMap.handleExceptionReturn(flowMethodResponse)")
                }
                if (op.flowTypeInfo != null) {
                    importQualifiedName(op.flowTypeInfo.qualifiedImplName)
                    val ctr = op.flowTypeInfo.simpleImplName
                    line("val packedFlow = flowMethodResponse[2].asArray")
                    line("val flowName = packedFlow[0].asTstr")
                    line("val joinPath = packedFlow[1].asTstr")
                    block(
                        "val result = $ctr(flowName, packedFlow[2], flowDispatcher, flowNotifier)",
                        lambdaParameters = "joiningState"
                    ) {
                        block("if (joinPath.isNotEmpty())") {
                            importQualifiedName(CborSymbolProcessor.TSTR_TYPE)
                            importQualifiedName(CborSymbolProcessor.CBOR_ARRAY_TYPE)
                            line("val joinStateArray = CborArray(mutableListOf(Tstr(flowName), joiningState))")
                            line("val joinArgs = listOf(this.flowState, joinStateArray)")
                            line("val joinResponse = flowDispatcher.dispatch(flowPath, joinPath, joinArgs)")
                            if (notifications) {
                                block("if (this.flowState != joinResponse[0])") {
                                    line("stopNotifications()")
                                    line("this.flowState = joinResponse[0]")
                                    line("startNotifications()")
                                }
                            } else {
                                line("this.flowState = joinResponse[0]")
                            }
                        }
                    }
                    if (op.flowTypeInfo.notifications) {
                        line("result.startNotifications()")
                    }
                    line("return result")
                } else if (op.type != null) {
                    val result = CborSymbolProcessor.deserializeValue(
                        this, "flowMethodResponse[2]", op.type
                    )
                    line("return $result")
                }
            }
        }
    }

    private fun getPathFromStateType(declaration: KSDeclaration): String {
        val baseName = declaration.simpleName.asString()
        val annotation = findAnnotation(declaration, ANNOTATION_STATE)
        return getStringArgument(
            annotation = annotation,
            name = "path",
            defaultValue = if (baseName.endsWith("State")) {
                baseName.substring(0, baseName.length - 5)
            } else {
                baseName
            }
        )
    }

    private fun getStateFlowTypeInfo(type: KSType?): FlowTypeInfo? {
        if (type == null) {
            return null
        }
        val annotation = findAnnotation(type.declaration, ANNOTATION_STATE)
        val fullInterfaceName = getFlowInterfaceName(
            type.declaration as KSClassDeclaration, annotation
        ) ?: return null
        val notificationType = notificationType(type.declaration as KSClassDeclaration)
        val simpleInterfaceName =
            fullInterfaceName.substring(fullInterfaceName.lastIndexOf('.') + 1)
        val fullImplName = getStringArgument(
            annotation, "flowImplementationName", fullInterfaceName + "Impl"
        )
        val simpleImplName = fullImplName.substring(fullImplName.lastIndexOf('.') + 1)
        return FlowTypeInfo(
            fullInterfaceName,
            simpleInterfaceName,
            fullImplName,
            simpleImplName,
            notificationType != null,
            getPathFromStateType(type.declaration)
        )
    }

    private fun getInterfaceFlowTypeInfo(type: KSType?): FlowTypeInfo? {
        if (type == null) {
            return null
        }
        val annotation = findAnnotation(type.declaration, ANNOTATION_INTERFACE) ?: return null
        val fullInterfaceName = type.declaration.qualifiedName!!.asString()
        val notificationType = notificationType(type.declaration as KSClassDeclaration)
        val simpleInterfaceName = type.declaration.simpleName.asString()
        val fullImplName = getStringArgument(
            annotation, "flowImplementationName", fullInterfaceName + "Impl"
        )
        val simpleImplName = fullImplName.substring(fullImplName.lastIndexOf('.') + 1)
        return FlowTypeInfo(
            fullInterfaceName,
            simpleInterfaceName,
            fullImplName,
            simpleImplName,
            notificationType != null,
            null
        )
    }

    private fun getFlowInterfaceName(
        stateClass: KSClassDeclaration, annotation: KSAnnotation?
    ): String? {
        if (annotation == null) {
            return null
        }
        val flowInterface = getClassArgument(annotation, "flowInterface")
        return if (flowInterface != null) {
            flowInterface.qualifiedName!!.asString()
        } else {
            val defaultName =
                stateSuffix.replace(stateClass.qualifiedName!!.asString(), "") + "Flow"
            getStringArgument(annotation, "flowInterfaceName", defaultName)
        }
    }

    private fun opDeclaration(codeBuilder: CodeBuilder, op: FlowOperationInfo): String {
        val signature = signature(codeBuilder, op)
        if (op.type == null) {
            return "${op.name}($signature)"
        } else {
            val type = if (op.flowTypeInfo != null) {
                codeBuilder.importQualifiedName(op.flowTypeInfo.qualifiedInterfaceName)
                op.flowTypeInfo.simpleInterfaceName
            } else {
                CborSymbolProcessor.typeRef(codeBuilder, op.type)
            }
            return "${op.name}($signature): $type"
        }
    }

    private fun signature(codeBuilder: CodeBuilder, op: FlowOperationInfo): String {
        val builder = StringBuilder()
        op.parameters.forEach { parameter ->
            if (builder.isNotEmpty()) {
                builder.append(", ")
            }
            val type = if (parameter.flowTypeInfo != null) {
                codeBuilder.importQualifiedName(parameter.flowTypeInfo.qualifiedInterfaceName)
                parameter.flowTypeInfo.simpleInterfaceName
            } else {
                CborSymbolProcessor.typeRef(codeBuilder, parameter.type)
            }
            builder.append("${parameter.name}: $type")
        }
        return builder.toString()
    }

    private fun generateFlowRegistration(creatable: Boolean, flowInfo: FlowInterfaceInfo) {
        val containingFile = flowInfo.stateClass.containingFile!!
        val packageName = flowInfo.stateClass.packageName.asString()
        val baseName = flowInfo.stateClass.simpleName.asString()
        with(CodeBuilder()) {
            importQualifiedName(CborSymbolProcessor.DATA_ITEM_CLASS)
            importQualifiedName(flowInfo.stateClass)
            importQualifiedName(FLOW_DISPATCHER_LOCAL)
            importQualifiedName(CborSymbolProcessor.BSTR_TYPE)

            block("private fun serialize(state: $baseName?): DataItem") {
                line("return state?.toDataItem ?: Bstr(byteArrayOf())")
            }

            emptyLine()
            block("private fun deserialize(state: DataItem): $baseName") {
                block(
                    "return if (state is Bstr && state.value.isEmpty())", hasBlockAfter = true
                ) {
                    line("$baseName()")
                }
                block("else", hasBlockBefore = true) {
                    line("$baseName.fromDataItem(state)")
                }
            }

            if (flowInfo.notificationType != null) {
                emptyLine()
                importQualifiedName(FLOW_ENVIRONMENT)
                importQualifiedName(FLOW_NOTIFICATIONS)
                val declaration = flowInfo.notificationType.declaration
                if (declaration is KSClassDeclaration) {
                    importQualifiedName(declaration)
                }
                val notificationType = declaration.simpleName.asString()
                block("suspend fun $baseName.emit(env: FlowEnvironment, notification: $notificationType)") {
                    line("val notifications = env.getInterface(FlowNotifications::class)!!")
                    val notification = CborSymbolProcessor.serializeValue(
                        this, "notification", flowInfo.notificationType)
                    line("notifications.emit(\"${flowInfo.path}\", this.toDataItem, $notification)")
                }
            }

            emptyLine()
            block("fun $baseName.Companion.register(dispatcherBuilder: FlowDispatcherLocal.Builder)") {
                block("dispatcherBuilder.addFlow(\"${flowInfo.path}\", $baseName::class, ::serialize, ::deserialize)") {
                    if (creatable) {
                        line("creatable()")
                    }
                    flowInfo.operations.forEach { op ->
                        block(
                            "dispatch(\"${op.path}\")",
                            lambdaParameters = "dispatcher, flowState, flowMethodArgList"
                        ) {
                            var index = 0
                            val params = op.parameters.map { parameter ->
                                val param = "flowMethodArgList[$index]"
                                index++
                                if (parameter.flowTypeInfo != null) {
                                    val declaration = parameter.type.declaration
                                    importQualifiedName(declaration as KSClassDeclaration)
                                    val simpleName = declaration.simpleName.asString()
                                    "dispatcher.decodeStateParameter($param) as $simpleName"
                                } else {
                                    CborSymbolProcessor.deserializeValue(
                                        this, param, parameter.type
                                    )
                                }
                            }
                            val resVal = if (op.type == null) "" else "val result = "
                            line("${resVal}flowState.${op.name}(")
                            withIndent {
                                line("dispatcher.environment,")
                                params.forEach {
                                    line("$it, ")
                                }
                            }
                            line(")")
                            if (op.type == null) {
                                line("Bstr(byteArrayOf())")
                            } else if (op.flowTypeInfo != null) {
                                val joinPath = op.flowTypeInfo.joinPath ?: ""
                                line("dispatcher.encodeStateResult(result, \"$joinPath\")")
                            } else {
                                line(
                                    CborSymbolProcessor.serializeValue(
                                        this, "result", op.type
                                    )
                                )
                            }
                        }
                    }
                }
            }

            writeToFile(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(false, containingFile),
                packageName = packageName,
                fileName = "${baseName}_Registration"
            )
        }
    }

    private fun notificationType(clazz: KSClassDeclaration): KSType? {
        for (supertype in clazz.superTypes) {
            val notificationType = notificationType(supertype)
            if (notificationType != null) {
                return notificationType
            }
        }
        return null
    }

    private fun notificationType(typeReference: KSTypeReference): KSType? {
        val declaration = typeReference.resolve().declaration
        if (declaration.qualifiedName?.asString() == NOTIFIABLE_INTERFACE) {
            return typeReference.element?.typeArguments?.get(0)?.type?.resolve()
        }
        if (declaration is KSClassDeclaration) {
            return notificationType(declaration)
        }
        return null
    }

    private fun findAnnotation(declaration: KSDeclaration, simpleName: String): KSAnnotation? {
        for (annotation in declaration.annotations) {
            if (annotation.shortName.asString() == simpleName && annotation.annotationType.resolve().declaration.packageName.asString() == ANNOTATION_PACKAGE) {
                return annotation
            }
        }
        return null
    }

    private fun getClassArgument(annotation: KSAnnotation?, name: String): KSClassDeclaration? {
        annotation?.arguments?.forEach { arg ->
            if (arg.name?.asString() == name) {
                val field = arg.value
                if (field is KSType && field.declaration.qualifiedName?.asString() != "kotlin.Unit") {
                    return field.declaration as KSClassDeclaration
                }
            }
        }
        return null
    }

    private fun getStringArgument(
        annotation: KSAnnotation?, name: String, defaultValue: String
    ): String {
        annotation?.arguments?.forEach { arg ->
            if (arg.name?.asString() == name) {
                val field = arg.value.toString()
                if (field.isNotEmpty()) {
                    return field
                }
            }
        }
        return defaultValue
    }

    private fun getBooleanArgument(
        annotation: KSAnnotation?, name: String, defaultValue: Boolean
    ): Boolean {
        annotation?.arguments?.forEach { arg ->
            if (arg.name?.asString() == name) {
                val field = arg.value
                if (field is Boolean) {
                    return field
                }
            }
        }
        return defaultValue
    }

    data class FlowInterfaceInfo(
        val path: String,
        val stateClass: KSClassDeclaration,
        val interfacePackage: String,
        val interfaceName: String,
        val notificationType: KSType?,
        val operations: List<FlowOperationInfo>
    )

    data class FlowOperationInfo(
        val hidden: Boolean,  // used for flow join methods
        val path: String,
        val name: String,
        val parameters: List<FlowOperationParameterInfo>,
        val type: KSType?,
        val flowTypeInfo: FlowTypeInfo?,
    )

    data class FlowOperationParameterInfo(
        val name: String, val flowTypeInfo: FlowTypeInfo?, val type: KSType
    )

    data class FlowTypeInfo(
        val qualifiedInterfaceName: String,
        val simpleInterfaceName: String,
        val qualifiedImplName: String,
        val simpleImplName: String,
        val notifications: Boolean,
        var joinPath: String? = null
    )
}