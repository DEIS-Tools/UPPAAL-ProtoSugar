package tools.indexing.tree

import tools.indexing.DeclarationBase
import tools.indexing.DeclarationHolder
import uppaal.model.Transition
import uppaal.model.UppaalPojo

class TransitionDecl(
    parent: DeclarationHolder,
    val element: Transition
) : DeclarationBase(null, parent), TreeNode {
    override val uppaalPojo: UppaalPojo = element
}