package tools.indexing

import tools.indexing.text.FieldDecl

abstract class DeclarationHolder {
    private val _subDeclarations = linkedSetOf<DeclarationBase>() // Order of elements affects visibility
    val subDeclarations: Set<DeclarationBase> get() = _subDeclarations

    fun add(element: DeclarationBase) {
        _subDeclarations.add(element)
        if (element.parent != this) {
            element.parent._subDeclarations.remove(element)
            element.associateTo(this)
        }
    }

    // TODO: Semantics... "Resolve = go up" and "Find = go down" ????


    // TODO: May require different resolve-strategies
    /** Find the declaration that "first" matches the given identifier which is a direct child of this element or any of its ancestors. **/
    inline fun <reified T : FieldDecl> resolveField(identifier: String, relativeTo: DeclarationBase? = null): T?
            = resolveField(identifier, relativeTo, T::class.java)

    open fun <T : FieldDecl> resolveField(identifier: String, relativeTo: DeclarationBase?, targetClass: Class<T>): T?
            = resolveFieldLocal(identifier, relativeTo, targetClass)

    /** Find the declaration that "first" matches the given identifier which is a direct child of this element. **/
    inline fun <reified T : FieldDecl> resolveFieldLocal(identifier: String, relativeTo: DeclarationBase? = null): T?
            = resolveFieldLocal(identifier, relativeTo, T::class.java)

    fun <T : FieldDecl> resolveFieldLocal(identifier: String, relativeTo: DeclarationBase?, targetClass: Class<T>): T? {
        return subDeclarations.asSequence()
            .takeWhile { it != relativeTo }
            .filterIsInstance(targetClass)
            .firstOrNull { it.identifier == identifier }
    }


    /** Find an identifier based on "something (TODO: dot notation? Figure out as we go)" **/
    fun <T> find(/* Something */): DeclarationBase? {
        return null
    }


    inline fun <reified T : DeclarationHolder> findAllLocal(noinline predicate: ((T) -> Boolean)? = null): Sequence<T> {
        val sequence = subDeclarations.asSequence().filterIsInstance<T>()
        return if (predicate == null) sequence
        else sequence.filter(predicate)
    }
}

fun DeclarationHolder.parentOrSelf(): DeclarationHolder
        = (this as? DeclarationBase)?.parent ?: this