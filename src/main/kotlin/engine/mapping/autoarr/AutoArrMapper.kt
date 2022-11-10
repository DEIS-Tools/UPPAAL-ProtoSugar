package engine.mapping.autoarr

import engine.mapping.*
import engine.parsing.*
import uppaal_pojo.Declaration
import uppaal_pojo.System

class AutoArrMapper : Mapper {
    override fun getPhases(): Pair<Sequence<ModelPhase>, QueryPhase?>
        = Pair(sequenceOf(Phase1()), null)

    private class Phase1 : ModelPhase() {
        private val constIntGrammar = Confre(
            """
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = -?[0-9]+
            
            ConstInt  :== 'const' 'int' IDENT '=' INT ';' .
        """.trimIndent())

        private val arrayGrammar = Confre(
            """
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = -?[0-9]+
            BOOL = true|false
            
            AutoArray  :== ( 'int' | 'bool' ) IDENT {'[' (INT | IDENT) ']'} '=' '{' Expression '}' ';' .
            Expression :== [Unary] (Term  {'[' Expression ']'} | '(' Expression ')') [Binary Expression].
            Term       :== IDENT | INT | BOOL .
            Unary      :== '+' | '-' | '!' | 'not' .
            Binary     :== '<' | '<=' | '==' | '!=' | '>=' | '>' | '+' | '-' | '*' | '/' | '%' | '&'
                         | '|' | '^' | '<<' | '>>' | '&&' | '||' | '<?' | '>?' | 'or' | 'and' | 'imply' .
        """.trimIndent())

        init {
            register(::mapDeclaration)
            register(::mapSystem)
        }

        private fun mapDeclaration(path: List<PathNode>, declaration: Declaration): List<UppaalError> {
            val (newDecl, errors) = mapAutoArrayInstantiations(declaration.content, path)
            declaration.content = newDecl
            return errors
        }

        private fun mapSystem(path: List<PathNode>, system: System): List<UppaalError> {
            val (newDecl, errors) = mapAutoArrayInstantiations(system.content, path)
            system.content = newDecl
            return errors
        }

        private fun mapAutoArrayInstantiations(code: String, path: List<PathNode>): Pair<String, List<UppaalError>> {
            val errors = ArrayList<UppaalError>()
            var offset = 0
            var newCode = code

            // TODO: Allow constant ARRAY-variables as size parameter? Constants that are computable?

            val constInts = ArrayList<Triple<IntRange, String, Int>>()
            for (constInt in constIntGrammar.findAll(code).map { it as Node }) {
                val range = IntRange(constInt.startPosition(), constInt.endPosition())
                val name = constInt.children[2]!!.toString()
                val value = constInt.children[4]!!.toString().toInt()
                constInts.add(Triple(range, name, value))
            }

            for (autoArr in arrayGrammar.findAll(code).map { it as Node }) {
                val sizes = (autoArr.children[2] as Node).children.map {
                    (it as Node).children[1].toString().toIntOrNull()
                    ?: constInts.find { const ->
                           const.second == it.children[1].toString()
                           && const.first.last < autoArr.startPosition()
                       }?.third
                }
                if (sizes.isEmpty() || sizes.any { it == null || it < 0 }) {
                    val linesAndColumns = getStartAndEndLinesAndColumns(
                        code, IntRange(autoArr.children[2]!!.startPosition(), autoArr.children[2]!!.endPosition()), 0
                    )
                    errors.add(UppaalError(
                        path,
                        linesAndColumns.first, linesAndColumns.second,
                        linesAndColumns.third, linesAndColumns.fourth,
                        "AutoArr cannot determine/use the sizes of some of the dimensions in '${code.substring(autoArr.startPosition(), autoArr.endPosition() + 1)}'. Only positive integers and constant integer variables with explicitly given values are supported",
                        "",
                        isUnrecoverable = false
                    ))
                    continue
                }

                val defaultValueNode = autoArr.children[5]!!
                val defaultValue = code.substring(defaultValueNode.startPosition(), defaultValueNode.endPosition() + 1)

                val anyDimPattern = Regex("""(?>[^_a-zA-Z0-9]|^)(i\s*\[\s*([0-9]+)\s*\])""")
                val illegalDimRefs = anyDimPattern.findAll(defaultValue)
                    .map { Triple(it.groups[2]!!.value.toInt(), it.groups[2]!!.range, it.range.first) }
                    .filter { it.first >= sizes.size }
                    .map { Pair(it.first, getStartAndEndLinesAndColumns(code, it.second, it.third)) }
                for (dimRef in illegalDimRefs)
                    errors.add(UppaalError(
                        path,
                        dimRef.second.first, dimRef.second.second,
                        dimRef.second.third, dimRef.second.fourth,
                        "Invalid dimension index: ${dimRef.first}", "", false
                    ))

                var currDim = sizes.size - 1
                var replacement = List(sizes.last()!!) { defaultValue }.withIndex().joinToString(", ") { mapDimension(it.value, it.index, currDim, sizes.size) }
                for (size in sizes.reversed().drop(1)) {
                    --currDim
                    replacement = List(size!!) { replacement }.withIndex().joinToString(", ") { "{ ${mapDimension(it.value, it.index, currDim, sizes.size)} }" }
                }

                newCode = newCode.replaceRange(defaultValueNode.startPosition() + offset, defaultValueNode.endPosition()+1 + offset, replacement)
                offset += replacement.length - defaultValueNode.length()
            }

            return Pair(newCode, errors)
        }

        private fun mapDimension(value: String, index: Int, currDim: Int, dimensionCount: Int): String {
            val singleDimPattern = Regex("""(?>[^_a-zA-Z0-9]|^)(i)(?>[^_a-zA-Z0-9\[]|$)""")
            val multiDimPattern = Regex("""(?>[^_a-zA-Z0-9]|^)(i\s*\[\s*($currDim)\s*\])""")

            var newValue = multiDimPattern.replace(value) { it.value.replaceFirst(it.groups[1]!!.value, index.toString()) }
            if (dimensionCount == 1)
                newValue = singleDimPattern.replace(newValue) { it.value.replaceFirst("i", index.toString()) }

            return newValue
        }
    }
}