package mapping.impl

import mapping.base.*
import tools.indexing.DeclarationBase
import tools.indexing.DeclarationHolder
import tools.restructuring.ActivationRule
import tools.restructuring.TextRewriter
import tools.indexing.tree.TemplateDecl
import tools.indexing.text.VariableDecl
import tools.indexing.tree.Model
import tools.indexing.text.types.Const
import tools.indexing.text.types.IntType
import tools.parsing.GuardedParseTree
import tools.parsing.ParseTree
import uppaal.messaging.UppaalMessage
import uppaal.UppaalPath
import uppaal.messaging.createUppaalError
import uppaal.model.*

class AutoArrMapper : Mapper() {
    override val registerSyntax: RegistryBuilder.() -> Unit = {
        makeNonTerminal("AutoArr", "'->' [Expression] .")
        insertOptional("ArrayInit", listOf(3), "AutoArr")
        // ArrayInit :== '{' [ArrayInitTerm] {',' [ArrayInitTerm]} [AutoArr] '}' .
        //                                                  NEW -> ---------
    }

    override fun buildPhases()
        = Phases(listOf(Phase1()), null, null)



    private inner class Phase1 : ModelPhase()
    {
        private lateinit var globalScope: Model

        private val declarationConfre by lazy { generateParser("Declaration") }


        override fun onConfigured() {
            register(::mapNta)
            register(::mapDeclaration)
            register(::mapSystem)
        }


        private fun mapNta(path: UppaalPath, nta: Nta) {
            globalScope = Model(nta)
        }

        private fun mapDeclaration(path: UppaalPath, declaration: Declaration) {
            val rewriter = getRewriter(path, declaration.content)
            val scope = when (path.size) {
                2 -> globalScope
                3 -> TemplateDecl((path[1].element as Template).name.content, globalScope, path[1].element as Template)
                else -> throw Exception("Unexpected path length '${path.size}' leading to a Declaration UppaalPojo")
            }

            doMapping(rewriter, path, scope)
            declaration.content = rewriter.getRewrittenText()
        }

        private fun mapSystem(path: UppaalPath, system: System) {
            val rewriter = getRewriter(path, system.content)
            doMapping(rewriter, path, globalScope)
            system.content = rewriter.getRewrittenText()
        }

        private fun doMapping(rewriter: TextRewriter, path: UppaalPath, scopeDecl: DeclarationHolder) {
            for (decl in declarationConfre.findAll(rewriter.originalText)) {
                val varNode = decl.findLocal("VarOrFunction") ?: continue
                if (varNode.localFullySafe)
                    if (!tryRegisterConstInt(varNode, path.last().element as TextUppaalPojo, rewriter, scopeDecl))
                        tryParseAutoArray(varNode, rewriter, path, scopeDecl)
            }
        }

        private fun tryRegisterConstInt(varNode: GuardedParseTree, source: TextUppaalPojo, rewriter: TextRewriter, scopeDecl: DeclarationHolder): Boolean {
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
            VariableDecl(identifier, scopeDecl, varNode, source, IntType(Const()), defaultValue = value)
            return true
        }

        private fun tryParseAutoArray(varNode: GuardedParseTree, rewriter: TextRewriter, path: UppaalPath, scopeDecl: DeclarationHolder) {
            // Check if "array with auto-syntax"
            val arrayInitNode = varNode[2]!!.findLocal("ArrayInit") ?: return
            val autoArrNode = arrayInitNode[3]!!.findLocal("AutoArr") ?: return
            val subscriptNode = varNode[2]!![0]!!
            if (subscriptNode.isBlank || !subscriptNode.localFullySafe || !arrayInitNode.localFullySafe)
                return

            // Check if "int or bool type array"
            val nonStructNode = varNode[0]!!.findLocal("NonStruct") ?: return
            if (!nonStructNode.localFullySafe || nonStructNode[0]!!.toString() !in arrayOf("int", "bool"))
                return

            // Compute values needed for mapping
            val dimSizes = getDimensionSizes(subscriptNode, scopeDecl)
            val dimVars  = getDimensionVariables(arrayInitNode, autoArrNode)
            if (checkForErrors(dimSizes, dimVars, path, rewriter.originalText, varNode))
                return

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

            return
        }


        private fun getDimensionSizes(subscriptNode: GuardedParseTree, scopeDecl: DeclarationHolder): List<Int?> {
            val sizeExprNodes = subscriptNode[0]!!.children.map { it?.findLocal("Expression") }
            return sizeExprNodes.map {
                val expr = it?.toStringNotNull() // TODO: Use "expression evaluator" instead
                if (expr == null)
                    null
                else
                    expr.toIntOrNull() ?: throw Exception("Broken rn") //(scopeDecl[expr] as? VariableDecl)?.defaultValue as? Int // TODO: Fix this
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

        private fun checkForErrors(dimSizes: List<Int?>, dimVars: List<String?>, path: UppaalPath, code: String, varNode: GuardedParseTree): Boolean {
            val errors = ArrayList<UppaalMessage>()
            val identNode = varNode[1]!!.tree
            if (dimSizes.isEmpty())
                errors.add(noDimensionsError(path, code, identNode))
            else if (dimVars.size != dimSizes.size)
                errors.add(mismatchingDimensionsAndVariablesError(path, code, identNode))

            val subscriptNode = varNode[2]!![0]!!.tree
            for (size in dimSizes.withIndex())
                if (size.value == null)
                    errors.add(cannotDetermineDimensionSizeError(path, code, identNode, subscriptNode, size, dimSizes.size))
                else if (size.value!! <= 0)
                    errors.add(nonPositiveDimensionSizeError(path, code, subscriptNode, size, dimSizes, identNode))

            if (dimVars.filter { it != "" }.distinct().size != dimVars.filter { it != "" }.size)
                errors.add(duplicateDimensionLabelsError(path, code, identNode))

            reportAll(errors)
            return errors.isNotEmpty()
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


        private fun mismatchingDimensionsAndVariablesError(path: UppaalPath, code: String, identNode: ParseTree) =
            createUppaalError(path, code, identNode, "Array '${identNode}' must have an equal number of dimensions and dimension variables.", true)
        private fun noDimensionsError(path: UppaalPath, code: String, identNode: ParseTree) =
            createUppaalError(path, code, identNode, "AutoArr-syntax requires at least one array dimension on variable '${identNode}'.", true)
        private fun cannotDetermineDimensionSizeError(path: UppaalPath, code: String, identNode: ParseTree, subscriptNode: ParseTree, element: IndexedValue<Int?>, size: Int) =
            createUppaalError(path, code, subscriptNode, "Cannot determine size of array dimension ${element.index+1} of $size in array '${identNode}'.", true)
        private fun nonPositiveDimensionSizeError(path: UppaalPath, code: String, subscriptNode: ParseTree, size: IndexedValue<Int?>, dimSizes: List<Int?>, identNode: ParseTree) =
            createUppaalError(path, code, subscriptNode, "Array dimension ${size.index + 1} of ${dimSizes.size} in array '${identNode}' has non-positive size.", true)
        private fun duplicateDimensionLabelsError(path: UppaalPath, code: String, identNode: ParseTree) =
            createUppaalError(path, code, identNode, "Array '${identNode}' has duplicate dimension variable names.", true)
    }
}