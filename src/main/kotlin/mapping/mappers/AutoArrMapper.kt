package mapping.mappers

import createOrGetRewriter
import mapping.rewriting.ActivationRule
import mapping.rewriting.BackMapResult
import mapping.rewriting.Rewriter
import mapping.parsing.Confre
import mapping.parsing.ConfreHelper
import mapping.parsing.Node
import uppaal.error.UppaalError
import uppaal.error.UppaalPath
import uppaal.error.createUppaalError
import uppaal.model.Declaration
import uppaal.model.System

class AutoArrMapper : Mapper {
    override fun getPhases()
        = PhaseOutput(sequenceOf(Phase1()), null, null)

    private class Phase1 : ModelPhase() {
        private val arrayConfre = Confre(
            """            
            AutoArray  :== ( 'int' | 'bool' ) IDENT {'[' (INT | IDENT) ']'} '=' '{' [IDENT {',' IDENT}] '->' Expression '}' ';' .
            
            ${ConfreHelper.baseExpressionGrammar}
        """.trimIndent())

        val rewriters = HashMap<String, Rewriter>()


        init {
            register(::mapDeclaration)
            register(::mapSystem)
        }


        private fun mapDeclaration(path: UppaalPath, declaration: Declaration): List<UppaalError> {
            val (newDecl, errors) = mapAutoArrayInstantiations(declaration.content, path)
            declaration.content = newDecl
            return errors
        }

        private fun mapSystem(path: UppaalPath, system: System): List<UppaalError> {
            val (newDecl, errors) = mapAutoArrayInstantiations(system.content, path)
            system.content = newDecl
            return errors
        }

        private fun mapAutoArrayInstantiations(code: String, path: UppaalPath): Pair<String, List<UppaalError>> {
            val rewriter = rewriters.createOrGetRewriter(path, code)

            val errors = ArrayList<UppaalError>()
            for (autoArr in arrayConfre.findAll(code).map { it as Node }) {
                val dimSizes = getDimensionSizes(autoArr, code)
                val dimVars  = getDimensionVariables(autoArr)
                val dimErrors = handleDimensionErrors(dimSizes, dimVars, path, code, autoArr)
                errors.addAll(dimErrors)
                if (dimErrors.isNotEmpty())
                    continue

                val defaultExprNode = autoArr.children[7]!!
                val defaultExpr = code.substring(defaultExprNode.startPosition(), defaultExprNode.endPosition() + 1)
                val sizesAndVars = dimSizes.filterNotNull().zip(dimVars)

                val replaceRange = autoArr.range(5, 7)
                val replacement = computeReplacement(sizesAndVars, defaultExpr)
                val replaceOp = rewriter.replace(replaceRange, replacement)

                val recurringErrors = HashSet<String>()
                replaceOp.addBackMap()
                    .activateOn(replacement.indices, ActivationRule.ACTIVATION_CONTAINS_ERROR) // On any array element
                    .withPriority(1)
                    .overrideErrorRange { autoArr.children[7]!!.range() } // Place on original expression
                    .discardError { !recurringErrors.add(it.message) } // Discard if message seen before

                replaceOp.addBackMap()
                    .activateOn(replacement.indices, ActivationRule.INTERSECTS)
                    .overrideErrorRange { autoArr.range() }
            }

            return Pair(rewriter.getRewrittenText(), errors)
        }

        private fun getConstantInts(code: String): ArrayList<Triple<IntRange, String, Int>> {
            // TODO: Allow constant ARRAY-variables as size parameter?
            // TODO: Constants that are computable?
            val constInts = ArrayList<Triple<IntRange, String, Int>>()
            for (constInt in ConfreHelper.constIntConfre.findAll(code).map { it as Node }) {
                val range = IntRange(constInt.startPosition(), constInt.endPosition())
                val name = constInt.children[3]!!.toString()
                val value = code.substring(constInt.children[5]!!.range()).toIntOrNull() ?: continue
                constInts.add(Triple(range, name, value))
            }
            return constInts
        }

        private fun getDimensionSizes(autoArr: Node, code: String): List<Int?> {
            val constInts = getConstantInts(code)
            val sizes = (autoArr.children[2] as Node).children.map {
                (it as Node).children[1].toString().toIntOrNull()
                    ?: constInts.find { const ->
                        const.second == it.children[1].toString()
                                && const.first.last < autoArr.startPosition()
                    }?.third
            }
            return sizes
        }

        private fun getDimensionVariables(autoArr: Node): List<String> {
            val varListNode = autoArr.children[5]!! as Node
            if (varListNode.isBlank())
                return listOf()

            val varNameNodes = arrayListOf(varListNode.children[0])
            if (varListNode.children[1]!!.isNotBlank())
                varNameNodes.addAll((varListNode.children[1]!! as Node).children.map { child -> (child as Node).children[1] })

            return varNameNodes.map { it.toString() }.withIndex().map { if (it.value == "_") it.index.toString() else it.value }
        }

        private fun handleDimensionErrors(dimSizes: List<Int?>, dimVars: List<String>, path: UppaalPath, code: String, autoArr: Node): Collection<UppaalError> {
            val errors = ArrayList<UppaalError>()
            if (dimSizes.isEmpty())
                errors.add(
                    createUppaalError(
                    path, code, autoArr.children[1]!!, "AutoArr-syntax requires at least one array dimension on variable '${autoArr.children[1]!!}'.", true
                )
                )
            else if (dimVars.size != dimSizes.size)
                errors.add(
                    createUppaalError(
                    path, code, autoArr.children[1]!!, "Array '${autoArr.children[1]!!}' must have an equal number of dimensions and dimension variables.", true
                )
                )

            for (size in dimSizes.withIndex())
                if (size.value == null)
                    errors.add(
                        createUppaalError(
                        path, code, autoArr.children[2]!!, "Cannot determine size of array dimension ${size.index+1} of ${dimSizes.size} in array '${autoArr.children[1]!!}'.", true
                    )
                    )
                else if (size.value!! <= 0)
                    errors.add(
                        createUppaalError(
                        path, code, autoArr.children[2]!!, "Array dimension ${size.index+1} of ${dimSizes.size} in array '${autoArr.children[1]!!}' has non-positive size.", true
                    )
                    )

            if (dimVars.distinct().size != dimVars.size)
                errors.add(
                    createUppaalError(
                    path, code, autoArr.children[5]!!, "Array '${autoArr.children[1]!!}' has duplicate dimension variable names.", true
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
            val singleDimPattern = Regex("""(?>[^_a-zA-Z0-9]|^)($currDimVar)(?>[^_a-zA-Z0-9\[]|$)""")
            return singleDimPattern.replace(value) { it.value.replace(currDimVar, index.toString()) }
        }


        override fun mapModelErrors(errors: List<UppaalError>)
            = errors.filter { rewriters[it.path]?.backMapError(it) != BackMapResult.REQUEST_DISCARD }
    }
}