package mapping.impl

import mapping.mapping.*
import mapping.parsing.*
import mapping.restructuring.ActivationRule
import mapping.restructuring.TextRewriter
import mapping.scoping.Modifier
import mapping.scoping.Scope
import mapping.scoping.declarations.Field
import mapping.scoping.types.IntType
import uppaal.messaging.UppaalMessage
import uppaal.messaging.UppaalPath
import uppaal.messaging.createUppaalError
import uppaal.model.Declaration
import uppaal.model.System

class AutoArrMapper : Mapper() {
    override val registerSyntax: RegistryBuilder.() -> Unit = {
        makeNonTerminal("AutoArr", "'->' [Expression] .")
        insertOptional("ArrayInit", listOf(3), "AutoArr")
        // ArrayInit :== '{' [ArrayInitTerm] {',' [ArrayInitTerm]} [AutoArr] '}' .
        //                                                  NEW -> ---------
    }

    override fun getPhases()
        = PhaseOutput(listOf(Phase1()), null, null)

    val globalScope = Scope()


    private inner class Phase1 : ModelPhase()
    {
        private val declarationConfre = parserBuilder.generateParser("Declaration")


        init {
            register(::mapDeclaration)
            register(::mapSystem)
        }


        private fun mapDeclaration(path: UppaalPath, declaration: Declaration): List<UppaalMessage> {
            val rewriter = getRewriter(path, declaration.content)
            val errors = doMapping(rewriter, path, if (path.size == 2) globalScope else Scope(globalScope))
            declaration.content = rewriter.getRewrittenText()
            return errors
        }

        private fun mapSystem(path: UppaalPath, system: System): List<UppaalMessage> {
            val rewriter = getRewriter(path, system.content)
            val errors = doMapping(rewriter, path, Scope(globalScope))
            system.content = rewriter.getRewrittenText()
            return errors
        }

        private fun doMapping(rewriter: TextRewriter, path: UppaalPath, scope: Scope): List<UppaalMessage> {
            val errors = ArrayList<UppaalMessage>()
            for (decl in declarationConfre.findAll(rewriter.originalText)) {
                val varNode = decl.findLocal("VarOrFunction") ?: continue
                if (varNode.localFullySafe)
                    if (!tryRegisterConstInt(varNode, rewriter, scope))
                        errors.addAll(tryParseAutoArray(varNode, rewriter, path, scope))
            }
            return errors
        }

        private fun tryRegisterConstInt(varNode: GuardedParseTree, rewriter: TextRewriter, scope: Scope): Boolean {
            // Check if "non-array instantiation" // TODO: Parse all declarations (using "Declaration parser (mapper)????")
            val exprNode = varNode[2]!!.findLocal("Expression") ?: return false
            if (!varNode[2]!![0]!!.isBlank) // Ensure no subscript/array-notation
                return false

            // Check if "const int" type
            val typeNode = varNode[0]!!
            if (!typeNode.globalFullySafe || typeNode.getUnguarded(0).toString() != "const")
                return false
            val nonStructNode = typeNode.findLocal("NonStruct") ?: return false
            if (nonStructNode[0]!!.toString() != "int")
                return false

            // TODO: Use "expression evaluator" instead
            // Register if value ís valid integer
            val value = rewriter.originalText.substring(exprNode.fullRange).toIntOrNull() ?: return false
            val identifier = varNode[1]!!.toString()
            scope.add(Field(identifier, IntType(Modifier.CONST), defaultValue = value))
            return true
        }

        private fun tryParseAutoArray(varNode: GuardedParseTree, rewriter: TextRewriter, path: UppaalPath, scope: Scope): List<UppaalMessage> {
            // Check if "array with auto-syntax"
            val arrayInitNode = varNode[2]!!.findLocal("ArrayInit") ?: return listOf()
            val autoArrNode = arrayInitNode[3]!!.findLocal("AutoArr") ?: return listOf()
            val subscriptNode = varNode[2]!![0]!!
            if (subscriptNode.isBlank || !subscriptNode.localFullySafe || !arrayInitNode.localFullySafe)
                return listOf()

            // Check if "int or bool type array"
            val nonStructNode = varNode[0]!!.findLocal("NonStruct") ?: return listOf()
            if (!nonStructNode.localFullySafe || nonStructNode[0]!!.toString() !in arrayOf("int", "bool"))
                return listOf()

            // Compute values needed for mapping
            val dimSizes = getDimensionSizes(subscriptNode, scope)
            val dimVars  = getDimensionVariables(arrayInitNode, autoArrNode)
            val errors = handleDimensionErrors(dimSizes, dimVars, path, rewriter.originalText, varNode)
            if (errors.isNotEmpty())
                return errors

            // Map and rewrite
            val defaultExprNode = autoArrNode[1]!![0]!!
            val defaultExpr = rewriter.originalText.substring(defaultExprNode.fullRange)
            val sizesAndVars = dimSizes.filterNotNull().zip(dimVars.filterNotNull())

            val replaceRange = arrayInitNode.range(1, 3)
            val replacement = computeReplacement(sizesAndVars, defaultExpr)
            val replaceOp = rewriter.replace(replaceRange, replacement)

            val recurringErrors = HashSet<String>()
            replaceOp.addBackMap()
                .activateOn(ActivationRule.ACTIVATION_CONTAINS_ERROR) // On any array element
                .withPriority(1)
                .overrideErrorRange { defaultExprNode.fullRange } // Place on original expression
                .discardError { !recurringErrors.add(it.message) } // Discard if message seen before

            replaceOp.addBackMap()
                .activateOn(replacement.indices, ActivationRule.INTERSECTS)
                .overrideErrorRange { varNode.fullRange }

            return listOf()
        }


        private fun getDimensionSizes(subscriptNode: GuardedParseTree, scope: Scope): List<Int?> {
            val sizeExprNodes = subscriptNode[0]!!.children.map { it?.findLocal("Expression") }
            return sizeExprNodes.map {
                val expr = it?.toStringNotNull() // TODO: Use "expression evaluator" instead
                if (expr == null)
                    null
                else
                    expr.toIntOrNull() ?: (scope[expr] as? Field)?.defaultValue as? Int
            }
        }

        private fun getDimensionVariables(arrayInitNode: GuardedParseTree, autoArrNode: GuardedParseTree): List<String?> {
            val dimVarNodes =
                listOf(arrayInitNode[1]!![0]) + arrayInitNode[2]!!.children.map { it?.get(1)?.get(0) }
            val dimVars = dimVarNodes
                .map {
                    val result = it?.toStringNotNull()
                    if (result != null && !Regex("""[_a-zA-Z$][_a-zA-Z0-9$]*""").matches(result)) // TODO: Better way of looking for single terminals in one bit non-terminal (I.e., is this "Expression" just an "IDENT"?)
                        throw Exception("Can't do this right now!!") // FIXME: Fix error reporting
                    result
                }
                .map { if (it == "_") "" else it }

            return dimVars
        }

        private fun handleDimensionErrors(dimSizes: List<Int?>, dimVars: List<String?>, path: UppaalPath, code: String, varNode: GuardedParseTree): List<UppaalMessage> {
            val errors = ArrayList<UppaalMessage>()
            val identNode = varNode[1]!!.tree
            if (dimSizes.isEmpty())
                errors.add(
                    createUppaalError(
                    path, code, identNode, "AutoArr-syntax requires at least one array dimension on variable '${identNode}'.", true
                )
                )
            else if (dimVars.size != dimSizes.size)
                errors.add(
                    createUppaalError(
                    path, code, identNode, "Array '${identNode}' must have an equal number of dimensions and dimension variables.", true
                )
                )

            val subscriptNode = varNode[2]!![0]!!.tree
            for (size in dimSizes.withIndex())
                if (size.value == null)
                    errors.add(
                        createUppaalError(
                        path, code, subscriptNode, "Cannot determine size of array dimension ${size.index+1} of ${dimSizes.size} in array '${identNode}'.", true
                    )
                    )
                else if (size.value!! <= 0)
                    errors.add(
                        createUppaalError(
                        path, code, subscriptNode, "Array dimension ${size.index+1} of ${dimSizes.size} in array '${identNode}' has non-positive size.", true
                    )
                    )

            if (dimVars.filter { it != "" }.distinct().size != dimVars.filter { it != "" }.size)
                errors.add(
                    createUppaalError( // TODO: Fix so that error is put on the correct spot
                    path, code, identNode, "Array '${identNode}' has duplicate dimension variable names.", true
                )
                )

            return errors
        }

        private fun computeReplacement(sizesAndVars: List<Pair<Int, String>>, defaultExpr: String): String {
            var replacement = List(sizesAndVars.last().first) { defaultExpr }
                .withIndex()
                .joinToString(", ") { mapDimension(it.value, it.index, sizesAndVars.last().second) }

            for (dim in sizesAndVars.reversed().drop(1)) {
                replacement = List(dim.first) { replacement }.withIndex().joinToString(", ") { "{ ${mapDimension(it.value, it.index, dim.second)} }" }
            }

            return replacement
        }

        private fun mapDimension(value: String, index: Int, currDimVar: String): String {
            if (currDimVar == "")
                return value

            val singleDimPattern = Regex("""(?>[^_a-zA-Z0-9]|^)($currDimVar)(?>[^_a-zA-Z0-9\[]|$)""")
            return singleDimPattern.replace(value) { it.value.replace(currDimVar, index.toString()) }
        }
    }
}