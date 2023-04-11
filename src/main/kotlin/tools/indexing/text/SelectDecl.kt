package tools.indexing.text

import tools.indexing.DeclarationHolder
import tools.indexing.text.types.Type
import tools.parsing.GuardedParseTree
import uppaal.model.TextUppaalPojo

class SelectDecl(
    identifier: String, parent: DeclarationHolder,
    parseTree: GuardedParseTree,
    source: TextUppaalPojo,
    evalType: Type
) : EvaluableDecl(identifier, parent, parseTree, source, evalType)