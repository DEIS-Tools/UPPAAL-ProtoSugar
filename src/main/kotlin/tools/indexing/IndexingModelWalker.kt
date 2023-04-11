package tools.indexing

import mapping.base.Mapper
import tools.indexing.text.FunctionDecl
import tools.indexing.text.ParameterDecl
import tools.indexing.text.TypedefDecl
import tools.indexing.text.VariableDecl
import tools.indexing.text.types.primitive.IntType
import tools.indexing.text.types.Type
import tools.indexing.text.types.modifiers.ReferenceType
import tools.indexing.tree.Model
import tools.indexing.tree.TemplateDecl
import tools.indexing.tree.TransitionDecl
import tools.parsing.GuardedParseTree
import tools.parsing.SyntaxRegistry
import uppaal.UppaalPath
import uppaal.model.*
import uppaal.walkers.ModelWalkerBase

class IndexingModelWalker(syntaxRegistry: SyntaxRegistry) : ModelWalkerBase() {
    private lateinit var currentScope: DeclarationHolder

    private val paramConfre = syntaxRegistry.generateParser("Param", Mapper::class)
    private val declarationConfre = syntaxRegistry.generateParser("Declaration", Mapper::class)


    fun buildIndex(nta: Nta): Model {
        doWalk(nta)
        return currentScope as? Model
            ?: throw Exception("The resulting scope was not a 'Model'. Something is wrong with the indexer")
    }


    override fun stepInto(path: UppaalPath, uppaalPojo: UppaalPojo) {
        when (uppaalPojo) {
            is Nta -> currentScope = Model(uppaalPojo)
            is Template -> currentScope = TemplateDecl(uppaalPojo.name.content, currentScope, uppaalPojo)
            is Transition -> currentScope = TransitionDecl(currentScope, uppaalPojo)
        }
    }

    override fun stepOut(path: UppaalPath, uppaalPojo: UppaalPojo) {
        when (uppaalPojo) {
            is Nta, is Template, is Transition ->
                currentScope = currentScope.parentOrSelf()
        }
    }

    override fun visitUppaalPojo(path: UppaalPath, uppaalPojo: UppaalPojo) {
        when (uppaalPojo) {
            is Parameter -> parameterNode(uppaalPojo)
            is Declaration -> declarationNode(uppaalPojo)
            is Transition -> transitionNode(uppaalPojo)
            is System -> systemNode(uppaalPojo)
        }
        // TODO: locations, labels, ... names(?), etc.
    }

    private fun parameterNode(parameter: Parameter) {
        for (param in paramConfre.findAll(parameter.content))
            parseParameter(param, parameter)
    }

    private fun declarationNode(declaration: Declaration) {
        for (decl in declarationConfre.findAll(declaration.content))
            registerDeclaration(decl, declaration)
    }

    private fun transitionNode(uppaalPojo: Transition) {
        // TODO
    }

    private fun systemNode(system: System) {
        // TODO: Partial instantiations
        for (decl in declarationConfre.findAll(system.content))
            registerDeclaration(decl, system)
    }

    private fun registerDeclaration(parseTree: GuardedParseTree, sourcePojo: TextUppaalPojo) {
        val varOrFunc = parseTree.findFirstNonTerminal("VarOrFunction")
        if (varOrFunc != null) {
            return if (varOrFunc[2]!![0]!!.isLeaf && varOrFunc[2]!![0]!!.leaf.token?.value == "(")
                parseFunction(varOrFunc, sourcePojo)
            else
                parseVariable(varOrFunc, sourcePojo)
        }

        val typedef = parseTree.findFirstNonTerminal("Typedef")
        if (typedef != null)
            return parseTypedef(typedef, sourcePojo)

        // TODO: Partial instantiation

        throw Exception("Something went wrong here") // TODO: Better message
    }


    private fun parseParameter(parseTree: GuardedParseTree, parameter: Parameter) {
        var type = parseTypeNode(parseTree[0]!!, parameter)
        // TODO: Handle arrays
        if (!parseTree[1]!!.isBlank)
            type = ReferenceType(type)

        ParameterDecl(
            parseTree[2]!!.toString(),
            currentScope,
            parseTree,
            parameter,
            type
        )
    }

    private fun parseVariable(parseTree: GuardedParseTree, sourcePojo: TextUppaalPojo) {
        VariableDecl(
            parseTree[1]!!.toString(),
            currentScope,
            parseTree,
            sourcePojo,
            parseTypeNode(parseTree, sourcePojo),
            null // TODO: Try evaluate constant expr
        )
    }

    private fun parseFunction(parseTree: GuardedParseTree, sourcePojo: TextUppaalPojo) {
        // TODO: Properly parse all parameters and declarations under the function and make it a scope of its own
        FunctionDecl(
            parseTree[1]!!.toString(),
            currentScope,
            parseTree,
            sourcePojo,
            parseTypeNode(parseTree, sourcePojo)
        )
    }

    private fun parseTypedef(parseTree: GuardedParseTree, sourcePojo: TextUppaalPojo) {
        TypedefDecl(
            parseTree[2]!!.toString(),
            currentScope,
            parseTree,
            sourcePojo,
            parseTypeNode(parseTree, sourcePojo)
        )
    }


    private fun parseTypeNode(parseTree: GuardedParseTree, sourcePojo: UppaalPojo): Type {
        return IntType() // FIXME: Actually do this properly
    }
}