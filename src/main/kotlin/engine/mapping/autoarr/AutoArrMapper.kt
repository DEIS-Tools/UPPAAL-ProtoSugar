package engine.mapping.autoarr

import engine.mapping.*
import engine.parsing.*
import uppaal_pojo.Declaration
import uppaal_pojo.System

class AutoArrMapper : Mapper {
    override fun getPhases(): Pair<Sequence<ModelPhase>, QueryPhase?>
        = Pair(sequenceOf(Phase1()), null)

    private class Phase1 : ModelPhase() {
        private val arrayGrammar = Confre(
            """
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = ([1-9][0-9]*)|0*
            BOOL = true|false
            
            AutoArray  :== ( 'int' | 'bool' ) IDENT {'[' INT ']'} '=' '{' Expression '}' ';' .
            Expression :== [Unary] (Term  ['[' Expression ']'] | '(' Expression ')') [Binary Expression].
            Term       :== IDENT | INT | BOOL .
            Unary      :== '+' | '-' | '!' | 'not' .
            Binary     :== '<' | '<=' | '==' | '!=' | '>=' | '>'
                         | '+' | '-' | '*' | '/' | '%' | '&'
                         | '|' | '^' | '<<' | '>>' | '&&' | '||'
                         | '<?' | '>?' | 'or' | 'and' | 'imply' .
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
            for (autoArr in arrayGrammar.findAll(code).map { it as Node }) {
                val sizes = (autoArr.children[2] as Node).children.map { (it as Node).children[1].toString().toIntOrNull() }
                val defaultValueNode = autoArr.children[5]!!
                val defaultValue = code.substring(defaultValueNode.startPosition(), defaultValueNode.endPosition() + 1)
                if (sizes.isEmpty() || sizes.any { it == null || it < 0 })
                    continue // Native UPPAAL engine will catch this

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

            // TODO: Allow constant variables as size parameter + detect errors

            return Pair(newCode, errors)
        }

        private fun mapDimension(value: String, index: Int, currDim: Int, dimensionCount: Int): String {
            val singleDimPattern = Regex("""(?>[^_a-zA-Z0-9]|^)(i)(?>[^_a-zA-Z0-9\[]|$)""")
            val multiDimPattern = Regex("""(?>[^_a-zA-Z0-9]|^)(i\s*\[\s*($currDim)\s*\])""")

            var newValue = multiDimPattern.replace(value) { it.value.replace(it.groups[1]!!.value, index.toString()) }
            if (dimensionCount == 1)
                newValue = singleDimPattern.replace(newValue) { it.value.replace("i", index.toString()) }

            return newValue
        }
    }
}