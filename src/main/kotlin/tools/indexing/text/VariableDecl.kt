package tools.indexing.text

import tools.indexing.DeclarationHolder
import tools.indexing.text.types.Type
import tools.parsing.GuardedParseTree
import uppaal.model.TextUppaalPojo

class VariableDecl(
    identifier: String, parent: DeclarationHolder,
    parseTree: GuardedParseTree,
    source: TextUppaalPojo,
    evalType: Type,
    val defaultValue: Any? = null
) : EvaluableDecl(identifier, parent, parseTree, source, evalType)