package tools.indexing

import tools.indexing.text.FieldDecl

abstract class DeclarationBase(val identifier: String?, parent: DeclarationHolder) : DeclarationHolder() {
    var parent: DeclarationHolder = parent
        private set


    init {
        associateTo(parent)
    }


    fun associateTo(element: DeclarationHolder) {
        if (this !in element.subDeclarations)
            element.add(this)
        else
            parent = element
    }


    final override fun <T : FieldDecl> resolveField(identifier: String, relativeTo: DeclarationBase?, targetClass: Class<T>): T?
            = resolveFieldLocal(identifier, relativeTo, targetClass) ?: parent.resolveField(identifier, this, targetClass)
}