package engine.mapping.autoarr

import engine.mapping.Error
import engine.mapping.Mapper
import engine.mapping.PathNode
import engine.mapping.Phase
import engine.parsing.Confre
import engine.parsing.Node
import uppaal_pojo.Declaration
import uppaal_pojo.System

class AutoArrMapper : Mapper {
    override fun getPhases(): Sequence<Phase> = sequenceOf(Phase1())

    private class Phase1 : Phase() {
        private val arrayGrammar = Confre(
            """
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = ([1-9][0-9]*)|0*
            BOOL = true|false
            
            AutoArray :== ( 'int' | 'bool' ) IDENT {'[' INT ']'} '=' '{' ( INT | BOOL ) '}' ';' .
        """.trimIndent())

        init {
            register(::mapDeclaration)
            register(::mapSystem)
        }

        private fun mapDeclaration(@Suppress("UNUSED_PARAMETER") path: List<PathNode>, declaration: Declaration): List<Error> {
            val (newDecl, errors) = mapAutoArrayInstantiations(declaration.content)
            declaration.content = newDecl
            return errors
        }

        private fun mapSystem(@Suppress("UNUSED_PARAMETER") path: List<PathNode>, system: System): List<Error> {
            val (newDecl, errors) = mapAutoArrayInstantiations(system.content)
            system.content = newDecl
            return errors
        }

        private fun mapAutoArrayInstantiations(code: String): Pair<String, List<Error>> {
            val errors = ArrayList<Error>()
            var offset = 0
            var newCode = code
            for (autoArr in arrayGrammar.findAll(code).map { it as Node })
            {
                val sizes = (autoArr.children[2] as Node).children.map { (it as Node).children[1].toString().toIntOrNull() }
                val defaultValueNode = autoArr.children[5]!!
                if (sizes.isEmpty() || sizes.any { it == null || it < 0 })
                    continue // Native UPPAAL engine will catch this

                var replacement = List(sizes.first()!!) { defaultValueNode.toString() }.joinToString(", ")
                for (size in sizes.drop(1))
                    replacement = List(size!!) { replacement }.joinToString(", ") { "{ $it }" }

                newCode = newCode.replaceRange(defaultValueNode.startPosition() + offset, defaultValueNode.endPosition()+1 + offset, replacement)
                offset += replacement.length - defaultValueNode.length()
            }

            // TODO: Allow constant variables as size parameter + detect errors

            return Pair(newCode, errors)
        }
    }
}