package tools.indexing.tree

import tools.indexing.DeclarationBase
import tools.indexing.DeclarationHolder
import uppaal.model.Template
import uppaal.model.UppaalPojo

class TemplateDecl(
    identifier: String,
    parent: DeclarationHolder,
    val element: Template
) : DeclarationBase(identifier, parent), TreeNode {
    override val uppaalPojo: UppaalPojo = element
}