package engine.mapping.autoarr

import engine.mapping.Error
import engine.mapping.Mapper
import engine.mapping.Phase
import engine.parsing.Confre
import engine.parsing.Node
import uppaal_pojo.Declaration
import uppaal_pojo.System

class AutoArrMapper : Mapper {
    override fun getPhases() = sequenceOf(Phase1())
}

class Phase1 : Phase() {
    private val arrayGrammar = Confre(
        """
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = ([1-9][0-9]*)|0*
            BOOL = true|false
            
            AutoArray :== ( 'int' | 'bool' ) IDENT '[' INT ']' '=' '{' ( INT | BOOL ) '}' ';' .
        """.trimIndent())

    init {
        register(::mapDeclaration)
        register(::mapSystem)
    }

    private fun mapDeclaration(declaration: Declaration): List<Error> {
        val (newDecl, errors) = mapAutoArrayInstantiations(declaration.content)
        declaration.content = newDecl
        return errors
    }

    private fun mapSystem(system: System): List<Error> {
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
            val size = autoArr.children[3].toString().toIntOrNull()
            val defaultValueNode = autoArr.children[7]!!
            if (size == null || size < 0)
                continue // Native UPPAAL engine will catch this

            val replacement = List(size) { defaultValueNode.toString() }.joinToString(", ")
            newCode = newCode.replaceRange(defaultValueNode.startPosition() + offset, defaultValueNode.endPosition()+1 + offset, replacement)
            offset += replacement.length - defaultValueNode.length();
        }

        // TODO: Allow constant variables as size parameter + detect errors

        return Pair(newCode, errors)
    }
}