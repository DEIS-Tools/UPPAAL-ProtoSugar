package tools.indexing.textual

import tools.indexing.types.Type
import tools.parsing.GuardedParseTree
import uppaal.model.TextUppaalPojo

class ParameterDecl(identifier: String,
                    parseTree: GuardedParseTree,
                    source: TextUppaalPojo,
                    evalType: Type)
    : FieldDecl(identifier, parseTree, source, evalType)