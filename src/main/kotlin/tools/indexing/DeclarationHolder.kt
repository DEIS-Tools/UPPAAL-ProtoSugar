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


    // TODO: May require different-search strategies (depth first? breadth first? Something else? This might not even make sense to allow in the first place)
    inline fun <reified T : DeclarationHolder> find(maxDepth: Int, noinline predicate: ((T) -> Boolean)?): T?
        = findAll(predicate, T::class.java, maxDepth).firstOrNull()
    inline fun <reified T : DeclarationHolder> findAll(maxDepth: Int, noinline predicate: ((T) -> Boolean)? = null): Sequence<T>
        = findAll(predicate, T::class.java, maxDepth)

    fun <T : DeclarationHolder> findAll(predicate: ((T) -> Boolean)?, targetClass: Class<T>, maxDepth: Int): Sequence<T> {
        if (maxDepth <= 0)
            return emptySequence()

        var sequence = _subDeclarations.asSequence().filterIsInstance(targetClass)
        if (predicate != null)
            sequence = sequence.filter(predicate)

        return if (maxDepth == 1) sequence
        else sequence + _subDeclarations.asSequence().flatMap { it.findAll(predicate, targetClass, maxDepth-1) }
    }
}

fun DeclarationHolder.parentOrSelf(): DeclarationHolder
        = (this as? DeclarationBase)?.parent ?: this