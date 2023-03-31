package tools.indexing.text

import tools.indexing.DeclarationBase
import tools.indexing.DeclarationHolder
import tools.parsing.GuardedParseTree
import uppaal.model.TextUppaalPojo

abstract class FieldDecl(
    override val identifier: String, parent: DeclarationHolder,
    override val parseTree: GuardedParseTree,
    override val source: TextUppaalPojo
) : DeclarationBase(identifier, parent), Textual