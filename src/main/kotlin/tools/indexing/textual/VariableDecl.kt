package tools.indexing.textual

import tools.indexing.types.Type
import tools.parsing.GuardedParseTree
import uppaal.model.TextUppaalPojo

class VariableDecl(identifier: String,
                   parseTree: GuardedParseTree,
                   source: TextUppaalPojo,
                   evalType: Type,
                   val defaultValue: Any? = null)
    : FieldDecl(identifier, parseTree, source, evalType)