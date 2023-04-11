package tools.indexing.tree

import tools.indexing.DeclarationBase
import tools.indexing.DeclarationHolder
import tools.indexing.Textual
import tools.parsing.GuardedParseTree
import uppaal.model.Label
import uppaal.model.TextUppaalPojo

class LabelDecl(
    parent: DeclarationHolder,
    override val uppaalPojo: Label,
    override val parseTree: GuardedParseTree,
) : DeclarationBase(null, parent), TreeNode, Textual {
    override val source: TextUppaalPojo = uppaalPojo
}