package tools.indexing.textual

import tools.indexing.DeclarationBase
import tools.indexing.Evaluable
import tools.indexing.Textual
import tools.indexing.types.Type
import tools.parsing.GuardedParseTree
import uppaal.model.TextUppaalPojo

abstract class FieldDecl(
    identifier: String,
    override val parseTree: GuardedParseTree,
    override val source: TextUppaalPojo,
    override val evalType: Type)
    : DeclarationBase(identifier), Textual, Evaluable